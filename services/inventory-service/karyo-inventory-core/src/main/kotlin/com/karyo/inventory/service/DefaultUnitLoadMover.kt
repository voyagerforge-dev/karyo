package com.karyo.inventory.service

import com.karyo.inventory.api.spi.UnitLoadMover
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Default in-process [UnitLoadMover], scoped to the current tenant. [move] delegates to the
 * existing [UnitLoadService.transferToLocation], which fires `UnitLoadTransferredEvent`
 * (-> layout allocation update via `UnitLoadTransferredObserver`) and writes the outbox
 * row -- the exact path the receive->putaway workflow already relies on. [appendDispatchSuffix]
 * (S6, outbound-completion task-8) delegates to [UnitLoadService.renameForDispatch].
 */
@ApplicationScoped
class DefaultUnitLoadMover(
    private val unitLoadService: UnitLoadService,
    private val tenantContext: TenantContext,
) : UnitLoadMover {

    @Transactional
    override fun move(unitLoadId: Long, destinationLocationId: Long, destinationLocationName: String) {
        unitLoadService.transferToLocation(unitLoadId, destinationLocationId, destinationLocationName, tenantContext)
    }

    @Transactional
    override fun appendDispatchSuffix(unitLoadId: Long): String =
        unitLoadService.renameForDispatch(unitLoadId, tenantContext)
}
