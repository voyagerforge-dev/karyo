package com.karyo.fulfillment.service

import com.karyo.fulfillment.repository.PickRepository
import com.karyo.inventory.api.spi.OpenPickGuard
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process implementation of [OpenPickGuard] over the fulfillment `picks` table,
 * placed here like [DefaultShipmentLookup] (fulfillment-core implementing a foreign api
 * module's contract).
 *
 * Terminal states: **PICKED(600) and CANCELED(800)** — the only two states the forward-only
 * pick lifecycle cannot leave ([com.karyo.fulfillment.vo.PickState.canAdvanceTo] forbids leaving PICKED, even to
 * CANCELED, and nothing is reachable from CANCELED). Everything else — CREATED(50),
 * RELEASED(100), STARTED(500) — counts as OPEN. The set is enumerated as terminal-states
 * (`state not in`), not open-states (`state in`), so any state ever added to the lifecycle
 * is automatically OPEN until proven terminal — an ambiguous state must block
 * reassignment, never allow it. (Note: today `confirmPick` consumes the reservation and
 * stamps PICKED atomically, so this guard is defense-in-depth against future changes to
 * that transaction boundary rather than a fix for a currently-observable race — see the
 * interface KDoc.)
 *
 * Deliberately NOT tenant-scoped: stock unit ids are globally unique, a Pick's
 * `sourceStockUnitId` always references a stock unit of its own client, and changeClient
 * runs as an OPS principal whose tenant context need not match the pick's (old) owner —
 * scoping by [com.karyo.security.TenantContext] here could silently miss the very pick the
 * guard exists to find.
 */
@ApplicationScoped
class DefaultOpenPickGuard(
    private val pickRepository: PickRepository,
) : OpenPickGuard {

    override fun hasOpenPicks(stockUnitIds: Collection<Long>): Boolean {
        if (stockUnitIds.isEmpty()) return false
        return pickRepository.countOpenBySourceStockUnitIds(stockUnitIds) > 0
    }
}
