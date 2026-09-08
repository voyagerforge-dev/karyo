package com.karyo.fulfillment.service

import java.math.BigDecimal

data class BulkSlice(val pickId: Long, val planned: BigDecimal)
data class BulkSliceShare(val pickId: Long, val amount: BigDecimal)

/**
 * Bulk-confirm fan-out (Bulk Allocation Sprint B, spec ruling 12). [slices] are the open picks of
 * one source stock unit on a bulk PickOrder; they are walked in pick id ASC, which is allocation
 * order (prio DESC, created ASC, line ASC) because `WavePickService` persists them in that order.
 * The head is filled, the tail is short: a slice may receive ZERO, which the caller turns into
 * a zero short-confirm. Pure function, no I/O.
 */
object BulkFanOut {
    fun allocate(slices: List<BulkSlice>, picked: BigDecimal): List<BulkSliceShare> {
        require(slices.isNotEmpty()) { "no open slices" }
        require(picked.signum() > 0) { "picked must be positive" }
        val total = slices.fold(BigDecimal.ZERO) { acc, s -> acc + s.planned }
        require(picked <= total) { "picked $picked exceeds open planned $total" }
        var remaining = picked
        return slices.sortedBy { it.pickId }.map { slice ->
            val share = remaining.min(slice.planned)
            remaining -= share
            BulkSliceShare(slice.pickId, share)
        }
    }
}
