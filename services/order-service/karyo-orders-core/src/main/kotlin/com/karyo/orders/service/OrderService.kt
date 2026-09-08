package com.karyo.orders.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.spi.PickCancelPort
import com.karyo.fulfillment.spi.PickRollupLookup
import com.karyo.fulfillment.spi.ShipmentLookup
import com.karyo.fulfillment.vo.PickRollup
import com.karyo.fulfillment.vo.ShipmentSummary
import com.karyo.inventory.api.spi.ReservationRequest
import com.karyo.inventory.api.spi.ReservedStock
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.domain.model.OrderLineReservation
import com.karyo.orders.dto.CreateDeliveryOrderRequest
import com.karyo.orders.dto.DeliveryOrderLineResponse
import com.karyo.orders.dto.DeliveryOrderReleaseResponse
import com.karyo.orders.dto.DeliveryOrderResponse
import com.karyo.orders.dto.LineShortage
import com.karyo.orders.dto.UpdateDeliveryOrderRequest
import com.karyo.orders.event.DeliveryOrderStateChangedEvent
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.DeliveryOrderLineRepository
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.spi.OrderReleaseValidator
import com.karyo.orders.spi.OrderStrategyContext
import com.karyo.orders.vo.OrderState
import com.karyo.orders.vo.ReleaseMode
import com.karyo.product.spi.ProductLookup
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.enterprise.inject.Instance
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * DeliveryOrder lifecycle for v1.2 (orders stop at PROCESSABLE; picking is v1.3).
 *
 * State mapping implemented here (interpreting the v1.2 spec wiring
 * CREATED→RELEASED→PROCESSABLE→RESERVED):
 *  - **Order**: CREATED→RELEASED on release; RELEASED→PROCESSABLE once ALL lines are
 *    fully reserved (either directly at release or later via retry-reservation).
 *    The order itself never enters PENDING; a shortage keeps it RELEASED with a
 *    shortage report. RESERVED(400) stays unwired until v1.3 picking.
 *  - **Line**: CREATED→PROCESSABLE(300) when fully reserved, CREATED→PENDING(550) on
 *    shortfall. PENDING→PROCESSABLE is the sanctioned retry hop.
 *  - **Cancel**: allowed pre-PICKED; force-finishes any open pick work via [PickCancelPort],
 *    releases the UNHANDLED remainder of each [OrderLineReservation] slice via
 *    [StockReserver.release], zeroes line reservations, and moves order + lines to
 *    CANCELED. See [cancel] for why both halves are required.
 *
 * Every order state change goes through [transition], which enforces
 * [OrderState.canAdvanceTo], fires the synchronous CDI event, and writes the outbox
 * row (webhook/copilot feed).
 */
