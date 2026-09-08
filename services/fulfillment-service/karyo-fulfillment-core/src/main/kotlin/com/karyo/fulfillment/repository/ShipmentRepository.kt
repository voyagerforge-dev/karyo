package com.karyo.fulfillment.repository

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ShipmentRepository : PanacheRepository<Shipment> {

    fun findByIdAndClient(id: Long, clientId: Long): Shipment? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    fun findByClient(clientId: Long): List<Shipment> =
        list("clientId = ?1", Sort.by("created").descending(), clientId)

    /**
     * Row 2 fix (defect-tail-2, 2026-08-17): `Sort.by("id").descending()` -- reads the LATEST
     * Shipment row for [deliveryOrderId], not an arbitrary one. Before this fix, `firstResult()`
     * carried no `ORDER BY` at all, so a delivery order with more than one Shipment row
     * (repeated cancel/reopen cycles accumulate one CANCELED row per prior attempt plus the
     * current live one, S4) read whatever row the database happened to return first -- not
     * necessarily the most recent. [PackingService.openPacking]'s duplicate-shipment guard is the
     * one production caller: it must see the CURRENT shipment's state (CANCELED frees the order to
     * pack again; anything else blocks it), which is only ever the latest row, never an older one.
     */
    fun findByDeliveryOrderId(deliveryOrderId: Long, clientId: Long): Shipment? =
        find("deliveryOrderId = ?1 and clientId = ?2", Sort.by("id").descending(), deliveryOrderId, clientId)
            .firstResult()

    /** SC17: the uniqueness check for [PackingService]'s generated `shipmentNumber`. */
    fun findByNumber(shipmentNumber: String, clientId: Long): Shipment? =
        find("shipmentNumber = ?1 and clientId = ?2", shipmentNumber, clientId).firstResult()

    fun findByDeliveryOrderIds(ids: Collection<Long>, clientId: Long): List<Shipment> =
        if (ids.isEmpty()) emptyList() else list("deliveryOrderId in ?1 and clientId = ?2", ids, clientId)

    /** Sprint C: batch id lookup, used to hydrate group shipments joined via `shipment_orders`. */
    fun findByIds(ids: Collection<Long>, clientId: Long): List<Shipment> =
        if (ids.isEmpty()) emptyList() else list("id in ?1 and clientId = ?2", ids, clientId)

    /**
     * Sprint C: the one non-canceled shipment of a consolidation group, if any.
     *
     * `Sort.by("id")` (IMPORTANT 2, final-review fix wave): fulfillment V613 makes a second live
     * row impossible, but this read must still be deterministic on a database that predates that
     * index (a group that raced an open before the migration landed keeps both rows). The oldest
     * one is the right answer -- it is the shipment any already-packed container hangs off.
     */
    fun findOpenByGroupId(groupId: Long, clientId: Long): Shipment? =
        find(
            "consolidationGroupId = ?1 and clientId = ?2 and state <> ?3",
            Sort.by("id"),
            groupId, clientId, ShipmentState.CANCELED.code,
        ).firstResult()

    /**
     * Burndown-6 A8 (row :2085), batched [findOpenByGroupId]: the live shipments of [groupIds],
     * oldest-first so a pre-V613 duplicate resolves to the same row the per-group read picks.
     * Empty input -> empty. One query for a whole wave's groups, never one per group.
     */
    fun findOpenByGroupIds(groupIds: Collection<Long>, clientId: Long): List<Shipment> =
        if (groupIds.isEmpty()) {
            emptyList()
        } else {
            list(
                "consolidationGroupId in ?1 and clientId = ?2 and state <> ?3",
                Sort.by("id"),
                groupIds, clientId, ShipmentState.CANCELED.code,
            )
        }

    /**
     * Row :1470 (A8): purposeful existence check for the `autoOpenPending` derivation. Row 2 fix
     * (defect-tail-2, 2026-08-17): [findByDeliveryOrderId] now carries a deterministic
     * `ORDER BY id DESC`, so the hazard this KDoc used to describe (an unordered `firstResult()`
     * picking an arbitrary row once more than one Shipment exists for a delivery order, S4) no
     * longer applies to it -- this method remains the right tool anyway: a `count` query is exact
     * regardless of row count, and does not need to materialize or order any row at all just to
     * answer a yes/no existence question.
     */
    fun existsNonCanceledByDeliveryOrderId(deliveryOrderId: Long, clientId: Long): Boolean =
        count(
            "deliveryOrderId = ?1 and clientId = ?2 and state != ?3",
            deliveryOrderId, clientId, ShipmentState.CANCELED.code,
        ) > 0
}
