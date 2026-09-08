package com.karyo.tasks

import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull

@QuarkusTest
class TransportOrderReplenishmentRepositoryTest {
    @Inject lateinit var repository: TransportOrderRepository

    private fun newOrder(fixId: Long, state: Int, clientId: Long) = TransportOrder().apply {
        this.clientId = clientId
        orderNumber = "RP-test-$fixId-$state"
        transportType = TransportType.REPLENISH
        unitLoadId = 1
        unitLoadLabel = "UL1"
        sourceLocationId = 10; sourceLocationName = "RES-1"
        destinationLocationId = 20; destinationLocationName = "PICK-1"
        fixAssignmentId = fixId
        this.state = state
    }

    @Test
    @Transactional
    fun `finds an open replenishment task for a fix assignment`() {
        val clientId = 9001L
        repository.persist(newOrder(fixId = 555, state = OrderState.RELEASED.code, clientId = clientId))
        val found = repository.findOpenReplenishment(555, clientId)
        assertThat(found).isNotNull
        assertThat(found!!.fixAssignmentId).isEqualTo(555)
    }

    @Test
    @Transactional
    fun `ignores finished and other-tenant replenishment tasks`() {
        val clientId = 9002L
        repository.persist(newOrder(fixId = 777, state = OrderState.FINISHED.code, clientId = clientId))
        assertNull(repository.findOpenReplenishment(777, clientId))      // finished is not open
        assertNull(repository.findOpenReplenishment(777, 9999))          // wrong tenant
    }

    // ── R12b (Task 6): findOpenAreaReplenishment ─────────────────────────────

    private fun newAreaOrder(areaId: Long, state: Int, clientId: Long) = TransportOrder().apply {
        this.clientId = clientId
        orderNumber = "RP-area-test-$areaId-$state"
        transportType = TransportType.REPLENISH
        unitLoadId = 1
        unitLoadLabel = "UL1"
        sourceLocationId = 10; sourceLocationName = "OUTSIDE-1"
        destinationLocationId = 20; destinationLocationName = "AREA-LOC-1"
        itemDataAreaId = areaId
        this.state = state
    }

    @Test
    @Transactional
    fun `finds an open area replenishment task for an item data area`() {
        val clientId = 9003L
        repository.persist(newAreaOrder(areaId = 555, state = OrderState.RELEASED.code, clientId = clientId))
        val found = repository.findOpenAreaReplenishment(555, clientId)
        assertThat(found).isNotNull
        assertThat(found!!.itemDataAreaId).isEqualTo(555)
    }

    @Test
    @Transactional
    fun `ignores finished and other-tenant area replenishment tasks`() {
        val clientId = 9004L
        repository.persist(newAreaOrder(areaId = 888, state = OrderState.FINISHED.code, clientId = clientId))
        assertNull(repository.findOpenAreaReplenishment(888, clientId))  // finished is not open
        assertNull(repository.findOpenAreaReplenishment(888, 9998))      // wrong tenant
    }
}
