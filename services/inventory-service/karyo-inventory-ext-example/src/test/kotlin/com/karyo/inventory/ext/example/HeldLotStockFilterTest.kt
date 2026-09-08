package com.karyo.inventory.ext.example

import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.vo.StockSelectionRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HeldLotStockFilterTest {
    private val stocks = mockk<StockUnitLookup>()
    private val filter = HeldLotStockFilter(stocks)
    private val request = StockSelectionRequest(itemDataId = 42, amount = BigDecimal.TEN, clientId = 73)

    @Test
    fun `removes held lots while preserving candidate order with one owner-scoped batch read`() {
        every { stocks.findByIds(setOf(8, 3, 5), 73) } returns listOf(
            stock(5, null), stock(3, "EXAMPLE-HOLD-synthetic"), stock(8, "RELEASED"),
        )

        assertThat(filter.filter(listOf(8, 3, 5), request)).containsExactly(8L, 5L)
        verify(exactly = 1) { stocks.findByIds(setOf(8, 3, 5), 73) }
        verify(exactly = 0) { stocks.findByIds(any<Set<Long>>()) }
    }

    @Test
    fun `never adds lookup rows and drops unknown or wrong-item candidates`() {
        every { stocks.findByIds(setOf(1, 2, 3), 73) } returns listOf(
            stock(1, "AVAILABLE"), stock(2, "AVAILABLE", itemId = 99), stock(100, "AVAILABLE"),
        )

        assertThat(filter.filter(listOf(1, 2, 3), request)).containsExactly(1L)
    }

    @Test
    fun `all held candidates produce an empty candidate list`() {
        every { stocks.findByIds(setOf(1), 73) } returns listOf(stock(1, "EXAMPLE-HOLD-only"))
        assertThat(filter.filter(listOf(1), request)).isEmpty()
    }

    @Test
    fun `ordinary and absent lot numbers are unchanged`() {
        every { stocks.findByIds(setOf(1, 2), 73) } returns listOf(stock(2, null), stock(1, "NORMAL"))
        assertThat(filter.filter(listOf(1, 2), request)).containsExactly(1L, 2L)
        assertThat(request.amount).isEqualByComparingTo(BigDecimal.TEN)
    }

    @Test
    fun `empty input does not read stock`() {
        assertThat(filter.filter(emptyList(), request)).isEmpty()
        verify(exactly = 0) { stocks.findByIds(any<Set<Long>>(), any()) }
    }

    private fun stock(stockId: Long, lot: String?, itemId: Long = 42): StockUnitResponse = mockk {
        every { id } returns stockId
        every { itemDataId } returns itemId
        every { lotNumber } returns lot
    }
}
