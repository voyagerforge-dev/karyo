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
 * Outbound customer order. clientId (via [TenantEntity]) is the domain-owner
 * attribute per the silo-tenancy rule — it scopes queries, not security machinery.
 */
@Entity
@Table(name = "delivery_orders")
class DeliveryOrder : TenantEntity() {

    @Column(name = "order_number", nullable = false, length = 100)
    lateinit var orderNumber: String

    @Column(name = "external_number", length = 100)
    var externalNumber: String? = null

    @Column(name = "customer_name", length = 255)
    var customerName: String? = null

    @Column(name = "street", length = 255)
    var street: String? = null

    @Column(name = "street_number", length = 40)
    var streetNumber: String? = null

    @Column(name = "zip_code", length = 40)
    var zipCode: String? = null

    @Column(name = "city", length = 120)
    var city: String? = null

    @Column(name = "country", length = 80)
    var country: String? = null

    @Column(name = "phone", length = 60)
    var phone: String? = null

    @Column(name = "email", length = 120)
    var email: String? = null

    @Column(name = "delivery_date")
    var deliveryDate: LocalDate? = null

    @Column(nullable = false)
    var prio: Int = DEFAULT_PRIO

    @Column(length = 2000)
    var notes: String? = null

    /** Operator instruction shown during picking (D3); null = no hint. */
    @Column(name = "picking_hint", length = 500)
    var pickingHint: String? = null

    /** Operator instruction shown during packing (D3); null = no hint. */
    @Column(name = "packing_hint", length = 500)
    var packingHint: String? = null

    /** Operator instruction shown during shipping (D3); null = no hint. */
    @Column(name = "shipping_hint", length = 500)
    var shippingHint: String? = null

    @Column(nullable = false)
    var state: Int = OrderState.UNDEFINED.code

    /** ID-only reference to a system-level [OrderStrategy]; null = DEFAULT strategy. */
    @Column(name = "order_strategy_id")
    var orderStrategyId: Long? = null

    @Column
    var started: Instant? = null

    @Column
    var finished: Instant? = null

    /**
     * Row 10: which [com.karyo.layout.domain.model.StorageLocation] inside THIS warehouse the
     * order's work is bound for -- e.g. a staging lane or an outbound dock -- cross-module
     * id-only reference (no FK), validated at write time via `StorageLocationLookup`. This is
     * NOT the customer's ship-to address ([street]/[city]/[country] above, V414) and NOT a
     * per-parcel label override (`shipping_units.ship_to_*`, V607): those answer where the goods
     * go in the world, this answers where inside this building the order's work is bound.
     */
    @Column(name = "destination_location_id")
    var destinationLocationId: Long? = null

    /**
     * Row 10: claiming operator -- pure metadata, never coupled to [state] (mirrors
     * GoodsReceipt/Shipment; see [com.karyo.orders.service.OrderService.claim]'s KDoc for why the
     * pick precedent's state-moving claim is deliberately not copied here).
     */
    @Column(name = "operator_id", length = 255)
    var operatorId: String? = null

    /** Wave membership (Advanced Fulfillment pack). Nullable: a non-waved order behaves exactly as before. */
    @Column(name = "wave_id")
    var waveId: Long? = null

    /** Per-order release-mode override (MANUAL|WAVE|STREAM); null = the strategy's releaseMode. */
    @Column(name = "release_mode_override", length = 10)
    var releaseModeOverride: String? = null

    /** Streaming engine claim + escalation stamps (B3). Null until streaming first touches the order. */
    @Column(name = "stream_first_attempt_at")
    var streamFirstAttemptAt: Instant? = null

    @Column(name = "stream_escalated_at")
    var streamEscalatedAt: Instant? = null

    @Column(name = "stream_stalled_at")
    var streamStalledAt: Instant? = null

    /**
     * Row 10: the party named as sender on this order's outbound paperwork, the outbound
     * counterpart of [Asn.senderName] (V417). This optional field is Karyo behavior, not a parity
     * claim.
     */
    @Column(name = "sender_name", length = 255)
    var senderName: String? = null

    @OneToMany(
        mappedBy = "deliveryOrder",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("lineNumber ASC")
    var lines: MutableList<DeliveryOrderLine> = mutableListOf()

    companion object {
        const val DEFAULT_PRIO = 50
    }
}
