package com.karyo.reporting.service

import java.util.Locale
import kotlin.math.abs

object KpiFormat {
    fun percent(ratio: Double): String = String.format(Locale.US, "%.1f%%", ratio * 100)
    fun hours(h: Double): String = String.format(Locale.US, "%.1fh", h)
    fun perDay(units: Double): String = String.format(Locale.US, "%,d/day", units.toLong())

    fun deltaPercent(current: Double, prior: Double): String {
        val diff = (current - prior) * 100
        return String.format(Locale.US, "%+.1f%%", diff)
    }

    fun deltaHours(current: Double, prior: Double): String =
        String.format(Locale.US, "%+.1fh", current - prior)

    fun deltaUnits(current: Double, prior: Double): String {
        val d = (current - prior).toLong()
        return (if (d >= 0) "+" else "") + String.format(Locale.US, "%,d", d) + "/day"
    }

    /** "up" when the change is in the good direction (or no change), else "warning". */
    fun tone(higherIsGood: Boolean, current: Double, prior: Double): String {
        if (abs(current - prior) < 1e-9) return "up"
        val improved = if (higherIsGood) current > prior else current < prior
        return if (improved) "up" else "warning"
    }
}
