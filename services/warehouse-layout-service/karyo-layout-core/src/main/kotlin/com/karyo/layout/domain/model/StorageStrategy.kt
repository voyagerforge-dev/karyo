package com.karyo.layout.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.*

@Entity
@Table(name = "storage_strategies")
class StorageStrategy : TenantEntity() {
    @Column(nullable = false, length = 100)
    lateinit var name: String

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    var zone: Zone? = null

    @Column(name = "mix_item", nullable = false)
    var mixItem: Boolean = true

    @Column(name = "mix_client", nullable = false)
    var mixClient: Boolean = false

    @Column(name = "near_picking_location", nullable = false)
    var nearPickingLocation: Boolean = false

    @Column(length = 255)
    var sorts: String? = null

    /** myWMS `onlyClientLocation`: true (default, since row 17/defect-burndown-4) restricts
     * putaway candidates to the requesting owner's own locations only; false is an explicit
     * per-strategy opt-in that also allows shared (client_id=0) locations. Shared placement
     * is deliberately opt-in, not the fallback: a shared location is counted by no tenant's
     * full inventory ([com.karyo.layout.spi.LocationLockPort.allStorageLocationIds] contract,
     * strict `clientId` equality), so silently defaulting stock onto one leaves it outside
     * every owner's END_OF_PERIOD count. Enforced in
     * [com.karyo.layout.service.LocationFinderService]. Existing rows keep whatever value
     * they were created with -- this default only governs the strategy-less fallback and
     * newly created rows that omit the field. */
    @Column(name = "only_client_location", nullable = false)
    var onlyClientLocation: Boolean = true

    /** myWMS `manualSearch`: true short-circuits the finder to "no location found"
     * immediately, before any candidate query — putaway falls back to manual
     * placement. Enforced in [com.karyo.layout.service.LocationFinderService]. */
    @Column(name = "manual_search", nullable = false)
    var manualSearch: Boolean = false

    /** myWMS `useAreaStrategyDate` — cross-area FIFO hiding. Column only in this task;
     * finder behavior lands in Task 3 alongside the L1 StorageArea trio. */
    @Column(name = "use_area_strategy_date", nullable = false)
    var useAreaStrategyDate: Boolean = false

    /** myWMS `useItemDataArea` — hide areas whose [com.karyo.layout.domain.model.ItemDataArea]
     * thresholds are already met. Column only in this task; finder behavior lands in Task 3. */
    @Column(name = "use_item_data_area", nullable = false)
    var useItemDataArea: Boolean = false
}
