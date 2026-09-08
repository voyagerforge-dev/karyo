package com.karyo.layout.service

import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.vo.StockState
import com.karyo.layout.domain.model.FixAssignment
import com.karyo.layout.dto.CreateFixAssignmentRequest
import com.karyo.layout.dto.FixAssignmentResponse
import com.karyo.layout.dto.UpdateFixAssignmentRequest
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.product.dto.ProductResponse
import com.karyo.product.spi.ProductLookup
import com.karyo.product.vo.ItemDataState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal

@ApplicationScoped
class FixAssignmentService(
    private val fixAssignmentRepository: FixAssignmentRepository,
    private val locationRepository: StorageLocationRepository,
    private val productLookup: ProductLookup,
    private val stockUnitLookup: StockUnitLookup,
) {
    private val log = Logger.getLogger(FixAssignmentService::class.java)

    fun listByClient(clientId: Long): List<FixAssignmentResponse> =
        fixAssignmentRepository.findByClientId(clientId).map { toResponse(it) }

    fun findByLocation(locationId: Long, clientId: Long): List<FixAssignmentResponse> =
        fixAssignmentRepository.findByLocation(locationId, clientId).map { toResponse(it) }

    fun findById(id: Long, clientId: Long): FixAssignmentResponse {
        val entity = findEntityById(id, clientId)
        return toResponse(entity)
    }

    @Suppress("ThrowsCount")
    @Transactional
    fun create(request: CreateFixAssignmentRequest, clientId: Long): FixAssignmentResponse {
        // 1. Validate location exists
        val location = locationRepository.findById(request.locationId)
            ?: throw LayoutException.NotFound("StorageLocation", request.locationId)
        if (location.clientId != clientId) {
            throw LayoutException.NotFound("StorageLocation", request.locationId)
        }

        // 2. Validate unique (location_id, item_data_id)
        fixAssignmentRepository.findByLocationAndItem(request.locationId, request.itemDataId)?.let {
            throw LayoutException.DuplicateName(
                "FixAssignment",
                "location=${request.locationId}/product=${request.itemDataId}"
            )
        }

        // 3. Validate product via in-process lookup
        val product = validateProduct(request.itemDataId)
        if (product.state != ItemDataState.ACTIVE.code) {
            throw LayoutException.ProductValidationFailed(request.itemDataId, "Product is not active")
        }

        // 4. Create entity with denormalized itemDataNumber
        val entity = FixAssignment().apply {
            this.location = location
            itemDataId = request.itemDataId
            itemDataNumber = product.number
            minAmount = request.minAmount
            maxAmount = request.maxAmount
            desiredAmount = request.desiredAmount
            maxPickAmount = request.maxPickAmount
            orderIndex = request.orderIndex
            this.clientId = clientId
        }

        fixAssignmentRepository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: UpdateFixAssignmentRequest, clientId: Long): FixAssignmentResponse {
        val entity = findEntityById(id, clientId)

        request.minAmount?.let { entity.minAmount = it }
        request.maxAmount?.let { entity.maxAmount = it }
        request.desiredAmount?.let { entity.desiredAmount = it }
        request.maxPickAmount?.let { entity.maxPickAmount = it }
        request.orderIndex?.let { entity.orderIndex = it }

        return toResponse(entity)
    }

    @Transactional
    fun delete(id: Long, clientId: Long) {
        val entity = findEntityById(id, clientId)
        fixAssignmentRepository.delete(entity)
    }

    fun validateProduct(itemDataId: Long): ProductResponse {
        return productLookup.findById(itemDataId)
            ?: throw LayoutException.InvalidReference("FixAssignment", "Product", itemDataId)
    }

    /**
     * Task 3 review CRITICAL-1 (replenishment sprint): [clientId] is now REQUIRED and threaded
     * to [StockUnitLookup.findByItemDataId]'s explicit-`clientId` overload rather than the
     * ambient-`TenantContext` single-arg one. [toResponse] passes `entity.clientId` (already
     * validated equal to the caller's own `clientId` by every entry point except the
     * `ReplenishmentScheduler` scan graph, where it's the ONLY correct source — the scheduler
     * thread never primes `TenantContext`). REST-path behavior is unchanged: `entity.clientId`
     * and the ambient tenant were always the same value on every REST-triggered call.
     */
    fun enrichStockAmount(itemDataId: Long, locationId: Long, clientId: Long): BigDecimal? {
        return try {
            val stocks = stockUnitLookup.findByItemDataId(itemDataId, clientId)
            stocks.filter { it.locationId == locationId && it.state == StockState.ON_STOCK.code }
                .fold(BigDecimal.ZERO) { acc, su -> acc + su.amount }
        } catch (e: Exception) {
            log.warn("Failed to fetch stock for fix assignment enrichment: ${e.message}")
            null
        }
    }

    private fun findEntityById(id: Long, clientId: Long): FixAssignment {
        val entity = fixAssignmentRepository.findById(id)
            ?: throw LayoutException.NotFound("FixAssignment", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("FixAssignment", id)
        }
        return entity
    }

    private fun toResponse(entity: FixAssignment): FixAssignmentResponse {
        val stockAmount = enrichStockAmount(entity.itemDataId, entity.location.id!!, entity.clientId)
        return FixAssignmentResponse(
            id = entity.id!!,
            locationId = entity.location.id!!,
            locationName = entity.location.name,
            itemDataId = entity.itemDataId,
            itemDataNumber = entity.itemDataNumber,
            minAmount = entity.minAmount,
            maxAmount = entity.maxAmount,
            desiredAmount = entity.desiredAmount,
            maxPickAmount = entity.maxPickAmount,
            currentStockAmount = stockAmount,
            orderIndex = entity.orderIndex,
            created = entity.created.toString(),
            modified = entity.modified.toString(),
        )
    }
}
