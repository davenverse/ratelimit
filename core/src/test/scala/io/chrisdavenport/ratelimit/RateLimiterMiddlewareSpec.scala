package io.chrisdavenport.ratelimit

import munit.CatsEffectSuite
import cats.effect._
import io.chrisdavenport.ratelimit.RateLimiter
// import scala.concurrent.duration._
// import io.chrisdavenport.mapref.MapRef
import org.http4s.dsl.io._
import org.http4s.HttpRoutes
import io.chrisdavenport.ratelimit.memory.FixedWindowRateLimiter
import com.comcast.ip4s._
import org.http4s._
import org.http4s.syntax.all._
import cats.syntax.all._

class RateLimiterMiddlwareSpec extends CatsEffectSuite {

  test("Should Add Headers to a non rate-limited request") {
    val connection = org.http4s.Request.Connection(
      local = SocketAddress(ip"8.8.8.8", port"8670"),
      remote = SocketAddress(ip"9.9.9.9", port"9999"),
      false
    )
    val app = HttpRoutes.of[IO]{
      case GET -> Root / "foo" =>
        Ok()
      case _ => Forbidden()
    }.orNotFound
    FixedWindowRateLimiter.build[IO, IpAddress](Function.const(5), 60).use(rl => 
      RateLimiter.Middleware.byIp(rl)(app).run(
        Request(Method.GET, uri"http://foo.bar/foo")
          .withAttribute(Request.Keys.ConnectionInfo, connection)
      ).map{resp => 
        val reset = resp.headers.get[RateLimiter.RateLimitReset]
        val remaining = resp.headers.get[RateLimiter.RateLimitRemaining]
        val limit = resp.headers.get[RateLimiter.RateLimitLimit]

        assert(reset.map(_.resetInSeconds < 60L).getOrElse(false), "Reset Timeout in bad window or missing")
        assertEquals(remaining.map(_.remaining), 4L.some)
        assertEquals(limit.map(_.limit), 5L.some)
        assertEquals(resp.status, Status.Ok)
      
      }
    )
  }

  test("Should Rate Limit Request") {
    val remoteIp = ip"9.9.9.9"
    val connection = org.http4s.Request.Connection(
      local = SocketAddress(ip"8.8.8.8", port"8670"),
      remote = SocketAddress(remoteIp, port"9999"),
      false
    )
    val app = HttpRoutes.of[IO]{
      case GET -> Root / "foo" =>
        Ok()
      case _ => Forbidden()
    }.orNotFound
    FixedWindowRateLimiter.build[IO, IpAddress](Function.const(2), 60).use(rl => 
      rl.getAndDecrement(remoteIp) >> 
      rl.getAndDecrement(remoteIp) >>
      RateLimiter.Middleware.byIp(rl)(app).run(
        Request(Method.GET, uri"http://foo.bar/foo")
          .withAttribute(Request.Keys.ConnectionInfo, connection)
      ).map{resp => 
        val reset = resp.headers.get[RateLimiter.RateLimitReset]
        val remaining = resp.headers.get[RateLimiter.RateLimitRemaining]
        val limit = resp.headers.get[RateLimiter.RateLimitLimit]

        assert(reset.map(_.resetInSeconds < 60L).getOrElse(false), "Reset Timeout in bad window or missing")
        assertEquals(remaining.map(_.remaining), 0L.some)
        assertEquals(limit.map(_.limit), 2L.some)
        assertEquals(resp.status, Status.TooManyRequests)
      
      }
    )
  }
}