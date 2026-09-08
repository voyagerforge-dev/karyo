package com.karyo.product.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.product.vo.ItemDataState
import com.karyo.product.vo.SerialNoRecordType
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "item_data")
class ItemData : TenantEntity() {
    @Column(nullable = false, length = 100)
    lateinit var number: String

    @Column(nullable = false, length = 255)
    lateinit var name: String

    @Column(length = 2000)
    var description: String? = null

    @Column(nullable = false)
    var state: Int = ItemDataState.ACTIVE.code

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "item_unit_id")
    lateinit var itemUnit: ItemUnit

    @Column(nullable = false)
    var scale: Int = 0

    @Column(precision = 16, scale = 3)
    var weight: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var height: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var width: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var depth: BigDecimal? = null

    @Column(name = "lot_mandatory", nullable = false)
    var lotMandatory: Boolean = false

    @Column(name = "best_before_mandatory", nullable = false)
    var bestBeforeMandatory: Boolean = false

    var shelflife: Int? = null

    @Column(name = "serial_no_record_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    var serialNoRecordType: SerialNoRecordType = SerialNoRecordType.NO_RECORD

    @Column(name = "default_unit_load_type_id")
    var defaultUnitLoadTypeId: Long? = null

    /**
     * Per-product preferred putaway [com.karyo.layout] StorageStrategy (ID-only cross-module
     * ref). **Consumed as of the locations-layout sprint's closing round:**
     * `LocationFinderService.resolveStrategy` falls back to this value (via `ProductLookup`)
     * when the caller's `LocationFinderRequest.storageStrategyId` is null — the middle rung of
     * myWMS's `goodsReceiptLine override -> ItemData.defaultStorageStrategy -> system default`
     * chain. The GR-line override (top rung) and the system-default rung (bottom) remain
     * unimplemented — see the WORKLIST resolver row. Do not remove; it is the binding input
     * for product-driven putaway. (ADR-036 audit)
     */
    @Column(name = "default_storage_strategy_id")
    var defaultStorageStrategyId: Long? = null

    /**
     * The default among this product's [packagingUnits] (ID-only, no FK — style-consistent
     * with the sibling default*Id fields). Invariant: when non-null it references a
     * PackagingUnit belonging to THIS product (enforced in ProductService.updateProduct);
     * removePackagingUnit clears it if the default is removed.
     */
    @Column(name = "default_packaging_unit_id")
    var defaultPackagingUnitId: Long? = null

    @Column(name = "zone_id")
    var zoneId: Long? = null

    @Column(name = "trade_group", length = 100)
    var tradeGroup: String? = null

    @Column(name = "image_url", length = 500)
    var imageUrl: String? = null

    @OneToMany(mappedBy = "itemData", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var numbers: MutableList<ItemDataNumber> = mutableListOf()

    @OneToMany(mappedBy = "itemData", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var packagingUnits: MutableList<PackagingUnit> = mutableListOf()

    /** Computed volume from height * width * depth (all in same unit). Null if any dimension is missing. */
    val volume: BigDecimal?
        get() {
            val h = height ?: return null
            val w = width ?: return null
            val d = depth ?: return null
            return h.multiply(w).multiply(d)
        }
}
