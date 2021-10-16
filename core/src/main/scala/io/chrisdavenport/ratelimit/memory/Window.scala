package io.chrisdavenport.ratelimit.memory

import scala.concurrent.duration._

private[memory] object Window {
  private[ratelimit] def fromFiniteDuration(fd: FiniteDuration): Long =
    fd.toSeconds

  private[ratelimit] case class PeriodInfo(periodNumber: Long, secondsLeftInPeriod: Long)

  private[ratelimit] def getPeriodInfo(secondsSinceEpoch: Long, periodSeconds: Long): PeriodInfo = {
    val period = determinePeriod(secondsSinceEpoch, periodSeconds)
    val secondsLeft = secondsLeftInPeriod(secondsSinceEpoch, periodSeconds, period)
    val pi = PeriodInfo(period, secondsLeft)
    pi
  }

  private[ratelimit] def determinePeriod(secondsSinceEpoch: Long, periodSeconds: Long): Long = {
    secondsSinceEpoch / periodSeconds
  }

  private[ratelimit] def secondsLeftInPeriod(secondsSinceEpoch: Long, periodSeconds: Long, periodNumber: Long): Long = {
    val periodEndSeconds = (periodNumber + 1)  * periodSeconds
    periodEndSeconds - secondsSinceEpoch
  }
}