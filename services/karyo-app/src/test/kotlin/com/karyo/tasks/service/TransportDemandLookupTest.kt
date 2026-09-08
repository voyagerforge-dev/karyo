package com.karyo.tasks.service

import com.karyo.layout.spi.TransportDemandLookup
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Service-level tests (real beans, no mocks) for [TransportDemandLookup], the layout-api
 * READ contract implemented in tasks-core (`DefaultTransportDemandLookup`) that the location
 * finder's client-mixing / item-mixing passes use to see other owners' in-flight transport
 * demand targeting a candidate location.
 *
 * Fixture idiom from [TransportOrderReplenishmentRepositoryTest]: persist [TransportOrder]
 * directly via the repository inside the test's transaction; location ids are nanoTime-derived
 * so rows left behind by other tests on the shared DB can never collide with ours.
 */
@QuarkusTest
class TransportDemandLookupTest {

    @Inject
    lateinit var transportDemandLookup: TransportDemandLookup

    @Inject
    lateinit var transportOrderRepository: TransportOrderRepository

    private fun uniqueLocationId(): Long = System.nanoTime()

    private fun newOrder(destinationLocationId: Long, state: Int, clientId: Long, itemDataId: Long? = null) =
        TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "TDL-test-$destinationLocationId-$state-${System.nanoTime()}"
            transportType = TransportType.MOVE
            unitLoadId = 1
            unitLoadLabel = "UL1"
            sourceLocationId = 10
            sourceLocationName = "SRC-1"
            this.destinationLocationId = destinationLocationId
            destinationLocationName = "DEST-1"
            this.itemDataId = itemDataId
            this.state = state
        }

    /**
     * Row :1614's PUTAWAY/TRANSFER shape: while open, these orders carry ONLY
     * [suggestedLocationId] -- [destinationLocationId] is stamped only at completion (see
     * `TaskService.createPutawayTask` / `ChainContinuationService.maybeChain` vs.
     * `TaskService.complete`). Fixture variant so the demand query's coalesced predicate has
     * something to match on the suggested-only shape.
     */
    private fun newPutawayOrder(suggestedLocationId: Long, state: Int, clientId: Long, itemDataId: Long? = null) =
        TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "TDL-putaway-$suggestedLocationId-$state-${System.nanoTime()}"
            transportType = TransportType.PUTAWAY
            unitLoadId = 1
            unitLoadLabel = "UL1"
            sourceLocationId = 10
            sourceLocationName = "SRC-1"
            this.suggestedLocationId = suggestedLocationId
            suggestedLocationName = "SUGGEST-1"
            this.itemDataId = itemDataId
            this.state = state
        }

    @Test
    @Transactional
    fun `open transports targeting the queried locations are returned with client and item`() {
        val locationId = uniqueLocationId()
        transportOrderRepository.persist(
            newOrder(locationId, OrderState.RELEASED.code, clientId = 1L, itemDataId = 42L),
        )

        val demand = transportDemandLookup.openDemandByLocationIds(setOf(locationId))

        assertThat(demand).hasSize(1)
        assertThat(demand.first().locationId).isEqualTo(locationId)
        assertThat(demand.first().clientId).isEqualTo(1L)
        assertThat(demand.first().itemDataId).isEqualTo(42L)
    }

    @Test
    @Transactional
    fun `FINISHED and CANCELED transports are not demand`() {
        val locationId = uniqueLocationId()
        transportOrderRepository.persist(newOrder(locationId, OrderState.FINISHED.code, clientId = 1L))
        transportOrderRepository.persist(newOrder(locationId, OrderState.CANCELED.code, clientId = 1L))

        val demand = transportDemandLookup.openDemandByLocationIds(setOf(locationId))

        assertThat(demand).isEmpty()
    }

    @Test
    @Transactional
    fun `transports targeting other locations are not returned`() {
        val queriedLocationId = uniqueLocationId()
        val otherLocationId = uniqueLocationId()
        transportOrderRepository.persist(newOrder(otherLocationId, OrderState.RELEASED.code, clientId = 1L))

        val demand = transportDemandLookup.openDemandByLocationIds(setOf(queriedLocationId))

        assertThat(demand).isEmpty()
    }

    @Test
    @Transactional
    fun `demand from another client IS visible - the lookup is deliberately unscoped`() {
        val locationId = uniqueLocationId()
        transportOrderRepository.persist(newOrder(locationId, OrderState.RELEASED.code, clientId = 999L))

        val demand = transportDemandLookup.openDemandByLocationIds(setOf(locationId))

        assertThat(demand).hasSize(1)
        assertThat(demand.first().clientId).isEqualTo(999L)
    }

    @Test
    @Transactional
    fun `open PUTAWAY order with only suggestedLocationId is returned as demand for that location`() {
        val locationId = uniqueLocationId()
        val order = newPutawayOrder(locationId, OrderState.RELEASED.code, clientId = 1L, itemDataId = 42L)
        transportOrderRepository.persist(order)

        val demand = transportDemandLookup.openDemandByLocationIds(setOf(locationId))

        assertThat(demand).hasSize(1)
        assertThat(demand.first().locationId).isEqualTo(locationId)
        assertThat(demand.first().clientId).isEqualTo(1L)
        assertThat(demand.first().itemDataId).isEqualTo(42L)
        assertThat(demand.first().transportOrderId).isEqualTo(order.id)
    }
}
