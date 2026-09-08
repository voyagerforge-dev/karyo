package com.karyo.inventory.api.spi

/**
 * In-process cross-module READ contract, like [StockUnitLookup] — NOT a strategy
 * seam. Declared here in inventory-api and implemented by the fulfillment module (the
 * reverse direction of [StockPicker]): inventory asks the question, fulfillment owns the
 * pick lifecycle that answers it.
 *
 * Consumed by inventory's `changeClient` as the second of two independent encumbrance
 * signals (the first is `reservedAmount > 0`). Precision matters here: as implemented
 * today, `confirmPick` consumes the reservation and stamps the Pick terminal in ONE
 * transaction, and short-pick follow-ups create their new Pick in the same transaction
 * that reserves fresh stock — so there is currently no observable moment where a live
 * pick exists without a reservation. This guard is deliberate DEFENSE-IN-DEPTH, not a
 * fix for a live race: changeClient breaches the module's strongest invariant, so its
 * refusal must not depend on a transaction boundary in another module staying atomic
 * under future refactors (async picking, sagas, split commits).
 */
interface OpenPickGuard {
    /** True if any Pick in a non-terminal state references any of [stockUnitIds]. Batched — one query. */
    fun hasOpenPicks(stockUnitIds: Collection<Long>): Boolean
}
