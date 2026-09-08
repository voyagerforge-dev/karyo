package com.karyo.layout.service

import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.spi.FixAssignmentLookup
import com.karyo.layout.spi.FixAssignmentView
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Default implementation of [FixAssignmentLookup] for replenishment and picking.
 *
 * [listForReplenishment] delegates to [FixAssignmentService.listByClient], which already
 * enriches [com.karyo.layout.dto.FixAssignmentResponse.currentStockAmount] via
 * [FixAssignmentService.enrichStockAmount]. We reuse that value directly rather than calling
 * enrichStockAmount a second time.
 */
@ApplicationScoped
class DefaultFixAssignmentLookup(
    private val fixAssignmentService: FixAssignmentService,
    private val fixAssignmentRepository: FixAssignmentRepository,
) : FixAssignmentLookup {

    override fun listForReplenishment(clientId: Long): List<FixAssignmentView> =
        fixAssignmentService.listByClient(clientId).map { r ->
            FixAssignmentView(
                assignmentId = r.id,
                locationId = r.locationId,
                locationName = r.locationName,
                itemDataId = r.itemDataId,
                itemDataNumber = r.itemDataNumber,
                minAmount = r.minAmount,
                maxAmount = r.maxAmount,
                desiredAmount = r.desiredAmount,
                currentAmount = r.currentStockAmount,
            )
        }

    override fun pickCeilings(clientId: Long, itemDataId: Long, locationIds: Set<Long>): Map<Long, BigDecimal> {
        if (locationIds.isEmpty()) return emptyMap()
        return fixAssignmentRepository.findPickCeilings(clientId, itemDataId, locationIds)
            .associate { it.location.id!! to it.maxPickAmount!! }
    }

    override fun clientIdsWithAssignments(): List<Long> = fixAssignmentRepository.distinctClientIds()
}
