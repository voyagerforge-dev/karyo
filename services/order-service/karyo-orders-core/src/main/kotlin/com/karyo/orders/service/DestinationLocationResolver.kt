package com.karyo.orders.service

import com.karyo.layout.spi.StorageLocationLookup
import com.karyo.orders.exception.OrderException
import jakarta.enterprise.context.ApplicationScoped

/**
 * Row 10 destination-location concern, split out of [OrderService] (and, as of fix round 1, out
 * of the single grab-bag `DeliveryOrderValidator` this class replaces) so the
 * [StorageLocationLookup] dependency does not push [OrderService]'s function count past detekt's
 * `TooManyFunctions` ceiling (25) -- the [ReceiveLineValidator] precedent, including taking over
 * the SPI's ENTIRE usage (write-time validation, single-order read, and the batched page-level
 * read) so [OrderService] swaps its [StorageLocationLookup] param for this resolver.
 *
 * Named for what it does (location resolution), not for the entity it currently serves, because
 * row 8's `OrderStrategy.defaultDestination` is a second, unrelated consumer of the same
 * [StorageLocationLookup] dependency -- a class named `DeliveryOrderValidator` would have been the
 * wrong host for that. The operator-claim guards that used to share this class with the location
 * concern have moved to [OrderClaimGuard]; they used no injected dependency and had nothing to do
 * with locations, so bundling them here was a grab-bag purely to relieve a function count, not a
 * real shared concern.
 */
@ApplicationScoped
class DestinationLocationResolver(
    private val storageLocationLookup: StorageLocationLookup,
) {

    /**
     * Row 10: a caller-supplied [destinationLocationId] must name a
     * [com.karyo.layout.domain.model.StorageLocation] owned by [clientId] -- unknown or foreign
     * (both collapse to the same [StorageLocationLookup.findById] `null` result, mirroring
     * [ReceiveLineValidator.validateStorageStrategy]'s ownership fail-close) is a 422. `null` (no
     * destination set) is always valid.
     */
    fun validateDestinationLocation(destinationLocationId: Long?, clientId: Long) {
        if (destinationLocationId != null && storageLocationLookup.findById(destinationLocationId, clientId) == null) {
            throw OrderException.InvalidDestinationLocation(destinationLocationId)
        }
    }

    /**
     * Resolves ONE destination location's display name for the response; null when unset or no
     * longer resolvable. Single-order callers only (getById/create/release/claim/etc); [list]
     * uses [resolveNames] instead to avoid one query per order on the page.
     */
    fun resolveName(destinationLocationId: Long?, clientId: Long): String? =
        destinationLocationId?.let { storageLocationLookup.findById(it, clientId)?.name }

    /**
     * Batched `destinationLocationId -> name` lookup for a whole page of orders, shaped like
     * [com.karyo.product.spi.ProductLookup.findNamesByIds] -- backs
     * [OrderService.list]'s pre-computed lookup maps (alongside product names, shipments, and
     * pick rollups), added in fix round 1 to close the N+1 [resolveName] would otherwise trigger
     * once per order carrying a destination.
     */
    fun resolveNames(destinationLocationIds: Set<Long>, clientId: Long): Map<Long, String> =
        storageLocationLookup.findNamesByIds(destinationLocationIds, clientId)
}
