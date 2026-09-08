package com.karyo.orders.domain.model

import com.karyo.common.domain.BaseEntity
import com.karyo.orders.vo.OrderState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * Unit-load pre-advice on an [Asn] — a Karyo-native capability: UL pre-advice has no
 * myWMS behavioral reference (it lives in the unanalyzed legacy layer), so every
 * decision here (the state subset, the silent receive-time match, the label
 * generation scheme) is Karyo's own, not a reimplementation of a documented
 * legacy behavior.
 *
 * Owned by [Asn] (`@ManyToOne`, like [AsnLine]). A caller pre-registers a
 * [labelId] expected to arrive on the ASN (caller-supplied or server-generated,
 * unique per ASN — see the V426 constraint); [com.karyo.orders.service.AsnUlAdviceService.match]
 * stamps a receive-time match silently — an un-advised label at receive time is
 * never refused or warned about, it simply receives as a first-class arrival.
 *
 * Lifecycle (OrderState subset): CREATED(50) -> FINISHED(700) on a receive-time
 * match, which also stamps [matchedReceiptLineId]. FINISHED can go back to
 * CREATED (pointer cleared) via [com.karyo.orders.service.AsnUlAdviceService.reopen] —
 * [com.karyo.orders.service.GoodsReceiptService.reverseLine] calls it when the
 * matched receipt line is reversed, so the re-receive of the same physical UL can
 * match again. CANCELED(800) is never actually stored on [state] — deleting a
 * still-open (CREATED) advice removes the row outright, so 800 is the conceptual
 * terminal for "canceled", not a value this column ever holds (pre-match only —
 * a FINISHED advice is receiving history and refuses deletion with 409).
 */
@Entity
@Table(name = "asn_ul_advices")
class AsnUlAdvice : BaseEntity() {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id")
    lateinit var asn: Asn

    @Column(name = "label_id", nullable = false, length = 100)
    lateinit var labelId: String

    @Column(name = "unit_load_type_id")
    var unitLoadTypeId: Long? = null

    /** Denormalized from the product module at creation time (ID-only cross-module reference). */
    @Column(name = "item_data_id")
    var itemDataId: Long? = null

    @Column(name = "item_data_number", length = 100)
    var itemDataNumber: String? = null

    @Column(name = "expected_amount", precision = 17, scale = 4)
    var expectedAmount: BigDecimal? = null

    @Column(name = "reason_for_return", length = 255)
    var reasonForReturn: String? = null

    @Column(nullable = false)
    var state: Int = OrderState.CREATED.code

    @Column(name = "matched_receipt_line_id")
    var matchedReceiptLineId: Long? = null
}
