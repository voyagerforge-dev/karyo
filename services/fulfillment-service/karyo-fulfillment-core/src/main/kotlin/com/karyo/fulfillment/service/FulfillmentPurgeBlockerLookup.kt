package com.karyo.fulfillment.service

import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.inventory.api.spi.PurgeBlockerLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Row 18, fulfillment half of [PurgeBlockerLookup]: a non-terminal pick's SOURCE stock unit
 * reference blocks purge ([PickRepository.findOpenSourceStockUnitIds] -- terminal-state
 * filtered, since a confirmed pick's source was fully consumed and is history from then on). A
 * pick's TARGET stock unit reference blocks purge unconditionally
 * ([PickRepository.findReferencedTargetStockUnitIds] -- fix round 1, Critical 1: the target is
 * what physically holds the picked quantity, and document generation reads it on demand forever,
 * so it is never merely historical, same reasoning as the two unit-load references below). A
 * pick order's target container reference OR a shipping unit's container reference blocks a
 * unit load's purge ([PickOrderRepository.findReferencedTargetUnitLoadIds] +
 * [ShippingUnitRepository.findReferencedUnitLoadIds] -- see [PurgeBlockerLookup]'s KDoc for why
 * both block unconditionally).
 */
@ApplicationScoped
class FulfillmentPurgeBlockerLookup(
    private val pickRepository: PickRepository,
    private val pickOrderRepository: PickOrderRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
) : PurgeBlockerLookup {

    override fun blockedStockUnitIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        pickRepository.findOpenSourceStockUnitIds(candidateIds, clientId) +
            pickRepository.findReferencedTargetStockUnitIds(candidateIds, clientId)

    override fun blockedUnitLoadIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        pickOrderRepository.findReferencedTargetUnitLoadIds(candidateIds, clientId) +
            shippingUnitRepository.findReferencedUnitLoadIds(candidateIds, clientId)
}
