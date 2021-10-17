package io.chrisdavenport.ratelimit

import cats._
import cats.syntax.all._
import cats.data.Kleisli
import com.comcast.ip4s.IpAddress

trait RateLimiter[F[_], K]{
  def get(id: K): F[RateLimiter.RateLimit]
  def getAndDecrement(id: K): F[RateLimiter.RateLimit]

  // Fails Request at this step, to be handled to 429 Too Many Requests and include a RetryAfter header
  def rateLimit(id: K): F[RateLimiter.RateLimit] 
  
  def mapK[G[_]](fk: F ~> G): RateLimiter[G, K] = 
    new RateLimiter.TransformRateLimiter[F, G, K](this, fk)
  def contramap[I](f: I => K): RateLimiter[F, I] = 
    new RateLimiter.ContravariantRateLimiter[F, K, I](this, f)
}

object RateLimiter {
  // Should Be in Http4s As Modeled Headers
  case class QuotaComment(token: String, value: Either[Long, String])
  case class QuotaPolicy(limit: Long, timeWindowSeconds: Long, comments: List[QuotaComment])
  case class RateLimitLimit(limit: Long, policy: List[QuotaPolicy])
  object RateLimitLimit {
    import org.http4s._
    import org.typelevel.ci._
    // TODO Complete modeling of policy and comment
    implicit val headerInstance: Header[RateLimitLimit, Header.Single] =
      Header.createRendered(
        ci"RateLimit-Limit",
        _.limit,
        Parsers.fromParserIncomplete(Parsers.limit, "Invalid RateLimit-Limit Header")
      )

  }
  case class RateLimitRemaining(remaining: Long)
  object RateLimitRemaining {
    import org.http4s._
    import org.typelevel.ci._
    implicit val headerInstance: Header[RateLimitRemaining, Header.Single] =
      Header.createRendered(
        ci"RateLimit-Remaining",
        _.remaining,
        Parsers.fromParser(Parsers.remaining, "Invalid RateLimit-Remaining Header")
      )
  }
  case class RateLimitReset(resetInSeconds: Long)
  object RateLimitReset {
    import org.http4s._
    import org.typelevel.ci._
    implicit val headerInstance: Header[RateLimitReset, Header.Single] =
      Header.createRendered(
        ci"RateLimit-Reset",
        _.resetInSeconds,
        Parsers.fromParser(Parsers.reset, "Invalid RateLimit-Reset Header")
      )
  }

  sealed trait WhetherToRateLimit
  object WhetherToRateLimit {
    case object ShouldRateLimit extends WhetherToRateLimit
    case object ShouldNotRateLimit extends WhetherToRateLimit
  }

  object Middleware {
    import org.http4s._
    import cats.data.OptionT
    def byIp[F[_]: MonadThrow, G[_]](rateLimiter: RateLimiter[F, IpAddress])(http: Http[F, G]): Http[F, G] = Kleisli{
      (req: Request[G]) => 
        val address = req.remoteAddr
        address match{
          case Some(ip) =>
            rateLimiter.rateLimit(ip).redeemWith(
              {
                case e: RateLimiter.RateLimited => 
                  Response[G](Status.TooManyRequests)
                    .putHeaders(e.info.limit, e.info.remaining, e.info.reset).pure[F]
                case e => MonadThrow[F].raiseError[Response[G]](e)
              },
              {(result: RateLimiter.RateLimit) =>
                http.run(req).map(resp => resp.putHeaders(result.limit, result.remaining, result.reset))
              }
            )
          case None => http.run(req)
        }
    }

    def byContext[F[_]: MonadThrow, K](rateLimiter: RateLimiter[F, K], http: ContextRoutes[K, F]): ContextRoutes[K, F] = 
      Kleisli{
        case c@ContextRequest(k, _) => 
          OptionT.liftF(rateLimiter.rateLimit(k)).redeemWith(
            {
              case e: RateLimiter.RateLimited => 
                Response[F](Status.TooManyRequests)
                  .putHeaders(e.info.limit, e.info.remaining, e.info.reset).pure[OptionT[F, *]]
              case e => MonadThrow[OptionT[F, *]].raiseError[Response[F]](e)
            },
            {(result: RateLimiter.RateLimit) =>
              http.run(c).map(resp => resp.putHeaders(result.limit, result.remaining, result.reset))
            }
          )
      }
  }

  private object Parsers {
    import cats.parse._
    import cats.parse.Parser._
    val digit: Parser[Char] =
      charIn(0x30.toChar to 0x39.toChar)
    val NonNegativeLong: Parser[Long] = digit.rep.string.mapFilter { s =>
      try Some(s.toLong)
      catch {
        case _: NumberFormatException => None
      }
    }

    // Used to ignore the complex pieces that dont work in our render
    def fromParserIncomplete[A](parser: Parser0[A], errorMessage: => String)(
      s: String): org.http4s.ParseResult[A] =
    try parser.parse(s).map(_._2).leftMap(e => org.http4s.ParseFailure(errorMessage, e.toString))
    catch { case p: org.http4s.ParseFailure => p.asLeft[A] }

    def fromParser[A](parser: Parser0[A], errorMessage: => String)(
      s: String): org.http4s.ParseResult[A] =
    try parser.parseAll(s).leftMap(e => org.http4s.ParseFailure(errorMessage, e.toString))
    catch { case p: org.http4s.ParseFailure => p.asLeft[A] }

    val reset = NonNegativeLong.map(RateLimitReset(_))
    val remaining = NonNegativeLong.map(RateLimitRemaining(_))
    val limit = NonNegativeLong.map(RateLimitLimit(_, List.empty))

  }

  case class RateLimit(whetherToRateLimit: WhetherToRateLimit, limit: RateLimitLimit, remaining: RateLimitRemaining, reset: RateLimitReset)

  // This has no stack trace. This should be fast and immediately logged or handled.
  // You Are free to use a different throwable, and provide
  abstract class RateLimited(description: String) extends RuntimeException(description){
    def info: RateLimit
  }
  case class FastRateLimited[K](key: K, info: RateLimit) extends RateLimited(s"RateLimiter with key $key failed") with scala.util.control.NoStackTrace


  private class ContravariantRateLimiter[F[_], K, I](r: RateLimiter[F, K], f: I => K) extends RateLimiter[F, I]{
    def get(id: I): F[RateLimit] = r.get(f(id))
    def getAndDecrement(id: I): F[RateLimit] = r.getAndDecrement(f(id))
    def rateLimit(id: I): F[RateLimit] = r.rateLimit(f(id))
  }
  private class TransformRateLimiter[F[_], G[_], K](r: RateLimiter[F, K], fk: F ~> G) extends RateLimiter[G, K]{
    def get(id: K): G[RateLimit] = fk(r.get(id))
    def getAndDecrement(id: K): G[RateLimit] = fk(r.getAndDecrement(id))
    def rateLimit(id: K): G[RateLimit] = fk(r.rateLimit(id))
    
  }
}