@ApplicationScoped
class OrderService(
    private val orderRepository: DeliveryOrderRepository,
    private val lineRepository: DeliveryOrderLineRepository,
    private val reservationRepository: OrderLineReservationRepository,
    private val strategyService: OrderStrategyService,
    private val productLookup: ProductLookup,
    private val shipmentLookup: ShipmentLookup,
    private val pickRollupLookup: PickRollupLookup,
    private val pickCancelPort: PickCancelPort,
    private val stockReserver: StockReserver,
    private val outboxService: OutboxService,
    private val stateChangedEvent: Event<DeliveryOrderStateChangedEvent>,
    private val releaseValidators: Instance<OrderReleaseValidator>,
    private val orderNumberResolver: OrderNumberResolver,
    private val destinationLocationResolver: DestinationLocationResolver,
    private val claimGuard: OrderClaimGuard,
) {

    fun findById(id: Long, clientId: Long): DeliveryOrderResponse =
        toResponse(findEntityById(id, clientId))

    fun list(clientId: Long, pagination: PaginationParams, state: Int?, q: String?): PaginatedResponse<DeliveryOrderResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.descending("created"))
        val query = orderRepository.search(clientId, state, q, sort)
            .page(Page.of(pagination.page, pagination.size))
        val orders = query.list()
        val itemDataIds = orders.flatMap { order -> order.lines.map { it.itemDataId } }.toSet()
        val names = productLookup.findNamesByIds(itemDataIds)
        val orderIds = orders.mapNotNull { it.id }.toSet()
        val shipments = shipmentLookup.findByDeliveryOrderIds(orderIds)
        val lineIds = orders.flatMap { order -> order.lines.mapNotNull { it.id } }.toSet()
        val rollups = pickRollupLookup.pickedAmountsByLineIds(lineIds)
        val destinationLocationIds = orders.mapNotNull { it.destinationLocationId }.toSet()
        val destinationNames = destinationLocationResolver.resolveNames(destinationLocationIds, clientId)
        return paginatedResponse(
            orders.map { toResponse(it, names, shipments, rollups, destinationNames) },
            pagination.page,
            pagination.size,
            query.count(),
        )
    }

    @Transactional
    fun create(request: CreateDeliveryOrderRequest, clientId: Long): DeliveryOrderResponse {
        val orderNumber = orderNumberResolver.resolve(request.orderNumber, clientId)
        // Validate the strategy reference up front (DEFAULT fallback when null)
        request.orderStrategyId?.let { strategyService.resolveEntity(it) }
        destinationLocationResolver.validateDestinationLocation(request.destinationLocationId, clientId)

        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            externalNumber = request.externalNumber
            customerName = request.customerName
            street = request.street
            streetNumber = request.streetNumber
            zipCode = request.zipCode
            city = request.city
            country = request.country
            phone = request.phone
            email = request.email
            deliveryDate = request.deliveryDate
            prio = request.prio
            notes = request.notes
            pickingHint = request.pickingHint
            packingHint = request.packingHint
            shippingHint = request.shippingHint
            orderStrategyId = request.orderStrategyId
            destinationLocationId = request.destinationLocationId
            senderName = request.senderName
            releaseModeOverride = parseReleaseModeOverride(request.releaseModeOverride)
        }
        request.lines.forEachIndexed { index, lineRequest ->
            val product = productLookup.findById(lineRequest.itemDataId)
                ?: throw OrderException.InvalidReference("Product", "id=${lineRequest.itemDataId}")
            order.lines.add(
                DeliveryOrderLine().apply {
                    deliveryOrder = order
                    lineNumber = index + 1
                    itemDataId = product.id
                    itemDataNumber = product.number
                    amount = lineRequest.amount
                    lotNumber = lineRequest.lotNumber
                    externalNumber = lineRequest.externalNumber
                    state = OrderState.CREATED.code
                }
            )
        }
        orderRepository.persist(order)
        transition(order, OrderState.CREATED)
        return toResponse(order)
    }

    /** Order streaming (B3): null or blank inherits the strategy; a non-blank, unparsable value is 422. */
    private fun parseReleaseModeOverride(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.let {
            ReleaseMode.parseOrNull(it)?.name
                ?: throw OrderException.InvalidReleaseMode(it)
        }

    @Transactional
    fun update(id: Long, request: UpdateDeliveryOrderRequest, clientId: Long): DeliveryOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.state != OrderState.CREATED.code) {
            throw OrderException.NotEditable(id, order.state)
        }
        applyAddressUpdates(order, request)
        request.externalNumber.apply(clear = { order.externalNumber = null }, set = { order.externalNumber = it })
        request.deliveryDate?.let { order.deliveryDate = it }
        request.prio?.let { order.prio = it }
        request.notes.apply(clear = { order.notes = null }, set = { order.notes = it })
        applyHintUpdates(order, request)
        request.orderStrategyId?.let {
            strategyService.resolveEntity(it)
            order.orderStrategyId = it
        }
        request.destinationLocationId.apply(
            clear = { order.destinationLocationId = null },
            set = {
                destinationLocationResolver.validateDestinationLocation(it, clientId)
                order.destinationLocationId = it
            },
        )
        request.senderName.apply(clear = { order.senderName = null }, set = { order.senderName = it })
        return toResponse(order)
    }

    /** Null-merge of the customer/address block (omitted fields stay untouched). */
    private fun applyAddressUpdates(order: DeliveryOrder, request: UpdateDeliveryOrderRequest) {
        request.customerName?.let { order.customerName = it }
        request.street?.let { order.street = it }
        request.streetNumber?.let { order.streetNumber = it }
        request.zipCode?.let { order.zipCode = it }
        request.city?.let { order.city = it }
        request.country?.let { order.country = it }
        request.phone?.let { order.phone = it }
        request.email?.let { order.email = it }
    }

    /**
     * Tri-state merge of the D3 operator instruction hints (D2): absent leaves the current
     * hint untouched, explicit `null` clears it, a value sets it.
     */
    private fun applyHintUpdates(order: DeliveryOrder, request: UpdateDeliveryOrderRequest) {
        request.pickingHint.apply(clear = { order.pickingHint = null }, set = { order.pickingHint = it })
        request.packingHint.apply(clear = { order.packingHint = null }, set = { order.packingHint = it })
        request.shippingHint.apply(clear = { order.shippingHint = null }, set = { order.shippingHint = it })
    }

    /**
     * Row 10: claims the order for [operatorId]. Mirrors [GoodsReceiptService.claim]'s SEMANTICS
     * -- any existing claim refuses with a 409, INCLUDING the caller's own (claim is not
     * idempotent), and a closed order is not claimable -- but NOT the pick precedent's state
     * mechanics: the pick claim also moves RELEASED -> STARTED (and back on release), which it
     * only gets away with by assigning `state` directly, bypassing its own guard. Here
     * [DeliveryOrder.operatorId] is PURE METADATA and `state` (a real [OrderState.canAdvanceTo]
     * chokepoint) never moves. Eventless by design: a pure metadata change is not a domain event,
     * so this writes no journal row and no outbox row.
     */
    @Transactional
    fun claim(id: Long, operatorId: String, clientId: Long): DeliveryOrderResponse {
        val order = findEntityById(id, clientId)
        claimGuard.requireClaimable(order, id)
        order.operatorId = operatorId
        return toResponse(order)
    }

    /**
     * Row 10: releases the claim. Mirrors [GoodsReceiptService.release]: a non-owner needs
     * [asManager] (the caller holds MANAGER) or the release is a 409 -- the same status the
     * claim conflict maps to. No state gate: the claim is pure metadata, and blocking release
     * after FINISHED would strand the claim forever. Eventless, same rationale as [claim].
     *
     * A6/:1550: releasing an UNCLAIMED order (`operatorId == null`) is a no-op success, not a
     * conflict -- release is an idempotent "make it unclaimed" operation, and the caller's
     * desired end state already holds. Unlike [claim] (deliberately not idempotent: any
     * existing claim, including the caller's own, refuses with 409), release only refuses
     * when a DIFFERENT operator genuinely holds the claim and the caller isn't a manager.
     */
    @Transactional
    fun releaseOperator(id: Long, operatorId: String, asManager: Boolean, clientId: Long): DeliveryOrderResponse {
        val order = findEntityById(id, clientId)
        if (order.operatorId != null && order.operatorId != operatorId && !asManager) {
            throw OrderException.OrderClaimConflict(id, "claimed by a different operator")
        }
        order.operatorId = null
        return toResponse(order)
    }

    /**
     * Releases a CREATED order: consults all [OrderReleaseValidator] extensions, then
     * reserves stock per line via [StockReserver] using the order's strategy
     * (`useLockedStock`). See the class KDoc for the resulting state mapping.
     */
    @Transactional
    fun release(id: Long, clientId: Long): DeliveryOrderReleaseResponse {
        val order = findEntityById(id, clientId)
        if (order.state != OrderState.CREATED.code) {
            throw OrderException.InvalidTransition(id, order.state, OrderState.RELEASED.code)
        }
        runReleaseValidators(order)

        transition(order, OrderState.RELEASED)
        order.started = Instant.now()

        val strategy = strategyService.resolve(OrderStrategyContext(order.orderStrategyId, clientId))
        order.lines.forEach { line ->
            reserveLine(line, strategy, order.orderNumber, clientId)
            val target = if (line.shortage.signum() == 0) OrderState.PROCESSABLE else OrderState.PENDING
            lineTransition(line, target)
        }
        promoteIfFullyReserved(order)
        return DeliveryOrderReleaseResponse(toResponse(order), buildShortages(order))
    }

    /**
     * In-process progression seam (used by [com.karyo.orders.spi.OrderProgressionPort]): advance
     * an order to [target] through the same forward-only [transition] chokepoint as the REST flow.
     */
    @Transactional
    fun progressTo(orderId: Long, clientId: Long, target: OrderState) {
        val order = findEntityById(orderId, clientId)
        transition(order, target)
    }

    /**
     * Re-runs reservation for PENDING lines of a RELEASED order — the sanctioned
     * PENDING→PROCESSABLE retry hop. Promotes the order to PROCESSABLE when all
     * lines end up fully reserved.
     */
    @Transactional
    fun retryReservation(id: Long, clientId: Long): DeliveryOrderReleaseResponse {
        val order = findEntityById(id, clientId)
        if (order.state != OrderState.RELEASED.code) {
            throw OrderException.InvalidTransition(id, order.state, OrderState.PROCESSABLE.code)
        }
        val strategy = strategyService.resolve(OrderStrategyContext(order.orderStrategyId, clientId))
        order.lines.filter { it.state == OrderState.PENDING.code }.forEach { line ->
            reserveLine(line, strategy, order.orderNumber, clientId)
            if (line.shortage.signum() == 0) {
                lineTransition(line, OrderState.PROCESSABLE)
            }
        }
        promoteIfFullyReserved(order)
        return DeliveryOrderReleaseResponse(toResponse(order), buildShortages(order))
    }

    /**
     * Cancels an order (allowed pre-PICKED only, see [OrderState.canAdvanceTo]) and reconciles it
     * with the pick lifecycle:
     *
     *  1. **Cascade first.** Cancel is legal up to PENDING(550), which includes a STARTED(500)
     *     order already released to picking, so [PickCancelPort] force-finishes the still-open
     *     pick work — otherwise the PickOrder stayed claimable and its picks stayed confirmable
     *     against a reservation this method had just given away. An open pick's stock-side
     *     reservation is released by the pick machinery itself, never by us.
     *  2. **Then release only the unhandled remainders.** Each recorded [OrderLineReservation]
     *     slice minus what TERMINAL picks on that same (line, stock unit) already consumed (at
     *     confirm) or released (at cancel / confirm-shortfall), floored at zero. Releasing the
     *     full recorded slice would double-release; `releaseReservation` now refuses an amount
     *     greater than what is actually reserved (defect I1) rather than silently clamping to
     *     zero and stealing a third order's live reservation on the freed stock.
     *
     * The ordering is load-bearing: JPA auto-flushes before the terminal-slice query, so the picks
     * canceled in step 1 are already terminal — and thus already netted out — in step 2.
     */
    @Transactional
    fun cancel(id: Long, clientId: Long): DeliveryOrderResponse {
        val order = findEntityById(id, clientId)
        if (!OrderState.fromCode(order.state).canAdvanceTo(OrderState.CANCELED)) {
            throw OrderException.InvalidTransition(id, order.state, OrderState.CANCELED.code)
        }
        pickCancelPort.cancelOpenWorkForDeliveryOrder(order.id!!, order.clientId)

        val lineIds = order.lines.mapNotNull { it.id }
        stockReserver.release(unhandledRemainders(order.clientId, lineIds), order.clientId)
        reservationRepository.deleteByLineIds(lineIds)

        order.lines.forEach { line ->
            line.reservedAmount = BigDecimal.ZERO
            if (OrderState.fromCode(line.state).canAdvanceTo(OrderState.CANCELED)) {
                lineTransition(line, OrderState.CANCELED)
            }
        }
        transition(order, OrderState.CANCELED)
        order.finished = Instant.now()
        return toResponse(order)
    }

    /**
     * Wave-side seam (backs [com.karyo.orders.spi.OrderReleasePort.releaseLineReservations]):
     * releases the recorded reservations of [lineIds] -- netted against terminal picks via the
     * same [unhandledRemainders] fold [cancel] uses, floored at zero -- zeroes each line's
     * `reservedAmount`, and performs **no state transition**. A line may sit at PENDING (or
     * wherever it already was); retry-reservation stays available. Used by wave SKIP (an order
     * dropped from a wave without being canceled) and HOLD_ORDER.
     *
     * [lineIds] may span multiple orders (a wave holds many); [clientId] scopes both the
     * terminal-pick netting query and the line reload, mirroring [cancel]'s tenant sourcing.
     *
     * **Never trusts [lineIds] directly (defect fix, Task 3 review CRITICAL-1).** The caller-
     * supplied ids are reloaded through [lineRepository.findByIdsAndClient] FIRST, and every
     * subsequent step -- the terminal-pick netting query, `stockReserver.release`, and
     * `deleteByLineIds` -- operates on the resulting [ownedIds], never the raw argument. Passing
     * the raw [lineIds] straight into the (client-id-unaware) `OrderLineReservationRepository`
     * queries would let a foreign id whose reservation `stockReserver.release` warns-and-skips
     * (wrong tenant, so [findByIdForWrite][com.karyo.inventory.service.StockService.findByIdForWrite]-
     * style scoping reports it "gone") still have its `order_line_reservations` ROW deleted by
     * `deleteByLineIds` -- silent cross-tenant data destruction with no error anywhere in the call.
     */
    @Transactional
    fun releaseLineReservations(lineIds: List<Long>, clientId: Long) {
        if (lineIds.isEmpty()) return
        val lines = lineRepository.findByIdsAndClient(lineIds, clientId)
        val ownedIds = lines.mapNotNull { it.id }
        if (ownedIds.isEmpty()) return
        stockReserver.release(unhandledRemainders(clientId, ownedIds), clientId)
        reservationRepository.deleteByLineIds(ownedIds)
        lines.forEach { line ->
            line.reservedAmount = BigDecimal.ZERO
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * What is STILL reserved on this order's behalf: the recorded slices per (line, stock unit),
     * minus the `plannedAmount` of TERMINAL picks on that same pair, floored at zero (a terminal
     * pick's reservation is already consumed or released — see [PickRollupLookup.terminalPlannedBySlice]).
     *
     * Slices are folded per (line, stock unit) before the subtraction: a line can accumulate two
     * rows for the SAME stock unit (release short-reserves it, stock is topped up, retry-reservation
     * reserves it again), and subtracting the whole terminal sum from each row separately would
     * under-release and strand a reservation on a canceled order.
     *
     * Takes [clientId] explicitly (not a [DeliveryOrder], not [com.karyo.security.TenantContext])
     * so both callers -- [cancel] (single order, passes `order.clientId`) and
     * [releaseLineReservations] (lineIds may span multiple orders in a wave) -- net against the
     * goods owner's picks regardless of which principal (OPS/system/scheduler) is driving the call.
     */
    private fun unhandledRemainders(clientId: Long, lineIds: List<Long>): List<ReservedStock> {
        val reservedBySlice = reservationRepository.findByLineIds(lineIds)
            .groupingBy { it.lineId to it.stockUnitId }
            .fold(BigDecimal.ZERO) { acc, slice -> acc.add(slice.amount) }
        val terminalBySlice = pickRollupLookup
            .terminalPlannedBySlice(lineIds.toSet(), clientId)
            .associate { (it.deliveryOrderLineId to it.sourceStockUnitId) to it.plannedAmount }
        return reservedBySlice.mapNotNull { (key, reserved) ->
            val handled = terminalBySlice[key] ?: BigDecimal.ZERO
            val remainder = reserved.subtract(handled).max(BigDecimal.ZERO)
            if (remainder.signum() > 0) ReservedStock(key.second, remainder) else null
        }
    }

    /**
     * Single chokepoint for ORDER state changes: enforces [OrderState.canAdvanceTo],
     * fires the synchronous CDI event, and writes the outbox row.
     */
    private fun transition(order: DeliveryOrder, target: OrderState) {
        val current = OrderState.fromCode(order.state)
        if (!current.canAdvanceTo(target)) {
            throw OrderException.InvalidTransition(order.id ?: 0L, current.code, target.code)
        }
        order.state = target.code
        val event = DeliveryOrderStateChangedEvent(
            orderId = order.id!!,
            orderNumber = order.orderNumber,
            oldState = current.code,
            newState = target.code,
            clientId = order.clientId,
            occurredAt = Instant.now(),
        )
        outboxService.publish("DeliveryOrder", order.id!!, "DeliveryOrderStateChanged", event, order.clientId)
        stateChangedEvent.fire(event)
    }

    /** Line state changes share the [OrderState.canAdvanceTo] guard but emit no events (order-level only). */
    private fun lineTransition(line: DeliveryOrderLine, target: OrderState) {
        val current = OrderState.fromCode(line.state)
        if (!current.canAdvanceTo(target)) {
            throw OrderException.InvalidTransition(line.id ?: 0L, current.code, target.code)
        }
        line.state = target.code
    }

    /** Reserves the line's uncovered remainder and records the per-stock-unit slices. */
    private fun reserveLine(
        line: DeliveryOrderLine,
        strategy: com.karyo.orders.domain.model.OrderStrategy,
        correlationId: String,
        clientId: Long,
    ) {
        val remaining = line.shortage
        if (remaining.signum() <= 0) return
        val outcome = stockReserver.reserve(
            ReservationRequest(
                itemDataId = line.itemDataId,
                amount = remaining,
                lotNumber = line.lotNumber,
                useLockedStock = strategy.useLockedStock,
                preferComplete = strategy.preferComplete,
                preferMatching = strategy.preferMatching,
                completeHandling = strategy.completeHandling,
                enforceLot = strategy.enforceLot,
                correlationId = correlationId,
            ),
            clientId,
        )
        outcome.reservations.forEach { slice ->
            reservationRepository.persist(
                OrderLineReservation().apply {
                    lineId = line.id!!
                    stockUnitId = slice.stockUnitId
                    amount = slice.amount
                }
            )
        }
        val reservedNow = outcome.reservations.sumOf { it.amount }
        line.reservedAmount = line.reservedAmount.add(reservedNow)
    }

    private fun promoteIfFullyReserved(order: DeliveryOrder) {
        if (order.lines.all { it.state == OrderState.PROCESSABLE.code }) {
            transition(order, OrderState.PROCESSABLE)
        }
    }

    private fun runReleaseValidators(order: DeliveryOrder) {
        val snapshot = toResponse(order)
        val violations = releaseValidators.flatMap { it.validate(snapshot) }
        if (violations.isNotEmpty()) {
            throw OrderException.ReleaseRejected(violations)
        }
    }

    private fun findEntityById(id: Long, clientId: Long): DeliveryOrder =
        orderRepository.findByIdAndClient(id, clientId)
            ?: throw OrderException.NotFound("DeliveryOrder", "id=$id")

    /**
     * Builds the response for a single order. When [names]/[shipments]/[rollups]/[destinationNames]
     * are omitted (single-order callers like getById/create/release), the product-name, shipment,
     * pick-rollup, and destination-name lookups are computed for just this order. [list]
     * pre-computes one batched lookup across the whole page and passes all four in here to avoid
     * an N+1 (one [ProductLookup.findNamesByIds]/[ShipmentLookup.findByDeliveryOrderIds]/
     * [PickRollupLookup.pickedAmountsByLineIds]/[DestinationLocationResolver.resolveNames] call
     * per order).
     */
    private fun toResponse(
        order: DeliveryOrder,
        names: Map<Long, String>? = null,
        shipments: Map<Long, ShipmentSummary>? = null,
        rollups: Map<Long, PickRollup>? = null,
        destinationNames: Map<Long, String>? = null,
    ): DeliveryOrderResponse {
        val resolvedNames = names ?: productLookup.findNamesByIds(order.lines.map { it.itemDataId }.toSet())
        val summary = if (shipments != null) {
            shipments[order.id]
        } else {
            shipmentLookup.findByDeliveryOrderIds(setOf(order.id!!))[order.id]
        }
        val resolvedRollups = rollups
            ?: pickRollupLookup.pickedAmountsByLineIds(order.lines.mapNotNull { it.id }.toSet())
        val destinationLocationName = if (destinationNames != null) {
            order.destinationLocationId?.let { destinationNames[it] }
        } else {
            destinationLocationResolver.resolveName(order.destinationLocationId, order.clientId)
        }
        return DeliveryOrderResponse(
            id = order.id!!,
            orderNumber = order.orderNumber,
            externalNumber = order.externalNumber,
            customerName = order.customerName,
            street = order.street,
            streetNumber = order.streetNumber,
            zipCode = order.zipCode,
            city = order.city,
            country = order.country,
            phone = order.phone,
            email = order.email,
            deliveryDate = order.deliveryDate,
            prio = order.prio,
            notes = order.notes,
            state = order.state,
            stateName = OrderState.fromCode(order.state).name,
            orderStrategyId = order.orderStrategyId,
            clientId = order.clientId,
            lines = order.lines.map { toLineResponse(it, resolvedNames, resolvedRollups) },
            created = order.created.toString(),
            modified = order.modified.toString(),
            carrierName = summary?.carrierName,
            carrierService = summary?.carrierService,
            trackingNumber = summary?.trackingNumber,
            shippedAt = summary?.shippedAt?.toString(),
            pickingHint = order.pickingHint,
            packingHint = order.packingHint,
            shippingHint = order.shippingHint,
            destinationLocationId = order.destinationLocationId,
            destinationLocationName = destinationLocationName,
            operatorId = order.operatorId,
            senderName = order.senderName,
            releaseModeOverride = order.releaseModeOverride,
            streamFirstAttemptAt = order.streamFirstAttemptAt,
            streamEscalatedAt = order.streamEscalatedAt,
            streamStalledAt = order.streamStalledAt,
            documentUrl = "/api/v1/delivery-orders/${order.id}/delivery-note.pdf",
            labelUrl = summary?.shippingUnitId?.let { "/api/v1/shipping-units/$it/label.zpl" },
        )
    }

    private fun toLineResponse(
        line: DeliveryOrderLine,
        names: Map<Long, String>,
        rollups: Map<Long, PickRollup>,
    ): DeliveryOrderLineResponse {
        val rollup = rollups[line.id]
        val substituted = rollup?.substitutedAmount ?: BigDecimal.ZERO
        return DeliveryOrderLineResponse(
            id = line.id!!,
            lineNumber = line.lineNumber,
            itemDataId = line.itemDataId,
            itemDataNumber = line.itemDataNumber,
            amount = line.amount,
            reservedAmount = line.reservedAmount,
            // RAW reservation shortfall (amount - reservedAmount), deliberately NOT netted against
            // substitution. Analysis 2026-07-31 (defect-burndown-2 final gate): substitution is
            // reservation-BACKED -- `handleShortfall` releases the parent pick's unpicked
            // reservation and `coverWithFollowUps` reserves fresh stock for the substitute, all
            // within the pool this line already reserved, while `reservedAmount` itself is written
            // only by reserveLine/cancel. So picked + substituted <= reservedAmount always, and
            // this shortfall can never be "covered" by substitution -- netting it only ever
            // subtracts unrelated picking-side coverage and under-reports genuinely undelivered
            // units (ordered 10 / reserved 6 / picked 2 / substituted 4 -> 4 short, not 0).
            shortage = line.shortage,
            state = line.state,
            stateName = OrderState.fromCode(line.state).name,
            lotNumber = line.lotNumber,
            itemDataName = names[line.itemDataId],
            unitPrice = line.unitPrice,
            externalNumber = line.externalNumber,
            pickedAmount = rollup?.pickedAmount ?: BigDecimal.ZERO,
            substitutedAmount = substituted,
        )
    }

    private fun buildShortages(order: DeliveryOrder): List<LineShortage> =
        order.lines
            .filter { it.shortage.signum() > 0 }
            .map { line ->
                LineShortage(
                    lineId = line.id!!,
                    lineNumber = line.lineNumber,
                    itemDataId = line.itemDataId,
                    itemDataNumber = line.itemDataNumber,
                    requestedAmount = line.amount,
                    reservedAmount = line.reservedAmount,
                    shortfall = line.shortage,
                )
            }

    companion object {
        val SORTABLE_FIELDS = setOf("id", "orderNumber", "customerName", "state", "prio", "deliveryDate", "created")
    }
}
