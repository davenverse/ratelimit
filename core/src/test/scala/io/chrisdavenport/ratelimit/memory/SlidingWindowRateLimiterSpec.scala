package io.chrisdavenport.ratelimit.memory

import munit.CatsEffectSuite
import cats.effect._
import io.chrisdavenport.ratelimit.RateLimiter
import scala.concurrent.duration._

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
    SlidingWindowRateLimiter.build[IO, String](Function.const(2), 2).use(rl => 
      for {
        rl1 <- rl.get("foo") // Does not modify so does not count against it
        rl2 <- rl.getAndDecrement("foo") // does should drop to 0, but should be allowed through
        rl3 <- rl.getAndDecrement("foo") // has already used its 1, should be rate limited
        _ <- Temporal[IO].sleep(2.1.seconds)
        rl4 <- rl.get("foo")
        _ <- Temporal[IO].sleep(1.second)
        rl5 <- rl.getAndDecrement("foo")
        rl6 <- rl.getAndDecrement("foo")
        
      } yield {
        // println((rl1, rl2, rl3))
        assertEquals(rl1.remaining.remaining, 2L)
        assertEquals(rl2.remaining.remaining, 1L)
        assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl3.remaining.remaining, 0L)
        assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl4.remaining.remaining, 0L)
        assertEquals(rl4.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl5.remaining.remaining, 0L)
        assertEquals(rl5.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl6.remaining.remaining, 0L)
        assertEquals(rl6.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldRateLimit)
      }
    )
  }

}