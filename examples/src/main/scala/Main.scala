package io.chrisdavenport.ratelimit

import cats.effect._
import io.chrisdavenport.rediculous._
import io.chrisdavenport.ratelimit.rediculous.RedisRateLimiter
import com.comcast.ip4s._
import fs2.io.net._

object Main extends IOApp {

  implicit class LogOps[A](a: IO[A]){
    def flatPrint: IO[A] = a.flatTap(a => IO(println(a)))
  }


  def run(args: List[String]): IO[ExitCode] = {
    val r = for {
      // maxQueued: How many elements before new submissions semantically block. Tradeoff of memory to queue jobs. 
      // Default 1000 is good for small servers. But can easily take 100,000.
      // workers: How many threads will process pipelined messages.
      connection <- RedisConnection.queued[IO](Network[IO], host"localhost", port"6379", maxQueued = 10000, workers = 2)
    } yield connection
    r.use{ connection => 

      val rl = RedisRateLimiter.fixedWindow(connection, Function.const(5), 60)

      rl.get("foo").flatPrint >>
      rl.getAndDecrement("foo").flatPrint >> 
      rl.getAndDecrement("foo").flatPrint >> 
      rl.getAndDecrement("foo").flatPrint >> 
      rl.getAndDecrement("foo").flatPrint >> 
      rl.getAndDecrement("foo").flatPrint >> 
      rl.getAndDecrement("foo").flatPrint 

    }.as(ExitCode.Success)
  }

}


