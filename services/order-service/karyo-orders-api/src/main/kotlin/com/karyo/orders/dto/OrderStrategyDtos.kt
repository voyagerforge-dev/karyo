package com.karyo.orders.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.karyo.common.patch.Patchable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Order strategies are system-level configuration (no clientId — silo tenancy makes
 * the company implicit). v1.3 adds the picking knobs (preferMatching, completeHandling,
 * enforceLot); [extensionProperties] is the JSONB relief valve for extension-specific config.
 */
data class CreateOrderStrategyRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val useLockedStock: Boolean = false,
    val preferComplete: Boolean = true,
    val preferMatching: Boolean = false,
    val completeHandling: Int = 0,
    val enforceLot: Boolean = false,
    val shortPickMode: String = com.karyo.orders.vo.ShortPickMode.DEFAULT.name,
    val shortfallStrategy: String = "PARTIAL_SHIP",
    val pickDifferenceStrategy: String = "LEAVE",
    val packoutStrategy: String = "ONE_TO_ONE",
    val extensionProperties: Map<String, Any> = emptyMap(),
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.sendToPacking] for semantics. */
    val sendToPacking: Boolean = false,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.sendToShipping] for semantics. */
    val sendToShipping: Boolean = false,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.createShippingOrder] for semantics. */
    val createShippingOrder: Boolean = false,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.createTypeOrders] for semantics. */
    val createTypeOrders: Boolean = false,
    /**
     * Row 8. Validated at write time via `StorageLocationLookup` (422 on unknown/foreign) --
     * see [com.karyo.orders.domain.model.OrderStrategy.defaultDestinationLocationId].
     */
    val defaultDestinationLocationId: Long? = null,
)

data class UpdateOrderStrategyRequest(
    val useLockedStock: Boolean? = null,
    val preferComplete: Boolean? = null,
    val preferMatching: Boolean? = null,
    val completeHandling: Int? = null,
    val enforceLot: Boolean? = null,
    val shortPickMode: String? = null,
    val shortfallStrategy: String? = null,
    val pickDifferenceStrategy: String? = null,
    val packoutStrategy: String? = null,
    val extensionProperties: Map<String, Any>? = null,
    val sendToPacking: Boolean? = null,
    val sendToShipping: Boolean? = null,
    val createShippingOrder: Boolean? = null,
    val createTypeOrders: Boolean? = null,
    /**
     * Tri-state (:1457, 2026-08-17): absent = leave unchanged, explicit JSON `null` = clear,
     * a value = set (revalidated via `DestinationLocationResolver`; `null` is always valid,
     * so the clear path bypasses validation naturally). Matches
     * [com.karyo.product.dto.UpdateProductRequest.defaultPackagingUnitId]'s `Patchable<Long>`
     * annotation set -- no `@Size`/`@PatchableSize`, string-length-only concern.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val defaultDestinationLocationId: Patchable<Long> = Patchable.Absent,
)

data class OrderStrategyResponse(
    val id: Long,
    val name: String,
    val useLockedStock: Boolean,
    val preferComplete: Boolean,
    val preferMatching: Boolean,
    val completeHandling: Int,
    val enforceLot: Boolean,
    val shortPickMode: String,
    val shortfallStrategy: String,
    val pickDifferenceStrategy: String,
    val packoutStrategy: String,
    val extensionProperties: Map<String, Any>,
    val sendToPacking: Boolean,
    val sendToShipping: Boolean,
    val createShippingOrder: Boolean,
    val createTypeOrders: Boolean,
    val defaultDestinationLocationId: Long?,
    /** Resolved display name for [defaultDestinationLocationId]; null when unset or no longer resolvable. */
    val defaultDestinationLocationName: String?,
)
