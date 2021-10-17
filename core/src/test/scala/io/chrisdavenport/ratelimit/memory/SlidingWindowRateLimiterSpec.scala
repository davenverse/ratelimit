package io.chrisdavenport.ratelimit.memory

import munit.CatsEffectSuite
import cats.effect._
import io.chrisdavenport.ratelimit.RateLimiter
import scala.concurrent.duration._
import io.chrisdavenport.mapref.MapRef

class SlidingWindowRateLimiterSpec extends CatsEffectSuite {

  test("State should be correct") {
    SlidingWindowRateLimiter.build[IO, String](Function.const(1), 6).use(rl => 
      for {
        rl1 <- rl.get("foo") // Does not modify so does not count against it
        rl2 <- rl.getAndDecrement("foo") // does should drop to 0, but should be allowed through
        rl3 <- rl.getAndDecrement("foo") // has already used its 1, should be rate limited
      } yield {
        assertEquals(rl1.remaining.remaining, 1L)
        assertEquals(rl2.remaining.remaining, 0L)
        assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl3.remaining.remaining, 0L)
        assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldRateLimit)
      }
    )
  }

  test("State should reset automatically") {
    val mapref = MapRef.defaultedMapRef(MapRef.inSingleImmutableMap[SyncIO, IO, (String, Long), Long](Map()).unsafeRunSync(), 0L)
    val rl = new SlidingWindowRateLimiter.SlidingWindow(Function.const(2), 2, mapref)

    // Mocking Time!
    for {
      rl1 <- rl.get("foo").run(2.seconds) // Does not modify so does not count against it
      rl2 <- rl.getAndDecrement("foo").run(2.seconds) // does should drop to 0, but should be allowed through
      rl3 <- rl.getAndDecrement("foo").run(2.seconds) // has already used its 1, should be rate limited
      rl4 <- rl.get("foo").run(4.seconds)
      rl5 <- rl.getAndDecrement("foo").run(5.seconds)
      rl6 <- rl.getAndDecrement("foo").run(5.seconds)
      rl7 <- rl.getAndDecrement("foo").run(5.seconds)
    } yield {
      assertEquals(rl1.remaining.remaining, 2L)
      assertEquals(rl2.remaining.remaining, 1L)
      assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl3.remaining.remaining, 0L)
      assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl4.remaining.remaining, 1L)
      assertEquals(rl4.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl5.remaining.remaining, 1L)
      assertEquals(rl5.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl6.remaining.remaining, 0L)
      assertEquals(rl6.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl7.remaining.remaining, 0L)
      assertEquals(rl7.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldRateLimit)
    }
  }

  test("Reset Timeout Should Respect Sliding Window") {
    val mapref = MapRef.defaultedMapRef(MapRef.inSingleImmutableMap[SyncIO, IO, (String, Long), Long](Map()).unsafeRunSync(), 0L)
    val rl = new SlidingWindowRateLimiter.SlidingWindow(Function.const(2), 60, mapref)

    // Mocking Time!
    for {
      rl2 <- rl.getAndDecrement("foo").run(0.seconds)
      rl3 <- rl.getAndDecrement("foo").run(0.seconds)
      rl4 <- rl.getAndDecrement("foo").run(89.seconds) 
    } yield {
      assertEquals(rl2.remaining.remaining, 1L)
      assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl3.remaining.remaining, 0L)
      assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl4.remaining.remaining, 0L)
      assertEquals(rl4.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      assertEquals(rl4.reset.resetInSeconds, 1L)
    }
  }


}