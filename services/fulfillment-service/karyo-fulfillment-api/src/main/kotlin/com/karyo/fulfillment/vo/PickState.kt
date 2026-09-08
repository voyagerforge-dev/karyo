package com.karyo.fulfillment.vo

/**
 * Pick lifecycle (a subset of the order lifecycle codes): CREATED -> RELEASED -> STARTED ->
 * PICKED, plus CANCELED. Forward-only. Picks and PickOrders share these codes.
 */
enum class PickState(val code: Int) {
    CREATED(50),
    RELEASED(100),
    STARTED(500),
    PICKED(600),
    CANCELED(800);

    /** Forward-only; CANCELED reachable from any pre-PICKED state. */
    fun canAdvanceTo(target: PickState): Boolean = when {
        target == this -> false
        target == CANCELED -> code < PICKED.code
        else -> target.code > code
    }

    companion object {
        fun fromCode(code: Int): PickState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown PickState code: $code")
    }
}
