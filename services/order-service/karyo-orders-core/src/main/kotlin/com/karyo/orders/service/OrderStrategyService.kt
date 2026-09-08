package com.karyo.orders.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.orders.domain.model.OrderStrategy
import com.karyo.orders.dto.CreateOrderStrategyRequest
import com.karyo.orders.dto.OrderStrategyResponse
import com.karyo.orders.dto.UpdateOrderStrategyRequest
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.OrderStrategyRepository
import com.karyo.orders.spi.OrderStrategyContext
import com.karyo.orders.spi.OrderStrategyResolver
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.transaction.Transactional

/**
 * CRUD-lite for system-level order strategies. A 'DEFAULT' strategy is seeded by
 * migration (useLockedStock=false, preferComplete=true); orders reference a strategy
 * by id and fall back to DEFAULT when none is set.
 *
 * A4/:1558 ruling (validation scope of `defaultDestinationLocationId`, closed by
 * adjudication, documented not coded): `OrderStrategy` is system-level config with no
 * `clientId` of its own by design (silo tenancy makes the company implicit; per the
 * standing decision, no per-tenant strategies are invented for this). `create`/`update`
 * validate `defaultDestinationLocationId` against the WRITER's ambient [TenantContext] --
 * the id itself is binding system config, resolved unconditionally by
 * [com.karyo.fulfillment.service.PickOrderService.releaseToPicking] regardless of which
 * tenant releases the order. [toResponse]'s `defaultDestinationLocationName`, by contrast,
 * is resolved against the READER's tenant and is a per-reader courtesy: it comes back
 * `null` when the reading tenant does not own the location, which is correct (not a bug)
 * -- the id still binds at release either way. No unscoped `StorageLocationLookup` variant
 * is added for this; the `ClientLookup.exists` precedent exists elsewhere but nothing here
 * needs it, and adding an unscoped read seam purely for a display-name courtesy would be
 * scope creep.
 */
