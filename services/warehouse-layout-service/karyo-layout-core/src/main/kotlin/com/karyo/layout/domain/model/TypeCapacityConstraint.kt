package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal

/**
 * myWMS `TypeCapacityConstraint` (locations-layout sprint Task 4) — the (LocationType,
 * UnitLoadType) compatibility/capacity matrix that replaces the finder's permissive filter-7
 * UL-type stub. Pure layout config, `BaseEntity` (like [LocationType]/[StorageArea]) — not
 * tenant-scoped, same as the rest of the layout configuration surface.
 *
 * `unitLoadTypeId` is a FOREIGN MODULE id (inventory) — deliberately **no FK**. Unlike
 * [com.karyo.layout.domain.model.ItemDataArea.itemDataId] (validated via the `ProductLookup`
 * SPI) or [FixAssignment.itemDataId], inventory-api exposes **no** `UnitLoadTypeLookup` SPI
 * today — only `StockUnitLookup`/`UnitLoadLookup`. Per the sprint's constraint against
 * inventing a new cross-module SPI just for this one validation, the id is accepted
 * unvalidated: advisory integrity only, consistent with the project's "no cross-module FK"
 * convention (a foreign id can go stale/be mistyped and this table will not notice).
 *
 * `allocation`: percentage a unit load of [unitLoadTypeId] occupies at a location of
 * [locationType] — 100 = one fits, 50 = two fit, >100 = myWMS's oversize/multi-position
 * case (NOT enforced by placement logic here; see `LocationFinderService`'s KDoc).
 * `orderIndex`: consumed by the L6 sorts task (CAPACITY sort key) — not read by this task's
 * finder filter itself.
 */
@Entity
@Table(
    name = "type_capacity_constraints",
    uniqueConstraints = [UniqueConstraint(columnNames = ["location_type_id", "unit_load_type_id"])],
)
class TypeCapacityConstraint : BaseEntity() {
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "location_type_id", nullable = false)
    lateinit var locationType: LocationType

    @Column(name = "unit_load_type_id", nullable = false)
    var unitLoadTypeId: Long = 0

    @Column(nullable = false, precision = 5, scale = 2)
    var allocation: BigDecimal = BigDecimal("100")

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
}
