package com.karyo.stocktaking.service

import com.karyo.layout.spi.LocationLockPort
import com.karyo.stocktaking.spi.CountScopeRequest
import com.karyo.stocktaking.spi.CountScopeStrategy
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in [CountScopeStrategy] for the END_OF_PERIOD full inventory (St5): EVERY storage
 * location owned by the tenant, **empty locations included** (an empty location gets a zero-line
 * count order — the operator confirms it is still empty through
 * `POST /count-orders/{id}/location-empty`, which is exactly what makes a full inventory
 * complete rather than merely "everything we think has stock").
 *
 * Ordering is delegated to [LocationLockPort.allStorageLocationIds] (orderIndex, then name), so
 * the generated orders come out in walking order.
 *
 * ⚠️ **Priority trap.** [CountScopeStrategyResolver] picks the LOWEST [priority] when asked
 * *unnamed*, and [ExplicitLocationScope] sits at [Int.MAX_VALUE]. This strategy therefore
 * OUTRANKS explicit scoping, and an unnamed `resolve()` returns *this* — which would silently
 * turn every plain cycle count into a warehouse-wide freeze. [StocktakingService] must always
 * resolve BY NAME (`"EXPLICIT"` / `"FULL_WAREHOUSE"`); `FullInventoryTest` pins both halves of
 * that contract.
 *
 * The priority value is NOT a displacement knob: since selection is by name (see
 * [CountScopeStrategy]), registering a customer strategy below 100 displaces nothing — a
 * strategy is adopted by being *asked for by name*. Priority now only tie-breaks between
 * strategies sharing a name, and orders the unnamed resolve no production caller uses.
 */
@ApplicationScoped
class FullWarehouseScope(
    private val locationLockPort: LocationLockPort,
) : CountScopeStrategy {
    override val priority: Int = FULL_WAREHOUSE_PRIORITY
    override val name: String = NAME

    /** [request] is ignored by design — a full inventory has no narrowing inputs; the service
     *  rejects (422) any caller that supplies some, rather than silently dropping them. */
    override fun resolveLocations(request: CountScopeRequest, clientId: Long): List<Long> =
        locationLockPort.allStorageLocationIds(clientId)

    companion object {
        const val NAME = "FULL_WAREHOUSE"
        private const val FULL_WAREHOUSE_PRIORITY = 100
    }
}
