package com.karyo.demo.service

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Single time anchor for a seed run; day 0 is (historyDays-1) days before [anchor]. */
class DemoClock(val anchor: Instant, private val historyDays: Int) {
    val today: Instant get() = anchor
    private val start: Instant = anchor.minus((historyDays - 1).toLong(), ChronoUnit.DAYS)
    fun dayInstant(dayOffsetFromStart: Int, secondsIntoDay: Long = 0): Instant =
        start.plus(dayOffsetFromStart.toLong(), ChronoUnit.DAYS).plusSeconds(secondsIntoDay)
    fun daysAgo(n: Long): Instant = anchor.minus(n, ChronoUnit.DAYS)
    val days: Int get() = historyDays
}
