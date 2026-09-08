package com.karyo.fulfillment.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BulkFanOutTest {
    private fun s(id: Long, planned: Int) = BulkSlice(id, BigDecimal(planned))
    private fun s(id: Long, planned: String) = BulkSlice(id, BigDecimal(planned))

    @Test
    fun `full pick fills every slice in pick id order`() {
        val out = BulkFanOut.allocate(listOf(s(3, 5), s(1, 20), s(2, 15)), BigDecimal(40))
        assertEquals(listOf(1L to 20, 2L to 15, 3L to 5), out.map { it.pickId to it.amount.toInt() })
    }

    @Test
    fun `short pick fills the head and leaves the tail short, last partial`() {
        val out = BulkFanOut.allocate(listOf(s(1, 20), s(2, 15), s(3, 5)), BigDecimal(27))
        assertEquals(listOf(1L to 20, 2L to 7, 3L to 0), out.map { it.pickId to it.amount.toInt() })
    }

    @Test
    fun `single slice`() {
        assertEquals(listOf(9L to 4), BulkFanOut.allocate(listOf(s(9, 4)), BigDecimal(4)).map { it.pickId to it.amount.toInt() })
    }

    @Test
    fun `fractional amounts are not pinned`() {
        val out = BulkFanOut.allocate(listOf(s(1, "10"), s(2, "5")), BigDecimal("12.5"))
        val byId = out.associate { it.pickId to it.amount }
        assertEquals(0, byId.getValue(1L).compareTo(BigDecimal("10")))
        assertEquals(0, byId.getValue(2L).compareTo(BigDecimal("2.5")))
    }

    @Test
    fun `over-pick and non-positive are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BulkFanOut.allocate(listOf(s(1, 5)), BigDecimal(6)) }
        assertThrows(IllegalArgumentException::class.java) { BulkFanOut.allocate(listOf(s(1, 5)), BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { BulkFanOut.allocate(emptyList(), BigDecimal.ONE) }
    }
}
