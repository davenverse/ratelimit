package io.chrisdavenport.ratelimit

import cats._

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
  case class RateLimitLimit(limit: Long, policy: Option[QuotaPolicy])
  case class RateLimitRemaining(remaining: Long)
  case class RateLimitReset(timeLeftInWindowSeconds: Long)

  sealed trait WhetherToRateLimit
  object WhetherToRateLimit {
    case object ShouldRateLimit extends WhetherToRateLimit
    case object ShouldNotRateLimit extends WhetherToRateLimit
  }

  case class RateLimit(whetherToRateLimit: WhetherToRateLimit, limit: RateLimitLimit, remaining: RateLimitRemaining, reset: RateLimitReset)

  // This has no stack trace. This should be fast and immediately logged or handled.
  // You Are free to use a different throwable, and provide
  trait RateLimited extends RuntimeException
  case class FastRateLimited[K](key: K, info: RateLimit) extends Throwable(s"RateLimiter with key $key failed") with scala.util.control.NoStackTrace


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