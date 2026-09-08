package com.karyo.inventory.service

import com.karyo.inventory.api.vo.CompleteHandling
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal

/**
 * Bounded combinatorial optimizer for completeHandling modes AMOUNT_MATCH / AMOUNT_SMALLEST_DIFF /
 * AMOUNT_SMALLEST_PLUS (behavioral parity with myWMS's amount-optimization behavior --
 * independent implementation). DFS over combinations of candidate amounts, scored by closeness
 * to the target; returns the amounts of the best combination, or null.
 *
 * Operates on plain [BigDecimal] amounts so it is pure and unit-testable; the caller maps amounts
 * back to its [com.karyo.inventory.domain.model.StockUnit]s (preserving candidate order for ties).
 * Amounts are assumed to be scale ≤ 4 (the `stock_units.amount` DB scale) so the ×[SCALE]→Long
 * collapse used for scoring is lossless.
 *
 * Bounds (myWMS uses a 10s wall clock): cap candidates at [MAX_CANDIDATES] (already FIFO-ordered,
 * so the oldest are considered) and DFS nodes at [MAX_NODES]; exact match short-circuits. Both
 * truncations are logged so a suboptimal result is observable, never silent.
 */
@ApplicationScoped
class Optimizer {

    // Bounded combinatorial DFS with per-mode scoring/pruning — inherently branchy; covered by
    // OptimizerTest. Splitting the closure-capturing dfs out would obscure more than it simplifies.
    @Suppress("CyclomaticComplexMethod")
    fun findBestCombination(
        candidateAmounts: List<BigDecimal>,
        target: BigDecimal,
        mode: CompleteHandling,
    ): List<BigDecimal>? {
        require(
            mode == CompleteHandling.AMOUNT_MATCH ||
                mode == CompleteHandling.AMOUNT_SMALLEST_DIFF ||
                mode == CompleteHandling.AMOUNT_SMALLEST_PLUS,
        ) { "Optimizer handles only combinatorial completeHandling modes (3-5), got $mode" }

        if (candidateAmounts.isEmpty()) return null
        if (candidateAmounts.size > MAX_CANDIDATES) {
            LOG.warnf(
                "Optimizer truncating %d candidates to %d; a complete combination may be missed/suboptimal",
                candidateAmounts.size, MAX_CANDIDATES,
            )
        }
        val pool = candidateAmounts.take(MAX_CANDIDATES)

        var best: List<BigDecimal>? = null
        var bestScore: Long? = null
        var nodes = 0

        // DFS over subsets; `start` enforces combinations (not permutations).
        fun dfs(start: Int, chosen: ArrayList<BigDecimal>, sum: BigDecimal) {
            if (nodes++ > MAX_NODES) return
            if (chosen.isNotEmpty()) {
                val s = score(sum, target, mode)
                if (s != null && (bestScore == null || s < bestScore!!)) {
                    bestScore = s
                    best = ArrayList(chosen)
                }
                if (s == 0L) return // perfect (exact) — stop exploring this branch
                // SMALLEST_PLUS: reaching/exceeding target is terminal — going deeper only overshoots
                // more, so the smallest crossing on each path is found by stopping here (optimal + bounded).
                if (mode == CompleteHandling.AMOUNT_SMALLEST_PLUS && sum >= target) return
            }
            // MATCH / SMALLEST_DIFF: once sum exceeds target, a longer combo only worsens |diff|.
            if (mode != CompleteHandling.AMOUNT_SMALLEST_PLUS && sum > target) return
            for (i in start until pool.size) {
                chosen.add(pool[i])
                dfs(i + 1, chosen, sum + pool[i])
                chosen.removeAt(chosen.size - 1)
                if (bestScore == 0L) return // global early-exit on exact
            }
        }
        dfs(0, ArrayList(), BigDecimal.ZERO)
        if (nodes > MAX_NODES) {
            LOG.warnf("Optimizer hit the %d-node budget; the returned combination may be suboptimal", MAX_NODES)
        }

        // AMOUNT_MATCH accepts only an exact total.
        if (mode == CompleteHandling.AMOUNT_MATCH && bestScore != 0L) return null
        return best
    }

    /**
     * Lower score = better; null = combination is unacceptable for the mode.
     * - AMOUNT_MATCH / AMOUNT_SMALLEST_DIFF: |sum - target| (scaled).
     * - AMOUNT_SMALLEST_PLUS: only sum >= target is acceptable (smaller positive diff wins);
     *   under-target combos are rejected (null), so when nothing reaches the target the result is null.
     */
    private fun score(sum: BigDecimal, target: BigDecimal, mode: CompleteHandling): Long? {
        val diff = sum.subtract(target)
        return when (mode) {
            CompleteHandling.AMOUNT_MATCH, CompleteHandling.AMOUNT_SMALLEST_DIFF ->
                diff.abs().multiply(SCALE).toLong()
            CompleteHandling.AMOUNT_SMALLEST_PLUS ->
                if (diff.signum() < 0) null else diff.multiply(SCALE).toLong()
            else -> null
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(Optimizer::class.java)
        const val MAX_CANDIDATES = 18
        const val MAX_NODES = 200_000
        val SCALE: BigDecimal = BigDecimal(10_000) // preserve 4 decimals when collapsing to Long
    }
}
