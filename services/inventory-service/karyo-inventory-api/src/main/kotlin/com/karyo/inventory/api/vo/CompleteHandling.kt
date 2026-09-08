package com.karyo.inventory.api.vo

/**
 * Complete-handling modes for stock selection, mirroring myWMS `OrderStrategyCompleteHandling`.
 * Drives whether selection must satisfy demand from *complete unit loads* and how it optimizes
 * the choice.
 *
 * Stored/transported as the Int [code] on [StockSelectionRequest.completeHandling] — the same
 * enum-with-code pattern as [PickingType]/StockState (the wire/DB carries the code; the algorithm
 * resolves it via [fromCode]).
 */
enum class CompleteHandling(val code: Int) {
    /** No complete-only restriction; selection falls through to preference/partial passes. */
    NONE(0),

    /** First single complete UL whose available amount EXACTLY equals the demand (FIFO order). */
    AMOUNT_FIRST_MATCH(1),

    /** First single complete UL whose available amount is >= the demand (FIFO order). */
    AMOUNT_FIRST_PLUS(2),

    /** Combination of complete ULs whose total EXACTLY equals the demand (combinatorial). */
    AMOUNT_MATCH(3),

    /** Combination of complete ULs with the smallest absolute difference from the demand. */
    AMOUNT_SMALLEST_DIFF(4),

    /** Combination of complete ULs with the smallest *positive* difference (>= demand preferred). */
    AMOUNT_SMALLEST_PLUS(5);

    /** True when this mode requires complete-only handling (any value other than NONE). */
    val isCompleteOnly: Boolean get() = this != NONE

    companion object {
        fun fromCode(code: Int): CompleteHandling =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown CompleteHandling mode: $code")
    }
}
