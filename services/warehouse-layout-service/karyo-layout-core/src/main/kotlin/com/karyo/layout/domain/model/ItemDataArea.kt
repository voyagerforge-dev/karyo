package com.karyo.layout.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.*
import java.math.BigDecimal

/**
 * myWMS `ItemDataArea` — per-product planned occupancy threshold for a [StorageArea]
 * (`useItemDataArea` strategy flag). Product-scoped config, like [FixAssignment] — carries
 * `client_id` and follows the same tenant-scoping idiom (post-fetch check, 404 not 403).
 *
 * `itemDataId` is a FOREIGN MODULE id (product) — deliberately no FK; validated at the
 * service layer via the `ProductLookup` SPI.
 */
@Entity
@Table(
    name = "item_data_areas",
    uniqueConstraints = [UniqueConstraint(columnNames = ["item_data_id", "storage_area_id"])],
)
class ItemDataArea : TenantEntity() {
    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_area_id", nullable = false)
    lateinit var storageArea: StorageArea

    @Column(name = "planned_amount", precision = 17, scale = 4)
    var plannedAmount: BigDecimal? = null

    @Column(name = "planned_stocks")
    var plannedStocks: Int? = null
}
