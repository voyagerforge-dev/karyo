package com.karyo.orders.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * System-level order configuration (no clientId — silo tenancy makes the company
 * implicit). v1.2 carries the two reservation flags; the picking flags
 * (sendToPacking etc.) join in v1.3. [extensionProperties] is a raw-JSON JSONB
 * column (same pattern as OutboxEvent.payload) acting as the relief valve for
 * extension config instead of new columns.
 */
@Entity
@Table(name = "order_strategies")
class OrderStrategy : BaseEntity() {

    @Column(nullable = false, length = 100, unique = true)
    lateinit var name: String

    @Column(name = "use_locked_stock", nullable = false)
    var useLockedStock: Boolean = false

    @Column(name = "prefer_complete", nullable = false)
    var preferComplete: Boolean = true

    @Column(name = "prefer_matching", nullable = false)
    var preferMatching: Boolean = false

    @Column(name = "complete_handling", nullable = false)
    var completeHandling: Int = 0

    @Column(name = "enforce_lot", nullable = false)
    var enforceLot: Boolean = false

    @Column(name = "short_pick_mode", nullable = false, length = 40)
    var shortPickMode: String = com.karyo.orders.vo.ShortPickMode.DEFAULT.name

    @Column(name = "shortfall_strategy", nullable = false, length = 60)
    var shortfallStrategy: String = "PARTIAL_SHIP"

    @Column(name = "pick_difference_strategy", nullable = false, length = 60)
    var pickDifferenceStrategy: String = "LEAVE"

    @Column(name = "packout_strategy", nullable = false, length = 60)
    var packoutStrategy: String = "ONE_TO_ONE"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extension_properties", nullable = false, columnDefinition = "JSONB")
    var extensionProperties: String = "{}"

    /**
     * Row 8. When true, a picked order parks in PACKING(640) instead of stopping at PICKED(600);
     * `markPacked` then moves it on. Public behavioral contract:
     * `docs/functional/picking.md#41-downstream-progression-flags`. Gates state parking only,
     * never whether packing itself happens; packing is always available regardless of this flag.
     */
    @Column(name = "send_to_packing", nullable = false)
    var sendToPacking: Boolean = false

    /**
     * Row 8. When true, a packed order parks in SHIPPING(670) instead of stopping at PACKED(650);
     * `markShipped` then moves it on. Public behavioral contract:
     * `docs/functional/picking.md#41-downstream-progression-flags`. Karyo defaults the flag FALSE
     * deliberately; flipping the default would silently change the state every existing order
     * comes to rest in.
     */
    @Column(name = "send_to_shipping", nullable = false)
    var sendToShipping: Boolean = false

    /**
     * Row 8. When true, pick-order completion auto-opens the shipment through PackingService, so
     * an operator does not have to post one. This is Karyo behavior, not a parity claim, and is
     * specified in `docs/functional/picking.md#41-downstream-progression-flags`. A refusal
     * (no PACK_STAGING location, wrong pick state) is swallowed, never propagated -- see
     * [com.karyo.fulfillment.service.PackingService] (not directly reachable from this module).
     */
    @Column(name = "create_shipping_order", nullable = false)
    var createShippingOrder: Boolean = false

    /**
     * Row 8. When true, `releaseToPicking` splits a mixed release into one PickOrder per derived
     * picking type (COMPLETE vs PICK) instead of one PickOrder for the whole release. Public
     * behavioral contract: `docs/functional/picking.md#23-pick-order-generation`. The split uses
     * the same full/COMPLETE-vs-PICK derivation that stamps each Pick's `pickingType`.
     */
    @Column(name = "create_type_orders", nullable = false)
    var createTypeOrders: Boolean = false

    /**
     * Row 8. Fallback destination for a released PickOrder when the delivery order itself has no
     * destination set (`DeliveryOrder.destinationLocationId ?: this`). Cross-module id-only
     * reference (the StorageLocation lives in the layout module) -- validated at write time via
     * `StorageLocationLookup` (Task 7's `DestinationLocationResolver`), not a foreign key.
     * Public behavioral contract: `docs/functional/picking.md#23-pick-order-generation`.
     */
    @Column(name = "default_destination_location_id")
    var defaultDestinationLocationId: Long? = null

    companion object {
        const val DEFAULT_NAME = "DEFAULT"
    }
}
