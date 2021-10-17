package io.chrisdavenport.ratelimit.memory


import munit.CatsEffectSuite
import cats.effect._
import io.chrisdavenport.ratelimit.RateLimiter
import scala.concurrent.duration._
import io.chrisdavenport.mapref.MapRef

class SlidingLogRateLimiterSpec extends CatsEffectSuite {

  // test("State should be correct") {
  //   for {
  //     rl <- SlidingLogRateLimiter.of[IO, String](Function.const(1), 5)
  //     rl1 <- rl.get("foo") // Does not modify so does not count against it
  //     rl2 <- rl.getAndDecrement("foo") // does should drop to 0, but should be allowed through
  //     rl3 <- rl.getAndDecrement("foo") // has already used its 1, should be rate limited
  //   } yield {
  //     assertEquals(rl1.remaining.remaining, 1L)
  //     assertEquals(rl2.remaining.remaining, 0L)
  //     assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
  //     assertEquals(rl3.remaining.remaining, 0L)
  //     assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldRateLimit)
  //   }
  // }

  test("State should reset automatically") {
    val mapref = MapRef.defaultedMapRef(MapRef.inSingleImmutableMap[SyncIO, IO, String, (Long, List[Long])](Map()).unsafeRunSync(), (0L, List.empty))
    val rl = new SlidingLogRateLimiter.SlidingLog(Function.const(1), 2, Int.MaxValue, mapref)
      for {
        rl1 <- rl.get("foo").run(0.seconds) // Does not modify so does not count against it
        rl2 <- rl.getAndDecrement("foo").run(1.seconds) // does should drop to 0, but should be allowed through
        rl3 <- rl.getAndDecrement("foo").run(4.seconds) // has already used its 1, should be rate limited
        
      } yield {
        assertEquals(rl1.remaining.remaining, 1L)
        assertEquals(rl2.remaining.remaining, 0L)
        assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl3.remaining.remaining, 0L)
        assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      }
  }

}

