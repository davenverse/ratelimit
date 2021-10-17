package io.chrisdavenport.ratelimit.memory

import io.chrisdavenport.ratelimit.RateLimiter
import cats._
import cats.syntax.all._
import cats.effect._
import cats.data.Kleisli
import scala.concurrent.duration._
import io.chrisdavenport.mapref.MapRef

object SlidingLogRateLimiter {

  def of[F[_]: Temporal, K](maxRate: K => Long, periodSeconds: Long, maxSize: Int = Int.MaxValue): F[RateLimiter[F, K]] = for {
    ref <- MapRef.ofSingleImmutableMap[F, K, (Long, Option[Long], List[Long])](Map.empty)
    mapRef = MapRef.defaultedMapRef[F, K, (Long, Option[Long], List[Long])](
      ref,
      (0, None, List.empty[Long])
    )
  } yield new SlidingLog(maxRate, periodSeconds, maxSize, mapRef).mapK(kleisliToTemporal[F])

  private def kleisliToTemporal[F[_]: Temporal]: Kleisli[F, FiniteDuration, *] ~> F = 
    new (Kleisli[F, FiniteDuration, *] ~> F){
      def apply[A](fa: Kleisli[F,FiniteDuration,A]): F[A] = Temporal[F].realTime.flatMap(fa.run(_))
    }

  // Assumes Time in Seconds Always is positive - Probably a very dangerous assumption (think a binary tree could do this as well without the assumption)
  private[ratelimit] class SlidingLog[F[_]: MonadThrow, K](maxRate: K => Long, periodSeconds: Long, maxSize: Int, mapRef: MapRef[F, K, (Long, Option[Long], List[Long])]) extends RateLimiter[Kleisli[F, FiniteDuration, *], K]{

    val comment = RateLimiter.QuotaComment("comment", Either.right("sliding log"))
    def limit(k: K) = {
      val max = maxRate(k)
      RateLimiter.RateLimitLimit(max, RateLimiter.QuotaPolicy(max, periodSeconds, comment :: Nil) :: Nil)
    }

    def createRateLimit(id: K, currentSeconds: Long, currentCount: Int, oldestTime: Option[Long]): RateLimiter.RateLimit ={
        val l = limit(id)
        val remain = l.limit - currentCount
        val reset = RateLimiter.RateLimitReset(
          oldestTime.map(_ + periodSeconds - currentSeconds).getOrElse(periodSeconds) 
        )
        val remaining = RateLimiter.RateLimitRemaining(Math.max(remain, 0))
        val rl = RateLimiter.RateLimit(
          if (remain < 0) RateLimiter.WhetherToRateLimit.ShouldRateLimit else RateLimiter.WhetherToRateLimit.ShouldNotRateLimit,
          l,
          remaining,
          reset
        )
        rl
    }      
    def get(id: K): Kleisli[F,FiniteDuration,RateLimiter.RateLimit] = Kleisli{ fd => 
      val seconds = fd.toSeconds
      val tooOld = seconds - periodSeconds
      mapRef(id).modify{ case (modified, old, l) => 
        val fixed = if (modified != seconds) l.takeWhile(_ >= tooOld) else l
        val oldest: Option[Long] = if (modified != seconds) fixed.lastOption else old
        val x = ((modified, oldest, fixed), (fixed.size, oldest))
        x
      }.map{ case (count, oldest) => 
        createRateLimit(id, seconds, count, oldest)
      }
    }

    def getAndDecrement(id: K): Kleisli[F,FiniteDuration,RateLimiter.RateLimit] = Kleisli{ fd =>
      val seconds = fd.toSeconds
      val tooOld = seconds - periodSeconds
      mapRef(id).modify{ case (modified, old, l) => 
        val fixed = if (modified != seconds) l.takeWhile(_ >= tooOld) else l
        val oldest: Option[Long] = if (modified != seconds) fixed.lastOption else old
        val size = fixed.size
        val out = if (size < maxSize) seconds :: fixed else fixed
        val x = ((seconds, oldest, out), (out.size, oldest))
        x
      }.map{ case (count, oldest) => 
        createRateLimit(id, seconds, count, oldest)
      }
    }
    def rateLimit(id: K): Kleisli[F,FiniteDuration,RateLimiter.RateLimit] = getAndDecrement(id).flatMap{
      case r@RateLimiter.RateLimit(RateLimiter.WhetherToRateLimit.ShouldRateLimit, _, _, _) => 
        Kleisli.liftF(RateLimiter.FastRateLimited(id, r).raiseError[F, RateLimiter.RateLimit])
      case otherwise =>  Kleisli.liftF(otherwise.pure[F])
    }
  }
}