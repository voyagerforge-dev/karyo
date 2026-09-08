package com.karyo.inventory.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.inventory.api.vo.StockState
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "unit_loads")
class UnitLoad : TenantEntity() {
    @Column(name = "label_id", nullable = false, unique = true, length = 255)
    lateinit var labelId: String

    @Column(name = "external_id", length = 255)
    var externalId: String? = null

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_load_type_id")
    lateinit var unitLoadType: UnitLoadType

    @Column(name = "storage_location_id", nullable = false)
    var storageLocationId: Long = 0

    @Column(name = "storage_location_name", nullable = false, length = 100)
    lateinit var storageLocationName: String

    @Column(nullable = false)
    var state: Int = StockState.UNDEFINED.code

    @Column(nullable = false)
    var opened: Boolean = false

    @Column(name = "is_carrier", nullable = false)
    var isCarrier: Boolean = false

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "carrier_unit_load_id")
    var carrierUnitLoad: UnitLoad? = null

    /**
     * Row 16. The EFFECTIVE weight: [weightMeasure] when an operator has set one, otherwise
     * [weightCalculated]. Maintained by [com.karyo.inventory.service.UnitLoadWeightCalculator]
     * at every mutation point that can change what is on this load. Stays a stored column
     * rather than becoming `@Transient` -- `StockUnitRepository.
     * findOnStockUnitLoadWeightByLocationIds` reads it via JPQL, which a transient field cannot
     * back.
     *
     * Widened to precision 19 by row :1443 (defect-burndown-5, `V111`): both of its sources
     * ([weightCalculated], [weightMeasure]) are precision 19, so a narrower target here was an
     * overflow waiting to happen.
     */
    @Column(precision = 19, scale = 3)
    var weight: BigDecimal? = null

    /**
     * Row 16. Recomputed value: the type's tare plus the weight of every non-gone stock unit on
     * this unit load. Recomputation is always full, never incremental: myWMS maintained this
     * incrementally with a full-recalculation fallback when the increment produced a nonsense
     * value, which is a cache-coherence design with a repair hatch. A full recompute at each of
     * the few mutation points costs one batched product-measures read and cannot drift.
     */
    @Column(name = "weight_calculated", precision = 19, scale = 3)
    var weightCalculated: BigDecimal? = null

    /**
     * Row 16. Manual override, for instance a scale reading, which beats the calculation
     * whenever it is set. This is a Karyo design decision, specified in
     * `docs/functional/inventory-operations.md#3-unit-load-weight`, rather than a parity claim.
     */
    @Column(name = "weight_measure", precision = 19, scale = 3)
    var weightMeasure: BigDecimal? = null

    @Column(name = "position_index", nullable = false)
    var index: Int = 0

    @OneToMany(mappedBy = "unitLoad", fetch = FetchType.LAZY)
    var stockUnits: MutableList<StockUnit> = mutableListOf()

    /**
     * A2-3 pallet-level lock — codes from `com.karyo.inventory.api.vo.LockType`,
     * NEVER the layout-service `LockType` (the two enums disagree past code 0/1/7;
     * see that enum's KDoc). D3/F1 (2026-07-25, myWMS-faithful — `PickingStockFinder`
     * filters `unitLoad.lock=0`): enforcement is now a READ-TIME INVARIANT —
     * `StockUnitRepository.findForSelection` joins `unitLoad.lockType = 0` directly, so a
     * locked pallet is unpickable even for stock that lands on it AFTER lock time (late
     * receive, transfer, carrier nesting), whose own `lockType` is still 0. The
     * stock-unit-level lock cascade in [UnitLoadService.lock]/[UnitLoadService.unlock]
     * remains — it drives per-stock `lockType` for journaling and per-stock visibility —
     * but it is no longer the sole enforcement mechanism.
     */
    @Column(name = "lock_type", nullable = false)
    var lockType: Int = 0
}
