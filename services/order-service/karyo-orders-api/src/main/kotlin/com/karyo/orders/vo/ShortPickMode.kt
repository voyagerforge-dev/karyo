package com.karyo.orders.vo

/**
 * Governs the cover-attempt order when a pick comes up short (consumed by fulfillment's confirmPick).
 * The *terminal* behaviour for an uncovered remainder is a separate pluggable ShortfallStrategy
 * (fulfillment) — v1.3 partial-ships; PENDING-escalation/auto-recovery are future strategies.
 */
enum class ShortPickMode {
    /** Re-select remaining same-item stock for the shortfall; create follow-up picks. */
    FOLLOW_UP,
    /** Follow-up first, then 1:1 substitution for any still-uncovered remainder (the default). */
    FOLLOW_UP_THEN_SUBSTITUTE,
    /** Skip follow-up; cover the shortfall only via 1:1 substitution. */
    SUBSTITUTE_ONLY,
    /** No cover attempt — the whole shortfall goes straight to the ShortfallStrategy. */
    NONE;

    companion object {
        val DEFAULT = FOLLOW_UP_THEN_SUBSTITUTE
    }
}
