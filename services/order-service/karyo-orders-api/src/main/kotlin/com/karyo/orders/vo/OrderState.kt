package com.karyo.orders.vo

/**
 * Full 18-value order state machine -- behavioral parity with myWMS `OrderState`, with the exact
 * legacy numeric codes. The codes are shared across order-like entity types
 * (DeliveryOrder, lines, and — from v1.2.3 — TransportOrder, ASN, GoodsReceipt).
 *
 * Transition rules (enforced via [canAdvanceTo]):
 *  - **Forward-only:** a transition is allowed when the target code is strictly
 *    greater than the current code (states only advance).
 *  - **CANCELED is gated, not numeric:** although CANCELED(800) is numerically
 *    forward from most states, cancellation is only allowed from *pre-picking*
 *    states (code < PICKED(600)). Once goods have been picked, the order must run
 *    its course (or FAIL) — it can no longer be canceled.
 *  - **PENDING retry hop:** PENDING(550) → PROCESSABLE(300) / RESERVED(400) is the
 *    one sanctioned "backward" transition. PENDING marks a reservation shortfall;
 *    when stock becomes available a retry re-runs reservation and hops the line
 *    back into the normal forward flow. This mirrors the myWMS "PENDING dance".
 *
 * v1.2 wires only CREATED→RELEASED→PROCESSABLE(→RESERVED) plus CANCELED and the
 * PENDING retry hop; the remaining states exist so codes never churn later.
 */
enum class OrderState(val code: Int) {
    UNDEFINED(0),
    CREATED(50),
    RELEASED(100),
    PAUSE(200),
    PROCESSABLE(300),
    RESERVED(400),
    STARTED(500),
    PENDING(550),
    PICKED(600),
    PACKING(640),
    PACKED(650),
    SHIPPING(670),
    SHIPPED(680),
    FINISHED(700),
    FAILED(710),
    CANCELED(800),
    POSTPROCESSED(900),
    DELETABLE(1000);

    /**
     * Returns true when this state may legally transition to [target].
     * See the class KDoc for the exact rules (forward-only, gated CANCELED,
     * sanctioned PENDING retry hop).
     */
    fun canAdvanceTo(target: OrderState): Boolean = when {
        target == this -> false
        target == CANCELED -> code < PICKED.code
        this == PENDING && (target == PROCESSABLE || target == RESERVED) -> true
        else -> target.code > code
    }

    companion object {
        fun fromCode(code: Int): OrderState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown OrderState code: $code")
    }
}
