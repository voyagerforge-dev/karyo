package com.karyo.orders.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.orders.vo.GoodsReceiptType
import com.karyo.orders.vo.OrderState
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * A physical goods-receipt session at the dock. Optionally bound to any number of
 * [Asn]s (receipt-against-expectation, V424 many-to-many via [GoodsReceiptAsn]);
 * no attached ASN = blind receipt. The set is not held as a collection here --
 * [com.karyo.orders.repository.GoodsReceiptAsnRepository] is the source of truth,
 * matching the "explicit join entity, no [jakarta.persistence.ManyToMany]" convention.
 *
 * Lifecycle (OrderState subset): CREATED(50) → STARTED(500, first line received)
 * → FINISHED(700); CANCELED(800) allowed only while no lines were received.
 */
@Entity
@Table(name = "goods_receipts")
class GoodsReceipt : TenantEntity() {

    @Column(name = "receipt_number", nullable = false, length = 100)
    lateinit var receiptNumber: String

    /**
     * Inbound reason — [GoodsReceiptType] code (0 NORMAL, 1 RETOUR; myWMS parity).
     * Immutable after create; RETOUR never binds an ASN (see the enum KDoc).
     */
    @Column(name = "receipt_type", nullable = false)
    var receiptType: Int = GoodsReceiptType.NORMAL.code

    @Column(name = "carrier_name", length = 255)
    var carrierName: String? = null

    @Column(name = "delivery_note_number", length = 100)
    var deliveryNoteNumber: String? = null

    @Column(length = 2000)
    var notes: String? = null

    @Column(nullable = false)
    var state: Int = OrderState.UNDEFINED.code

    /** Receipt priority — the [DeliveryOrder.prio] convention (default 50, lower = more urgent). */
    @Column(nullable = false)
    var prio: Int = DEFAULT_PRIO

    /**
     * The date the goods PHYSICALLY arrived — operator-entered and backdatable
     * (paperwork often lags the truck). Distinct from [com.karyo.common.domain.BaseEntity.created]
     * (when the record was opened); `DefaultGoodsReceiptLookup.receivedAt` prefers this
     * when set and falls back to `created`.
     */
    @Column(name = "receipt_date")
    var receiptDate: LocalDate? = null

    /**
     * Denormalized dock-door pair, UNVALIDATED against the layout module — the same
     * deliberate convention as [GoodsReceiptLine.locationId]/`locationName` (ID-only
     * cross-module references; a LocationLookup validation seam may arrive later).
     */
    @Column(name = "dock_location_id")
    var dockLocationId: Long? = null

    @Column(name = "dock_location_name", length = 100)
    var dockLocationName: String? = null

    /**
     * The operator who claimed this receipt — PURE METADATA, never coupled to [state]
     * (unlike the PickOrder precedent, whose claim/release also move PickState —
     * see [com.karyo.orders.service.GoodsReceiptService.claim]).
     */
    @Column(name = "operator_id", length = 100)
    var operatorId: String? = null

    /**
     * Orthogonal pause stamp: non-null = paused. [state] NEVER moves on pause/resume,
     * so resume restores the receipt exactly where it was (myWMS's PAUSE state jump
     * lost STARTED on resume — deliberately not adopted from myWMS here).
     */
    @Column(name = "paused_at")
    var pausedAt: Instant? = null

    @OneToMany(
        mappedBy = "goodsReceipt",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("id ASC")
    var lines: MutableList<GoodsReceiptLine> = mutableListOf()

    companion object {
        const val DEFAULT_PRIO = 50
    }
}
