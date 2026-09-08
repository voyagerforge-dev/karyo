package com.karyo.orders.domain.model

import com.karyo.common.domain.BaseEntity
import com.karyo.inventory.api.vo.LockType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * One received line — the audit record linking a receipt to the stock it created.
 * Owned by [GoodsReceipt] (cascade ALL, orphanRemoval).
 *
 * stockUnitId/unitLoadId are ID-only references into the inventory module
 * (ADR-004). locationId/locationName are passed by the caller — v1.2 performs no
 * cross-module location validation (sub-phase 2.3's LocationLookup will add it).
 */
@Entity
@Table(name = "goods_receipt_lines")
class GoodsReceiptLine : BaseEntity() {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_receipt_id")
    lateinit var goodsReceipt: GoodsReceipt

    /** Linked expected line of the receipt's ASN; null = blind line. */
    @Column(name = "asn_line_id")
    var asnLineId: Long? = null

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false, length = 100)
    lateinit var itemDataNumber: String

    @Column(precision = 17, scale = 4, nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "location_id", nullable = false)
    var locationId: Long = 0

    @Column(name = "location_name", nullable = false, length = 100)
    lateinit var locationName: String

    @Column(name = "unit_load_label", nullable = false, length = 255)
    lateinit var unitLoadLabel: String

    @Column(name = "stock_unit_id", nullable = false)
    var stockUnitId: Long = 0

    @Column(name = "unit_load_id", nullable = false)
    var unitLoadId: Long = 0

    @Column(name = "lot_number", length = 255)
    var lotNumber: String? = null

    /** Serial number as received; threaded onto the created stock unit. */
    @Column(name = "serial_number", length = 255)
    var serialNumber: String? = null

    /**
     * ID-only reference to the product module's PackagingUnit the goods arrived in
     * (e.g. case vs. each). **A5 (hard-parity-sprint): now VALIDATED** — via the
     * `PackagingUnitLookup` SPI, `StockService.createStock` confirms the id exists and
     * belongs to the line's item before it is copied onto the created stock unit
     * (`StockUnit.packagingUnitId`); an unknown or mismatched id rejects the whole
     * receive-line call. `changePackagingUnit` (reassigning an already-received line)
     * remains a separate, still-open operation.
     */
    @Column(name = "packaging_unit_id")
    var packagingUnitId: Long? = null

    @Column(name = "best_before")
    var bestBefore: LocalDate? = null

    /**
     * Inventory [LockType] code applied to the created stock at receipt (subset
     * {GENERAL 1, QUALITY_FAULT 103, LOT_EXPIRED 202, LOT_TOO_YOUNG 203} — validated
     * in GoodsReceiptService); null = received unlocked. NOT the layout LockType.
     */
    @Column(name = "lock_type")
    var lockType: Int? = null

    /**
     * Authoritative full operator note for the line (e.g. why it was locked). The
     * inventory journal hop truncates a copy VISIBLY to 50 chars (`take(47) + "…"`,
     * activity_code is VARCHAR(50)); this column keeps the untruncated text.
     */
    @Column(name = "note", length = 255)
    var note: String? = null

    /** B3: when non-null the line was reversed (stock soft-deleted, ASN decremented). */
    @Column(name = "reversed_at")
    var reversedAt: Instant? = null

    /**
     * V425 (inbound-completion row 7 residual): an optional per-line override of the putaway
     * location finder's `StorageStrategy` — ID-only reference into the layout module, no FK
     * (ADR-004). Validated at receive time (unknown/foreign → 422, see [ReceiveLineValidator]);
     * carried onto [com.karyo.orders.event.GoodsReceiptLineReceivedEvent] and PERSISTED on the
     * auto-created putaway `TransportOrder` so the re-resolve-on-start path can replay it.
     */
    @Column(name = "storage_strategy_id")
    var storageStrategyId: Long? = null

    /**
     * DERIVED (V420 dropped the qa_hold column): true when ANY lock was applied at
     * receipt — not only a QA fault. Kept because the response DTO and
     * [com.karyo.orders.event.GoodsReceiptLineReceivedEvent] contracts carry it
     * (meaning "this stock is held, skip putaway / skip ON_STOCK promotion at finish").
     */
    @get:Transient
    val qaHold: Boolean
        get() = lockType != null && lockType != LockType.UNLOCKED.code

    /** Derived — a reversed line is excluded from finish() promotion, like qaHold. */
    @get:Transient
    val reversed: Boolean
        get() = reversedAt != null
}
