package io.chrisdavenport.ratelimit.memory

import io.chrisdavenport.ratelimit.RateLimiter
import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import cats.data.Kleisli
import scala.concurrent.duration._
import io.chrisdavenport.mapref.MapRef
import Window._

object FixedWindowRateLimiter {

  def build[F[_]: Temporal, K](maxRate: K => Long, periodSeconds: Long): Resource[F, RateLimiter[F, K]] = for {
    ref <- Resource.eval(Ref.of(Map.empty[(K, Long), Long])) 
    mapRef = MapRef.defaultedMapRef(
      MapRef.fromSingleImmutableMapRef[F, (K, Long), Long](ref),
      0L
    )
    _ <- clearNonPeriodKeys(periodSeconds, ref).background
  } yield new FixedWindow(maxRate, periodSeconds, mapRef).mapK(kleisliToTemporal[F])

  private[ratelimit] def clearNonPeriodKeys[F[_]: Temporal, K](periodSeconds: Long, ref: Ref[F, Map[(K, Long), Long]]): F[Unit] = 
    Temporal[F].realTime.flatMap{fd => 
      val pi = getPeriodInfo(fromFiniteDuration(fd), periodSeconds)
      ref.update{map => 
        val outOfPeriod = map.keys.collect{
          case (k, period) if period != pi.periodNumber => (k, period)
        }
        map -- outOfPeriod
      }
    } >> Temporal[F].sleep(periodSeconds.seconds) >> clearNonPeriodKeys(periodSeconds, ref)

  private def kleisliToTemporal[F[_]: Temporal]: Kleisli[F, FiniteDuration, *] ~> F = 
    new (Kleisli[F, FiniteDuration, *] ~> F){
      def apply[A](fa: Kleisli[F,FiniteDuration,A]): F[A] = Temporal[F].realTime.flatMap(fa.run(_))
    }

  private[ratelimit] class FixedWindow[F[_]: MonadThrow, K](maxRate: K => Long, periodSeconds: Long, mapRef: MapRef[F, (K, Long), Long]) extends RateLimiter[Kleisli[F, FiniteDuration, *], K]{

    val comment = RateLimiter.QuotaComment("comment", Either.right("fixed window"))
    def limit(k: K) = {
      val max = maxRate(k)
      RateLimiter.RateLimitLimit(max, RateLimiter.QuotaPolicy(max, periodSeconds, comment :: Nil) :: Nil)
    }

    def createRateLimit(pi: PeriodInfo, k: K, currentCount: Long): RateLimiter.RateLimit = {
      val reset = RateLimiter.RateLimitReset(pi.secondsLeftInPeriod)
      val l = limit(k)
      val remain = l.limit - currentCount
      val remaining = RateLimiter.RateLimitRemaining(Math.max(remain, 0))
      RateLimiter.RateLimit(
        if (remain < 0) RateLimiter.WhetherToRateLimit.ShouldRateLimit else RateLimiter.WhetherToRateLimit.ShouldNotRateLimit,
        l,
        remaining,
        reset
      )
    }

    def periodInfo(fd: FiniteDuration): PeriodInfo = getPeriodInfo(fromFiniteDuration(fd), periodSeconds)

    def get(id: K): Kleisli[F,FiniteDuration,RateLimiter.RateLimit] = Kleisli{fd => 
      val pi = periodInfo(fd)
      mapRef((id, pi.periodNumber)).get.map(createRateLimit(pi, id, _))
    }
    
    def getAndDecrement(id: K): Kleisli[F,FiniteDuration,RateLimiter.RateLimit] = Kleisli{fd =>
      val pi = periodInfo(fd)
      mapRef((id, pi.periodNumber)).modify(l => (l+1, l+1)).map(createRateLimit(pi, id, _))
    }
    
    def rateLimit(id: K): Kleisli[F,FiniteDuration,RateLimiter.RateLimit] = getAndDecrement(id).flatMap{
      case r@RateLimiter.RateLimit(RateLimiter.WhetherToRateLimit.ShouldRateLimit, _, _, _) => 
        Kleisli.liftF(RateLimiter.FastRateLimited(id, r).raiseError[F, RateLimiter.RateLimit])
      case otherwise =>  Kleisli.liftF(otherwise.pure[F])
    }
  }

}