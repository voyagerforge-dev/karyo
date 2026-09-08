package com.karyo.demo

import com.karyo.demo.gen.MoverClass
import com.karyo.demo.gen.VelocitySkew
import com.karyo.demo.service.DemoClock
import com.karyo.demo.service.Rng
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class DemoHelpersTest {
    @Test fun `clock spans the window ending at anchor`() {
        val anchor = Instant.parse("2026-07-13T12:00:00Z")
        val clock = DemoClock(anchor, historyDays = 120)
        assertEquals(anchor, clock.today)
        // day 0 is 119 days before anchor; day 119 is anchor's day
        val day0 = clock.dayInstant(0)
        assertEquals(119, ChronoUnit.DAYS.between(day0.truncatedTo(ChronoUnit.DAYS), anchor.truncatedTo(ChronoUnit.DAYS)))
        assertTrue(clock.dayInstant(119).isAfter(day0))
    }

    @Test fun `rng is reproducible for a seed`() {
        val a = (1..10).map { Rng(42).nextInt(1000) }
        val b = (1..10).map { Rng(42).nextInt(1000) }
        // fresh Rng(42) each call returns the same first draw; sequence from one instance is stable
        val seq1 = Rng(42).let { r -> (1..5).map { r.nextInt(100) } }
        val seq2 = Rng(42).let { r -> (1..5).map { r.nextInt(100) } }
        assertEquals(seq1, seq2)
        assertEquals(a, b)
    }

    @Test fun `velocity skew partitions into A B C by thresholds`() {
        val skus = (1..24).map { "SKU-%02d".format(it) }
        val cls = VelocitySkew.classify(skus)
        assertEquals(24, cls.size)
        assertEquals(4, cls.values.count { it == MoverClass.A })
        assertEquals(8, cls.values.count { it == MoverClass.B })
        assertEquals(12, cls.values.count { it == MoverClass.C })
        assertTrue(VelocitySkew.dailyPickProbability(MoverClass.A) > VelocitySkew.dailyPickProbability(MoverClass.C))
    }
}
