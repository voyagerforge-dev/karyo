package com.karyo.fulfillment.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "shipping_units")
class ShippingUnit : TenantEntity() {

    @Column(name = "shipment_id", nullable = false)
    var shipmentId: Long = 0

    @Column(name = "shipping_unit_number", nullable = false)
    lateinit var shippingUnitNumber: String

    @Column(nullable = false)
    var type: String = "CARTON"

    @Column(nullable = false)
    var weight: BigDecimal = BigDecimal.ZERO

    @Column(nullable = false)
    var state: Int = 650

    @Column(name = "unit_load_id")
    var unitLoadId: Long? = null

    @Column(name = "tracking_number")
    var trackingNumber: String? = null

    /**
     * 1-based position of this unit within its shipment (naming precedent: `UnitLoad.index`,
     * column `position_index`), assigned once by [com.karyo.fulfillment.service.PackingService]
     * as `existingUnitCount + offsetInCall + 1` and never changed afterward -- also the source of
     * [shippingUnitNumber]'s `-SU{positionIndex}` suffix, so numbering survives a second `pack()`
     * call on an incomplete packout instead of colliding on the unique constraint.
     */
    @Column(name = "position_index", nullable = false)
    var positionIndex: Int = 0

    /** Per-parcel carrier label reference (distinct from [trackingNumber], which the manifest
     *  step stamps identically onto every unit on the shipment). */
    @Column(name = "carrier_label")
    var carrierLabel: String? = null

    /**
     * Flat nullable ship-to override for this unit's label/print documents. Null in every field
     * (the default) means "inherit the order's ship-to address" -- this is a per-parcel label
     * override, not multi-drop routing.
     */
    @Column(name = "ship_to_name")
    var shipToName: String? = null

    @Column(name = "ship_to_street")
    var shipToStreet: String? = null

    @Column(name = "ship_to_street_number")
    var shipToStreetNumber: String? = null

    @Column(name = "ship_to_zip")
    var shipToZip: String? = null

    @Column(name = "ship_to_city")
    var shipToCity: String? = null

    @Column(name = "ship_to_country")
    var shipToCountry: String? = null

    /**
     * S4/S5 (outbound-completion sprint): provenance of this unit's container, driving how
     * cancel/removal restores its stock ([ShippingLifecycleService]'s `restoreUnit`) --
     * [ORIGIN_PACKOUT] (the default; every unit created by [com.karyo.fulfillment.service.PackingService.pack])
     * restores PACKED stock to PICKED(600); [ORIGIN_AD_HOC] (Task 7's `addAdHocUnit`, no
     * PickOrder behind it) restores to ON_STOCK(300). Column exists since V607; mapped here.
     */
    @Column(nullable = false)
    var origin: String = ORIGIN_PACKOUT

    companion object {
        const val ORIGIN_PACKOUT = "PACKOUT"
        const val ORIGIN_AD_HOC = "AD_HOC"

        /** Sprint C: a container built by cross-order (consolidation-group) pack-out. */
        const val ORIGIN_CONSOLIDATION = "CONSOLIDATION"

        /** Sprint C: an OPEN container at the pack-out wall; 650 once closed. */
        const val STATE_OPEN = 640
    }
}
