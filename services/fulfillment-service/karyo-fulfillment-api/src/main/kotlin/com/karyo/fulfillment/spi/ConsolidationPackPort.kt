package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Bulk Allocation Sprint C: the fulfillment-side write seam for a CONSOLIDATION-GROUP
 * (cross-order) shipment -- one `Shipment` spanning every member delivery order of one
 * consolidation group, its containers, and the stock moves that fill them.
 *
 * **Allocation is NOT this port's job.** Deciding WHICH sorted units go into WHICH container is
 * wave-core's (`com.karyo.wave.service.PackoutService`, Task 4); this contract only executes
 * what it is handed, as [PackLineAllocation] slices. Same contract-direction trick as
 * [PickZoneLookup]/[BatchPickPort]: the FOSS module declares the shape, the paid module drives it.
 *
 * **Per-slice, never per-line.** One order line's picks can sit on two carts at once (the
 * multi-zone doctrine, Sprint A) so every quantity crossing this seam is carried per pick slice
 * ([PackLineAllocation.sourcePickId] + [PackLineAllocation.cartStockUnitId]), never aggregated
 * by delivery-order line.
 *
 * **Explicit `clientId` on every method, but NOT on everything it delegates to.** This port's own
 * reads and writes take `clientId` as a parameter and never touch the ambient `TenantContext`.
 * Three inventory mutations it calls through still resolve the tenant ambiently, though:
 * `StockMover.transferToUnitLoad` (from `addLines`) and `StockPicker.packContainer` /
 * `StockPicker.unpackContainer` (from `closeContainer` / `reopenContainer`). That makes the port
 * REST-DRIVEN ONLY today -- a `@Scheduled` or otherwise unprimed caller would hit the B13 failure
 * mode where the ambient context resolves to OWNER(0), which permits no real tenant. The stock
 * flips are guarded against doing that silently (a zero-row flip is refused, not ignored), but the
 * guard is a tripwire, not a fix: giving those three seams explicit-`clientId` overloads is the
 * prerequisite for ever driving this port from a scheduler.
 */
interface ConsolidationPackPort {
    /** The one live (non-CANCELED) group shipment of [consolidationGroupId], or null. */
    fun findOpenGroupShipment(consolidationGroupId: Long, clientId: Long): GroupShipmentView?

    /**
     * Burndown-6 A8: batched, ID-ONLY companion to [findOpenGroupShipment] for a read that spans a
     * whole wave's groups (`PackoutService.detailAdditions`, once per wave-detail read instead of
     * once per group). Consolidation group id -> live shipment id; empty input -> empty map; at
     * most one entry per group -- the oldest live row, the same tie-break the per-group read uses
     * on a pre-V613 database.
     *
     * Ids, not [GroupShipmentView]s, deliberately: hydrating a view costs a member-row read, a
     * container read and a line read PER SHIPMENT, and the only caller needs the id. The per-group
     * form stays for the mutating paths, which resolve exactly one group and do need the view.
     */
    fun findOpenGroupShipmentIds(consolidationGroupIds: Collection<Long>, clientId: Long): Map<Long, Long>

    /**
     * Opens (or returns, idempotently) the group shipment for
     * [OpenGroupShipmentRequest.consolidationGroupId]: PACKING(640), member rows in
     * `shipment_orders`, every member order advanced to PACKING.
     */
    fun openGroupShipment(request: OpenGroupShipmentRequest): GroupShipmentView

    /**
     * Opens an empty container on [shipmentId]. [OpenContainerRequest.unitLoadId] adopts a
     * scanned LPN (must exist, be empty, not already be a live shipment's container, and not be
     * a pick cart); null mints a fresh pick-bin unit load at the staging location instead.
     */
    fun openContainer(shipmentId: Long, request: OpenContainerRequest, clientId: Long): ContainerView

    /** Moves each slice's quantity off its cart stock unit onto the container and records a line. */
    fun addLines(shipmentId: Long, containerId: Long, lines: List<PackLineAllocation>, clientId: Long): ContainerView

    /** Closes a non-empty container at [weight] (> 0): its stock flips PICKED(600) -> PACKED(650). */
    fun closeContainer(shipmentId: Long, containerId: Long, weight: BigDecimal, clientId: Long): ContainerView

    /** Reverses [closeContainer] while the shipment is still pre-manifest: PACKED -> PICKED, OPEN again. */
    fun reopenContainer(shipmentId: Long, containerId: Long, clientId: Long): ContainerView

    /**
     * Flips the shipment to PACKED and every member order to PACKED (plus SHIPPING when
     * [sendToShipping]). Refused while any container is still open, or when there are none.
     * The "everything sorted has been packed" check belongs to the CALLER: this port only knows
     * containers, not what the put wall expected.
     */
    fun completeGroupShipment(shipmentId: Long, clientId: Long, sendToShipping: Boolean): GroupShipmentView

    /**
     * Packed amount per delivery-order line over LIVE GROUP shipments (the put wall's universe);
     * a per-order PACKOUT box on the same line is excluded by design (burndown-6 A6). A HYBRID
     * member packs its COMPLETE-picked lines through the ordinary per-order path while its
     * batch-picked remainder crosses the wall, and counting that box here would make the group's
     * `packedAmount` exceed what the wall ever sorted. Empty input -> empty.
     */
    fun packedByLines(lineIds: Collection<Long>, clientId: Long): Map<Long, BigDecimal>

    /** Packed amount per source pick on [shipmentId] -- the per-slice consumption ledger. */
    fun packedByPicks(shipmentId: Long, clientId: Long): Map<Long, BigDecimal>
}

data class OpenGroupShipmentRequest(
    val clientId: Long,
    val waveId: Long,
    val waveNumber: String,
    val consolidationGroupId: Long,
    val sortSlot: String,
    val members: List<MemberOrderRef>,
)

data class MemberOrderRef(val orderId: Long, val orderNumber: String)

data class OpenContainerRequest(
    /** A scanned, empty LPN to adopt; null mints a fresh container unit load. */
    val unitLoadId: Long?,
    val type: String,
    val stagingLocationId: Long,
    val stagingLocationName: String,
)

/** One pick slice's contribution to one container. Two slices of one line are never merged. */
data class PackLineAllocation(
    val deliveryOrderId: Long,
    val deliveryOrderLineId: Long,
    val sourcePickId: Long,
    val cartStockUnitId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val amount: BigDecimal,
)

data class ContainerLineView(
    val id: Long,
    val deliveryOrderId: Long?,
    val deliveryOrderLineId: Long?,
    val itemDataNumber: String,
    val lotNumber: String?,
    val amount: BigDecimal,
)

data class ContainerView(
    val id: Long,
    val shippingUnitNumber: String,
    val unitLoadId: Long,
    val state: Int,
    val type: String,
    val weight: BigDecimal,
    val lines: List<ContainerLineView>,
)

data class GroupShipmentView(
    val id: Long,
    val shipmentNumber: String,
    val state: Int,
    val consolidationGroupId: Long,
    val waveId: Long,
    val memberOrderIds: List<Long>,
    val containers: List<ContainerView>,
)
