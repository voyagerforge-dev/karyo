package com.karyo.fulfillment.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "picks")
class Pick : TenantEntity() {

    @Column(name = "pick_order_id", nullable = false)
    var pickOrderId: Long = 0

    /**
     * V605 (WORKLIST row 20): null for an EXTINGUISH pick — a stock-clearance pick has no
     * backing DeliveryOrder line at all (myWMS `ExtinguishOrderGenerator`: `deliveryOrderLine =
     * null` is legitimate, not a gap). Every consumer of this field must treat null as "not
     * order-bound," never a fabricated line id — see the picking-block sprint plan's
     * null-blast-radius audit list.
     */
    @Column(name = "delivery_order_line_id")
    var deliveryOrderLineId: Long? = null

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false)
    lateinit var itemDataNumber: String

    @Column(name = "source_stock_unit_id", nullable = false)
    var sourceStockUnitId: Long = 0

    @Column(name = "planned_amount", nullable = false)
    var plannedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "picked_amount", nullable = false)
    var pickedAmount: BigDecimal = BigDecimal.ZERO

    @Column(nullable = false)
    var state: Int = 50

    @Column(name = "lot_number")
    var lotNumber: String? = null

    @Column(name = "picking_type", nullable = false)
    var pickingType: String = "PICK"

    @Column(name = "follow_up_for_pick_id")
    var followUpForPickId: Long? = null

    @Column(name = "substituted_item_data_id")
    var substitutedItemDataId: Long? = null

    @Column(name = "target_stock_unit_id")
    var targetStockUnitId: Long? = null

    /**
     * Picked ACTUALS (V604, WORKLIST row 18) — the lot the source stock unit actually carried at
     * confirm time, captured BEFORE [com.karyo.inventory.api.spi.StockPicker.pickStock] mutates it.
     * Distinct from [lotNumber] (the PLANNED lot from the reservation): a zero-picked shortfall
     * (fully covered elsewhere) writes neither field — see `PickOrderService.confirmPick`.
     */
    @Column(name = "picked_lot_number")
    var pickedLotNumber: String? = null

    /** Picked ACTUALS best-before, captured alongside [pickedLotNumber]. See its KDoc. */
    @Column(name = "picked_best_before")
    var pickedBestBefore: LocalDate? = null
}
