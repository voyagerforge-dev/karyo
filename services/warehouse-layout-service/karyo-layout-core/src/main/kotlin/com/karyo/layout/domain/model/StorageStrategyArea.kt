package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

/**
 * myWMS `StorageStrategyArea` — (strategy, storageArea, orderIndex) join. A strategy's
 * areas are an ORDERED list; [StorageStrategyService.setAreas] rewrites the whole list on
 * every PUT, assigning `orderIndex` 1..N (myWMS `saveForStorageStrategy` shape).
 */
@Entity
@Table(
    name = "storage_strategy_areas",
    uniqueConstraints = [UniqueConstraint(columnNames = ["storage_strategy_id", "storage_area_id"])],
)
class StorageStrategyArea : BaseEntity() {
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_strategy_id", nullable = false)
    lateinit var storageStrategy: StorageStrategy

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_area_id", nullable = false)
    lateinit var storageArea: StorageArea

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
}