@ApplicationScoped
class OrderStrategyService(
    private val repository: OrderStrategyRepository,
    private val objectMapper: ObjectMapper,
    private val resolvers: Instance<OrderStrategyResolver>,
    /**
     * Row 8: `defaultDestinationLocationId` names a `StorageLocation`, which -- like every
     * cross-module reference in this codebase -- is validated at write time, not persisted with a
     * foreign key. `OrderStrategy` carries no `clientId` of its own (system-level config, silo
     * tenancy makes the company implicit), so the ambient [TenantContext] supplies the owning
     * tenant for that validation, exactly as [com.karyo.orders.api.v1.DeliveryOrderResource]'s
     * `asManager` derivation reads the caller's own tenant rather than the entity's.
     */
    private val destinationLocationResolver: DestinationLocationResolver,
    private val tenantContext: TenantContext,
) {

    fun list(): List<OrderStrategyResponse> =
        repository.listAll().map { toResponse(it) }

    fun findById(id: Long): OrderStrategyResponse =
        toResponse(findEntityById(id))

    /**
     * Write-time validation of a strategy reference (used by create/update): loads the explicitly
     * referenced strategy and throws [OrderException.InvalidReference] if it is absent, or returns
     * DEFAULT when no id is given. Operation-time resolution goes through [resolve] instead.
     */
    fun resolveEntity(strategyId: Long?): OrderStrategy {
        if (strategyId != null) {
            return repository.findById(strategyId)
                ?: throw OrderException.InvalidReference("OrderStrategy", "id=$strategyId")
        }
        return repository.findByName(OrderStrategy.DEFAULT_NAME)
            ?: throw OrderException.NotFound("OrderStrategy", "name=${OrderStrategy.DEFAULT_NAME}")
    }

    /**
     * ADR-036 resolution: run the resolver chain (ascending priority, first non-null name wins),
     * fall back to DEFAULT, then load the entity. Replaces direct id lookup at operation time.
     */
    fun resolve(context: OrderStrategyContext): OrderStrategy {
        val name = resolvers
            .sortedBy { it.priority() }
            .firstNotNullOfOrNull { it.resolve(context) }
            ?: OrderStrategy.DEFAULT_NAME
        return repository.findByName(name)
            ?: throw OrderException.NotFound("OrderStrategy", "name=$name")
    }

    @Transactional
    fun create(request: CreateOrderStrategyRequest): OrderStrategyResponse {
        repository.findByName(request.name)?.let {
            throw OrderException.DuplicateName("OrderStrategy", request.name)
        }
        destinationLocationResolver.validateDestinationLocation(
            request.defaultDestinationLocationId, tenantContext.clientId,
        )
        val entity = OrderStrategy().apply {
            name = request.name
            useLockedStock = request.useLockedStock
            preferComplete = request.preferComplete
            preferMatching = request.preferMatching
            completeHandling = request.completeHandling
            enforceLot = request.enforceLot
            shortPickMode = request.shortPickMode
            shortfallStrategy = request.shortfallStrategy
            pickDifferenceStrategy = request.pickDifferenceStrategy
            packoutStrategy = request.packoutStrategy
            extensionProperties = objectMapper.writeValueAsString(request.extensionProperties)
            sendToPacking = request.sendToPacking
            sendToShipping = request.sendToShipping
            createShippingOrder = request.createShippingOrder
            createTypeOrders = request.createTypeOrders
            defaultDestinationLocationId = request.defaultDestinationLocationId
        }
        repository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: UpdateOrderStrategyRequest): OrderStrategyResponse {
        val entity = findEntityById(id)
        applyReservationAndPickingFields(entity, request)
        applyProgressionFields(entity, request)
        return toResponse(entity)
    }

    private fun applyReservationAndPickingFields(entity: OrderStrategy, request: UpdateOrderStrategyRequest) {
        request.useLockedStock?.let { entity.useLockedStock = it }
        request.preferComplete?.let { entity.preferComplete = it }
        request.preferMatching?.let { entity.preferMatching = it }
        request.completeHandling?.let { entity.completeHandling = it }
        request.enforceLot?.let { entity.enforceLot = it }
        request.shortPickMode?.let { entity.shortPickMode = it }
        request.shortfallStrategy?.let { entity.shortfallStrategy = it }
        request.pickDifferenceStrategy?.let { entity.pickDifferenceStrategy = it }
        request.packoutStrategy?.let { entity.packoutStrategy = it }
        request.extensionProperties?.let { entity.extensionProperties = objectMapper.writeValueAsString(it) }
    }

    /**
     * Row 8 fields, split out of [update] purely to keep its own Cyclomatic Complexity under
     * detekt's ceiling (adding these five inline pushed `update` from 15 to 16) -- not a
     * meaningful grouping distinction otherwise.
     */
    private fun applyProgressionFields(entity: OrderStrategy, request: UpdateOrderStrategyRequest) {
        request.sendToPacking?.let { entity.sendToPacking = it }
        request.sendToShipping?.let { entity.sendToShipping = it }
        request.createShippingOrder?.let { entity.createShippingOrder = it }
        request.createTypeOrders?.let { entity.createTypeOrders = it }
        request.defaultDestinationLocationId.apply(
            clear = { entity.defaultDestinationLocationId = null },
            set = {
                destinationLocationResolver.validateDestinationLocation(it, tenantContext.clientId)
                entity.defaultDestinationLocationId = it
            },
        )
    }

    private fun findEntityById(id: Long): OrderStrategy =
        repository.findById(id) ?: throw OrderException.NotFound("OrderStrategy", "id=$id")

    private fun toResponse(entity: OrderStrategy): OrderStrategyResponse =
        OrderStrategyResponse(
            id = entity.id!!,
            name = entity.name,
            useLockedStock = entity.useLockedStock,
            preferComplete = entity.preferComplete,
            preferMatching = entity.preferMatching,
            completeHandling = entity.completeHandling,
            enforceLot = entity.enforceLot,
            shortPickMode = entity.shortPickMode,
            shortfallStrategy = entity.shortfallStrategy,
            pickDifferenceStrategy = entity.pickDifferenceStrategy,
            packoutStrategy = entity.packoutStrategy,
            extensionProperties = readProperties(entity.extensionProperties),
            sendToPacking = entity.sendToPacking,
            sendToShipping = entity.sendToShipping,
            createShippingOrder = entity.createShippingOrder,
            createTypeOrders = entity.createTypeOrders,
            defaultDestinationLocationId = entity.defaultDestinationLocationId,
            // order_strategies is small, system-level config (not a paginated, high-volume
            // table like DeliveryOrder/PickOrder), so a per-row resolveName call here is
            // proportionate -- no batching seam needed the way OrderService.list() required one.
            defaultDestinationLocationName = destinationLocationResolver.resolveName(
                entity.defaultDestinationLocationId, tenantContext.clientId,
            ),
        )

    private fun readProperties(json: String): Map<String, Any> =
        if (json.isBlank()) {
            emptyMap()
        } else {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(json, Map::class.java) as Map<String, Any>
        }
}
