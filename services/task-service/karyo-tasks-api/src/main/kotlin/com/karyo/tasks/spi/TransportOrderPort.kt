package com.karyo.tasks.spi

import java.math.BigDecimal

/**
 * Inbound seam: lets another module mint a REPLENISH transport order through the tasks
 * state-change chokepoint, without depending on tasks-core.
 *
 * Implemented by [com.karyo.tasks.service.DefaultTransportOrderPort] in tasks-core;
 * consumed by the replenishment module (and future modules) via the api contract only.
 */
interface TransportOrderPort {
    fun createReplenishment(command: ReplenishmentTaskCommand): TransportOrderRef
    fun hasOpenReplenishment(fixAssignmentId: Long, clientId: Long): Boolean

    /** R12b (Task 6): mints an area-level (Mode 2) REPLENISH transport order. */
    fun createAreaReplenishment(command: AreaReplenishmentTaskCommand): TransportOrderRef

    /** R12b (Task 6): the per-area open-order dedupe guard — mirrors [hasOpenReplenishment],
     *  keyed by `itemDataAreaId` instead of `fixAssignmentId`. */
    fun hasOpenAreaReplenishment(itemDataAreaId: Long, clientId: Long): Boolean

    /**
     * Row 3 (defect-burndown-4, Task 5): source unit-load ids claimed by any OPEN (non-terminal)
     * REPLENISH transport order for [clientId] -- both Mode 1 (fix-face) and Mode 2 (area)
     * orders. Consumed by [com.karyo.replenishment.service.ReplenishmentService.scan] to seed
     * the scan pass's in-flight claimed-source set, so a source already committed to a
     * still-open order from an earlier pass can never be re-selected either.
     */
    fun openReplenishmentUnitLoadIds(clientId: Long): Set<Long>

    /**
     * Cross-docking sprint: mints a CROSS_DOCK transport order (RELEASED immediately) from the
     * receiving dock to a staging location chosen by the (paid) cross-docking engine. Consumed
     * by `karyo-crossdock-core` only: tasks-core never depends on that module, it just answers
     * this port method.
     */
    fun createCrossDock(command: CrossDockTaskCommand): TransportOrderRef

    /**
     * Cross-docking sprint: expiry/cancel fallback. Mints a normal PUTAWAY transport order for
     * a unit load sitting on a cross-dock staging location, resolved by the ordinary
     * [com.karyo.layout.spi.LocationFinder] exactly like the auto-putaway observer path.
     * Reachable from a `@Scheduled` sweep (the cross-dock expiry sweep), so [command] carries an
     * explicit `clientId` rather than relying on ambient `TenantContext`, same doctrine as
     * [createReplenishment].
     */
    fun createPutawayFromStaging(command: PutawayFromStagingCommand): TransportOrderRef

    /**
     * Cross-docking sprint (final fix wave): cancels the CROSS_DOCK transport order
     * [transportOrderId] IF it is still open (pre-STARTED, non-terminal) -- the guard is the
     * SAME one the ordinary operator-facing cancel path already applies, just non-throwing.
     * Called by the cross-dock expiry handler's AUTO_PUTAWAY action and by
     * `CrossDockLifecycleService.cancel` BEFORE either mints its own fallback PUTAWAY transport,
     * so a unit load never ends up with two competing floor instructions (a stale open CROSS_DOCK
     * move plus a brand new PUTAWAY move) for the same unit load. Returns `false` (and logs a
     * warning, leaving the transport order untouched) when it is already STARTED or terminal, or
     * when [transportOrderId] does not resolve for [clientId] at all -- never throws, since
     * neither caller can afford to abort its own transaction over a transport order that may
     * simply have moved on. Tenant-scoped.
     */
    fun cancelIfOpen(transportOrderId: Long, clientId: Long): Boolean
}

/** Command to mint a REPLENISH transport order for a fixed-location assignment. */
data class ReplenishmentTaskCommand(
    val clientId: Long,
    val unitLoadId: Long,
    val destinationLocationId: Long,
    val destinationLocationName: String,
    val fixAssignmentId: Long,
    /**
     * R13 (replenishment sprint, Task 2) — Karyo-original fill-to-max top-up quantity.
     * NOT a legacy myWMS concept: the behavioral corpus is explicit that legacy replenishment
     * never computed a quantity and never read `FixAssignment.maxAmount` — it always moved the
     * whole reserve unit-load. `null` (the default) preserves that whole-UL parity behavior.
     * A non-null value is the deficit-to-`maxAmount` the scan computed for this fix face,
     * capped at the source unit-load's own amount — see `ReplenishmentService.scan`'s KDoc for
     * the exact computation.
     */
    val amount: BigDecimal? = null,
)

/**
 * R12b (replenishment sprint Task 6): command to mint an area-level (Mode 2) REPLENISH
 * transport order for an [com.karyo.layout.domain.model.ItemDataArea] deficiency. Sibling to
 * [ReplenishmentTaskCommand] (the Mode 1 / fix-face command) — kept as a SEPARATE data class
 * rather than widening the fix-face one with nullable fields, because the two commands carry
 * disjoint identity ([ReplenishmentTaskCommand.fixAssignmentId] vs [itemDataAreaId]) and the
 * destination is resolved differently upstream (the fix assignment's own location vs. a
 * location chosen within the area's cluster set by
 * [com.karyo.replenishment.service.ReplenishmentService.scanAreas]).
 *
 * [itemDataId]/[itemDataNumber] are carried for audit/logging at the call site only —
 * `TaskService.createAreaReplenishment` still denormalizes [com.karyo.tasks.domain.model.
 * TransportOrder.itemDataId]/`itemDataNumber` from the unit load's own live stock at creation
 * time (`ConfirmVariantService.denormalizeAtCreation`, same as [ReplenishmentTaskCommand]),
 * since that is the one place both source-selection strictness (non-mixed UL) and the actually-
 * moved stock's identity are already guaranteed to agree.
 *
 * [amount] defaults `null` (whole-UL move) - R12b v1 does not compute a top-up quantity for
 * area orders. See `docs/functional/replenishment.md#4-area-level-replenishment`; unlike Mode 1,
 * area mode has no partial-transfer request.
 */
data class AreaReplenishmentTaskCommand(
    val clientId: Long,
    val itemDataId: Long,
    val itemDataNumber: String?,
    val itemDataAreaId: Long,
    val unitLoadId: Long,
    val destinationLocationId: Long,
    val destinationLocationName: String,
    val amount: BigDecimal? = null,
)

/** Lightweight reference returned after creating a transport order — avoids leaking tasks-core DTOs. */
data class TransportOrderRef(
    val id: Long,
    val orderNumber: String,
    val state: Int,
)

/** Command to mint a CROSS_DOCK transport order from the dock to a staging location. */
data class CrossDockTaskCommand(
    val clientId: Long,
    val unitLoadId: Long,
    val destinationLocationId: Long,
    val destinationLocationName: String,
    val goodsReceiptLineId: Long,
    val note: String? = null,
)

/** Command to mint a fallback PUTAWAY transport order for a unit load left on staging. */
data class PutawayFromStagingCommand(
    val clientId: Long,
    val unitLoadId: Long,
    val note: String? = null,
)
