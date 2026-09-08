package com.karyo.tasks.service

import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.spi.AreaReplenishmentTaskCommand
import com.karyo.tasks.spi.CrossDockTaskCommand
import com.karyo.tasks.spi.PutawayFromStagingCommand
import com.karyo.tasks.spi.ReplenishmentTaskCommand
import com.karyo.tasks.spi.TransportOrderPort
import com.karyo.tasks.spi.TransportOrderRef
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default implementation of [TransportOrderPort] — delegates to [TaskService] so the
 * replenishment module (and any future callers) can mint and query REPLENISH transport
 * orders without taking a compile-time dependency on tasks-core.
 *
 * [hasOpenAreaReplenishment] (R12b, Task 6) and [openReplenishmentUnitLoadIds]
 * (defect-burndown-4, Task 5) are the exceptions: both read [TransportOrderRepository] DIRECTLY
 * rather than through a `TaskService` passthrough, because `TaskService` is already at Detekt's
 * `TooManyFunctions` ceiling (see that class's own KDoc) and both are trivial reads with no
 * business logic -- unlike [createAreaReplenishment], which genuinely needs `TaskService`'s
 * create/transition/denorm chokepoint and does count against that ceiling.
 */
@ApplicationScoped
class DefaultTransportOrderPort(
    private val taskService: TaskService,
    private val repository: TransportOrderRepository,
) : TransportOrderPort {

    override fun createReplenishment(command: ReplenishmentTaskCommand): TransportOrderRef {
        val r = taskService.createReplenishment(command)
        return TransportOrderRef(id = r.id, orderNumber = r.orderNumber, state = r.state)
    }

    override fun hasOpenReplenishment(fixAssignmentId: Long, clientId: Long): Boolean =
        taskService.hasOpenReplenishment(fixAssignmentId, clientId)

    override fun createAreaReplenishment(command: AreaReplenishmentTaskCommand): TransportOrderRef {
        val r = taskService.createAreaReplenishment(command)
        return TransportOrderRef(id = r.id, orderNumber = r.orderNumber, state = r.state)
    }

    override fun hasOpenAreaReplenishment(itemDataAreaId: Long, clientId: Long): Boolean =
        repository.findOpenAreaReplenishment(itemDataAreaId, clientId) != null

    /** Row 3 (defect-burndown-4, Task 5): same repo-direct rationale as [hasOpenAreaReplenishment]
     *  -- a read with no business logic, not worth a `TaskService` passthrough. */
    override fun openReplenishmentUnitLoadIds(clientId: Long): Set<Long> =
        repository.openReplenishmentUnitLoadIds(clientId)

    override fun createCrossDock(command: CrossDockTaskCommand): TransportOrderRef {
        val r = taskService.createCrossDock(command)
        return TransportOrderRef(id = r.id, orderNumber = r.orderNumber, state = r.state)
    }

    override fun createPutawayFromStaging(command: PutawayFromStagingCommand): TransportOrderRef {
        val r = taskService.createPutawayFromStaging(command)
        return TransportOrderRef(id = r.id, orderNumber = r.orderNumber, state = r.state)
    }

    override fun cancelIfOpen(transportOrderId: Long, clientId: Long): Boolean =
        taskService.cancelIfOpen(transportOrderId, clientId)
}
