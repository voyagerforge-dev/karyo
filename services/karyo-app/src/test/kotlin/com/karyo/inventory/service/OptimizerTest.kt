package com.karyo.inventory.service

import com.karyo.inventory.api.vo.CompleteHandling
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OptimizerTest {

    private val optimizer = Optimizer()

    private fun amounts(vararg v: Int) = v.map { BigDecimal(it) }
    private fun sum(r: List<BigDecimal>?) = r?.fold(BigDecimal.ZERO) { a, b -> a + b }

    @Test
    fun `AMOUNT_MATCH finds an exact combination`() {
        val result = optimizer.findBestCombination(amounts(30, 40, 70, 25), BigDecimal(70), CompleteHandling.AMOUNT_MATCH)
        assertThat(sum(result)).isEqualByComparingTo(BigDecimal(70))
    }

    @Test
    fun `AMOUNT_MATCH returns null when no exact combination exists`() {
        val result = optimizer.findBestCombination(amounts(30, 45, 80), BigDecimal(70), CompleteHandling.AMOUNT_MATCH)
        assertThat(result).isNull()
    }

    @Test
    fun `AMOUNT_SMALLEST_DIFF minimizes absolute difference`() {
        // target 50; best single is 45 (diff 5) or 55 (diff 5); 30+25=55 (diff 5); 30 alone diff 20.
        val result = optimizer.findBestCombination(amounts(30, 45, 80), BigDecimal(50), CompleteHandling.AMOUNT_SMALLEST_DIFF)
        assertThat((sum(result)!! - BigDecimal(50)).abs()).isLessThanOrEqualTo(BigDecimal(5))
    }

    @Test
    fun `AMOUNT_SMALLEST_PLUS prefers the smallest combination at or above target`() {
        // target 50; combinations >= 50: {80}=80, {30,45}=75. Smallest at-or-above is 75.
        val result = optimizer.findBestCombination(amounts(30, 45, 80), BigDecimal(50), CompleteHandling.AMOUNT_SMALLEST_PLUS)
        assertThat(sum(result)).isEqualByComparingTo(BigDecimal(75))
    }

    @Test
    fun `AMOUNT_SMALLEST_PLUS returns null when nothing reaches the target`() {
        // max total 60 < 100 — no combination is at or above target, so there is no valid smallest-plus.
        val result = optimizer.findBestCombination(amounts(10, 20, 30), BigDecimal(100), CompleteHandling.AMOUNT_SMALLEST_PLUS)
        assertThat(result).isNull()
    }

    @Test
    fun `AMOUNT_MATCH works with fractional (scale-4) amounts`() {
        val result = optimizer.findBestCombination(
            listOf(BigDecimal("12.5000"), BigDecimal("17.5000"), BigDecimal("5.0000")),
            BigDecimal("30.0000"), CompleteHandling.AMOUNT_MATCH,
        )
        assertThat(sum(result)).isEqualByComparingTo(BigDecimal("30.0000")) // 12.5 + 17.5
    }

    @Test
    fun `AMOUNT_MATCH combines duplicate amounts`() {
        // two distinct ULs of 35 each => 70 (combination, not dedup).
        val result = optimizer.findBestCombination(amounts(35, 35, 10), BigDecimal(70), CompleteHandling.AMOUNT_MATCH)
        assertThat(sum(result)).isEqualByComparingTo(BigDecimal(70))
        assertThat(result).hasSize(2)
    }

    @Test
    fun `AMOUNT_MATCH single candidate exact`() {
        val result = optimizer.findBestCombination(amounts(70), BigDecimal(70), CompleteHandling.AMOUNT_MATCH)
        assertThat(sum(result)).isEqualByComparingTo(BigDecimal(70))
        assertThat(result).hasSize(1)
    }

    @Test
    fun `non-combinatorial mode is rejected`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            optimizer.findBestCombination(amounts(30, 40), BigDecimal(70), CompleteHandling.AMOUNT_FIRST_MATCH)
        }
    }

    @Test
    fun `empty candidates returns null`() {
        assertThat(optimizer.findBestCombination(emptyList(), BigDecimal(50), CompleteHandling.AMOUNT_MATCH)).isNull()
    }
}
