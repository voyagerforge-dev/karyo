package com.karyo.fulfillment.repository

import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import java.math.BigDecimal

/**
 * Owns both [ShippingUnit] aggregates and their [ShippingUnitLine] children. Lines are a
 * dependent collection of a shipping unit, so they are persisted/queried here via the
 * [EntityManager] rather than through a separate Panache repository.
 */
@ApplicationScoped
class ShippingUnitRepository(
    private val em: EntityManager,
) : PanacheRepository<ShippingUnit> {

    fun findByShipmentId(shipmentId: Long): List<ShippingUnit> =
        list("shipmentId = ?1 order by id asc", shipmentId)

    /**
     * Row 10 (stock-and-orders sprint): batched `shipmentId -> first (lowest id) shipping unit
     * id` for [shipmentIds] -- backs [com.karyo.fulfillment.spi.ShipmentLookup.findByDeliveryOrderIds]
     * folding `ShipmentSummary.shippingUnitId` into that SAME batched call (never a per-row extra
     * query). A shipment with no shipping unit yet is simply absent -- honest gap, mirrors
     * [findByUnitLoadIdOnLiveShipment]'s absent-means-none convention.
     */
    @Suppress("UNCHECKED_CAST")
    fun findFirstIdsByShipmentIds(shipmentIds: Collection<Long>, clientId: Long): Map<Long, Long> {
        if (shipmentIds.isEmpty()) return emptyMap()
        return (
            em.createQuery(
                "select su.shipmentId, min(su.id) from ShippingUnit su " +
                    "where su.shipmentId in :ids and su.clientId = :clientId group by su.shipmentId",
            )
                .setParameter("ids", shipmentIds)
                .setParameter("clientId", clientId)
                .resultList as List<Array<Any>>
            )
            .associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
    }

    fun findByIdAndClient(id: Long, clientId: Long): ShippingUnit? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    fun persistLine(line: ShippingUnitLine) {
        em.persist(line)
    }

    fun findLinesByUnitId(shippingUnitId: Long): List<ShippingUnitLine> =
        em.createQuery(
            "select l from ShippingUnitLine l where l.shippingUnitId = :unitId order by l.id asc",
            ShippingUnitLine::class.java,
        ).setParameter("unitId", shippingUnitId).resultList

    fun findLineByIdAndClient(id: Long, clientId: Long): ShippingUnitLine? =
        em.createQuery(
            "select l from ShippingUnitLine l where l.id = :id and l.clientId = :clientId",
            ShippingUnitLine::class.java,
        ).setParameter("id", id).setParameter("clientId", clientId).resultList.firstOrNull()

    /**
     * Task 6 (row :1354, adjudication A3): the per-call packing consumption ledger. One
     * GROUP BY query over every [ShippingUnitLine] belonging to any [ShippingUnit] of
     * [shipmentId], summed per [ShippingUnitLine.sourcePickId]. Ad-hoc lines (null
     * `sourcePickId` -- [ShippingUnit.ORIGIN_AD_HOC]) are excluded: they never consume a
     * `Pick`. [shipmentId] alone is a safe scope here -- every caller resolves the shipment
     * via a clientId-scoped lookup (e.g. [com.karyo.fulfillment.service.PackingService.pack]'s
     * `findByIdAndClient`) before this runs, so nothing outside that already-authorized
     * shipment's own units is ever summed.
     *
     * [com.karyo.fulfillment.service.ShippingLifecycleService.removeUnit] and `.removeLine`
     * hard-delete lines (directly, or via `deleteUnit`'s cascade), so a removed/regressed
     * unit's amounts drop out of this sum on the very next call -- the ledger self-heals with
     * zero extra bookkeeping. See
     * [com.karyo.fulfillment.service.PackingService.packSiblingContainers]'s KDoc for how the
     * result is turned into a remaining-work selection.
     */
    fun consumedAmountsByPick(shipmentId: Long): Map<Long, BigDecimal> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createQuery(
            "select l.sourcePickId, sum(l.amount) from ShippingUnitLine l, ShippingUnit su " +
                "where l.shippingUnitId = su.id and su.shipmentId = :shipmentId " +
                "and l.sourcePickId is not null group by l.sourcePickId",
        ).setParameter("shipmentId", shipmentId).resultList as List<Array<Any>>
        return rows.associate { (it[0] as Number).toLong() to it[1] as BigDecimal }
    }

    /**
     * Task 6/7: is [unitLoadId] currently sitting on a shipment that hasn't been canceled? Task
     * 7's ad-hoc-unit refusal ("UL already on a live shipment") reads this; a CANCELED shipment's
     * units are excluded because [com.karyo.fulfillment.service.ShippingLifecycleService.cancel]
     * has already restored their stock -- the unit load is free again.
     */
    fun findByUnitLoadIdOnLiveShipment(unitLoadId: Long, clientId: Long): ShippingUnit? =
        em.createQuery(
            "select su from ShippingUnit su, Shipment s where su.unitLoadId = :unitLoadId " +
                "and su.shipmentId = s.id and su.clientId = :clientId and s.state != :canceled",
            ShippingUnit::class.java,
        ).setParameter("unitLoadId", unitLoadId)
            .setParameter("clientId", clientId)
            .setParameter("canceled", ShipmentState.CANCELED.code)
            .resultList.firstOrNull()

    /** Deletes [unit] and its child lines (S4: `removeUnit`). */
    fun deleteUnit(unit: ShippingUnit) {
        deleteLinesByUnitId(unit.id!!)
        delete(unit)
    }

    fun deleteLinesByUnitId(shippingUnitId: Long) {
        em.createQuery("delete from ShippingUnitLine l where l.shippingUnitId = :unitId")
            .setParameter("unitId", shippingUnitId).executeUpdate()
    }

    /** Deletes a single line (S4: `removeLine`) -- no cascading effect on its sibling lines/unit. */
    fun deleteLine(line: ShippingUnitLine) {
        em.remove(line)
    }

    /**
     * Row 18, fulfillment half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedUnitLoadIds]:
     * distinct [ShippingUnit.unitLoadId] among [unitLoadIds] referenced by ANY shipping unit for
     * [clientId] -- unlike [findByUnitLoadIdOnLiveShipment]'s live-shipment-only reading, this
     * reference blocks unconditionally: see [com.karyo.inventory.api.spi.PurgeBlockerLookup]'s
     * KDoc for why a shipping record's container reference is never treated as merely
     * historical. Batched over the whole candidate set. Callers must not pass an empty
     * collection.
     */
    fun findReferencedUnitLoadIds(unitLoadIds: Collection<Long>, clientId: Long): Set<Long> =
        em.createQuery(
            "select distinct su.unitLoadId from ShippingUnit su " +
                "where su.unitLoadId in :ids and su.clientId = :clientId",
            Long::class.java,
        )
            .setParameter("ids", unitLoadIds)
            .setParameter("clientId", clientId)
            .resultList
            .toSet()

    /**
     * Sprint C: packed amounts per order line over LIVE GROUP shipments (the put wall's universe);
     * a per-order PACKOUT box on the same line is excluded by design (burndown-6 A6, row :2075).
     * A HYBRID member packs its COMPLETE-picked lines through the ordinary per-order path while
     * its batch-picked remainder crosses the wall -- summing that box in here made the group's
     * `packedAmount` disagree with both the put wall's own `packed` (per-slice) and its `sorted`
     * (batch picks only). Empty input -> empty.
     */
    fun sumAmountByOrderLineIds(lineIds: Collection<Long>, clientId: Long): Map<Long, BigDecimal> =
        if (lineIds.isEmpty()) {
            emptyMap()
        } else {
            em.createQuery(
                "select l.deliveryOrderLineId, sum(l.amount) from ShippingUnitLine l, ShippingUnit u, Shipment s " +
                    "where l.shippingUnitId = u.id and u.shipmentId = s.id and l.deliveryOrderLineId in :ids " +
                    "and l.clientId = :clientId and s.state <> :canceled and s.consolidationGroupId is not null " +
                    "group by l.deliveryOrderLineId",
                Array<Any>::class.java,
            ).setParameter("ids", lineIds).setParameter("clientId", clientId)
                .setParameter("canceled", ShipmentState.CANCELED.code)
                .resultList.associate { (it[0] as Long) to (it[1] as BigDecimal) }
        }

    /** Sprint constraint: explicit `clientId` on every new repository method. */
    fun findLinesByUnitIds(unitIds: Collection<Long>, clientId: Long): List<ShippingUnitLine> =
        if (unitIds.isEmpty()) {
            emptyList()
        } else {
            em.createQuery(
                "select l from ShippingUnitLine l where l.shippingUnitId in :ids and l.clientId = :clientId order by l.id asc",
                ShippingUnitLine::class.java,
            ).setParameter("ids", unitIds).setParameter("clientId", clientId).resultList
        }
}
