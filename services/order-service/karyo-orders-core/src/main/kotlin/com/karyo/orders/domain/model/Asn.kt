package com.karyo.orders.domain.model

import com.karyo.common.domain.TenantEntity
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
 * Advance Shipping Notice — expected inbound goods, received against by
 * [GoodsReceipt]. clientId (via [TenantEntity]) is the domain-owner attribute per
 * the silo-tenancy rule.
 *
 * Lifecycle (OrderState subset): CREATED(50) → RELEASED(100) → STARTED(500, first
 * receipt against it) → FINISHED(700); CANCELED(800) allowed pre-STARTED only.
 */
@Entity
@Table(name = "asns")
class Asn : TenantEntity() {

    @Column(name = "asn_number", nullable = false, length = 100)
    lateinit var asnNumber: String

    @Column(name = "external_number", length = 100)
    var externalNumber: String? = null

    @Column(name = "carrier_name", length = 255)
    var carrierName: String? = null

    /** Who supplies the goods on this ASN — the commercial party. See [senderName]. */
    @Column(name = "supplier_name", length = 255)
    var supplierName: String? = null

    /**
     * Who dispatched this particular shipment — distinct from [supplierName]: supplier
     * is the commercial source of the goods, sender is the party that physically sent
     * this shipment. They differ whenever a supplier ships from a 3PL or another site.
     */
    @Column(name = "sender_name", length = 255)
    var senderName: String? = null

    @Column(name = "expected_date")
    var expectedDate: LocalDate? = null

    @Column(length = 2000)
    var notes: String? = null

    @Column(nullable = false)
    var state: Int = OrderState.UNDEFINED.code

    /** Stamped on the transition to RELEASED (mirrors [DeliveryOrder.started]). */
    @Column
    var started: Instant? = null

    /** Stamped on reaching a terminal state, FINISHED or CANCELED (mirrors [DeliveryOrder.finished]). */
    @Column
    var finished: Instant? = null

    @OneToMany(
        mappedBy = "asn",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("lineNumber ASC")
    var lines: MutableList<AsnLine> = mutableListOf()

    /**
     * UL pre-advices (Karyo-native, see [AsnUlAdvice]). Managed directly through
     * [com.karyo.orders.repository.AsnUlAdviceRepository] (not via this collection's
     * cascade — advices are created/deleted independently of the ASN header, and
     * matched across a whole receipt's ASN set); this relation exists so
     * [com.karyo.orders.service.AsnService.toResponse] can embed them without an
     * extra collaborator.
     */
    @OneToMany(
        mappedBy = "asn",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("id ASC")
    var ulAdvices: MutableList<AsnUlAdvice> = mutableListOf()
}
