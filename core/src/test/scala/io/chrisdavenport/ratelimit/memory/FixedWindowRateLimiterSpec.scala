package io.chrisdavenport.ratelimit.memory


import munit.CatsEffectSuite
import cats.effect._
import io.chrisdavenport.ratelimit.RateLimiter
import scala.concurrent.duration._

class FixedWindowRateLimiterSpec extends CatsEffectSuite {

  test("State should be correct") {
    FixedWindowRateLimiter.build[IO, String](Function.const(1), 5).use(rl => 
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
    FixedWindowRateLimiter.build[IO, String](Function.const(1), 2).use(rl => 
      for {
        rl1 <- rl.get("foo") // Does not modify so does not count against it
        rl2 <- rl.getAndDecrement("foo") // does should drop to 0, but should be allowed through
        _ <- Temporal[IO].sleep(2.seconds)
        rl3 <- rl.getAndDecrement("foo") // has already used its 1, should be rate limited
        
      } yield {
        // println((rl1, rl2, rl3))
        assertEquals(rl1.remaining.remaining, 1L)
        assertEquals(rl2.remaining.remaining, 0L)
        assertEquals(rl2.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
        assertEquals(rl3.remaining.remaining, 0L)
        assertEquals(rl3.whetherToRateLimit, RateLimiter.WhetherToRateLimit.ShouldNotRateLimit)
      }
    )
  }

}

