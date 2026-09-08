package com.karyo.fulfillment.vo

/**
 * Shipment / shipping-unit lifecycle: PACKING -> PACKED (3.3) -> SHIPPING -> SHIPPED (3.4), plus
 * CANCELED (S4, outbound-completion sprint). Forward-only, with CANCELED as the one gated
 * exception -- the [PickState][com.karyo.fulfillment.vo.PickState] shape copied verbatim: CANCELED
 * is reachable only pre-manifest (from PACKING/PACKED, i.e. `code < SHIPPING`); everything else
 * stays strictly forward-only. Once SHIPPING/SHIPPED, cancel/removal is refused (409).
 */
enum class ShipmentState(val code: Int) {
    PACKING(640),
    PACKED(650),
    SHIPPING(670),
    SHIPPED(680),
    CANCELED(800);

    fun canAdvanceTo(target: ShipmentState): Boolean = when {
        target == this -> false
        target == CANCELED -> code < SHIPPING.code
        else -> target.code > code
    }

    companion object {
        fun fromCode(code: Int): ShipmentState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown ShipmentState code: $code")
    }
}
