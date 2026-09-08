package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class PickOrderPersistenceTest {

    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository

    @Test
    @Transactional
    fun `persist a PickOrder with one Pick and read it back`() {
        // deliveryOrderId is intentionally NOT a small literal: this row is persisted directly
        // (bypassing REST) and lives on for the rest of the shared Testcontainers DB/JVM. A
        // small hardcoded id (e.g. 10L) can collide with a REAL delivery-order id assigned
        // later in the same run by another test class, making PackingService.openPacking's
        // `pickOrderRepository.findByDeliveryOrderId` match this stale CREATED-state row
        // instead of (or ambiguously alongside) the real PICKED one -- see ShipmentDocumentRestTest's
        // "packet list renders after packing..." 409 flake when the full fulfillment package runs
        // together. System.nanoTime() is far outside the identity-sequence range, so it can't
        // collide with any real generated id.
        val deliveryOrderId = System.nanoTime()
        val po = PickOrder().apply {
            clientId = 1L
            pickOrderNumber = "PO-TEST-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = PickState.CREATED.code
        }
        pickOrderRepository.persist(po)

        val pick = Pick().apply {
            clientId = 1L
            pickOrderId = po.id!!
            deliveryOrderLineId = 20L
            itemDataId = 30L
            itemDataNumber = "SKU-30"
            sourceStockUnitId = 40L
            plannedAmount = BigDecimal("12.000")
            state = PickState.CREATED.code
            pickingType = PickingType.PICK.name
        }
        pickRepository.persist(pick)

        assertThat(pickRepository.findByPickOrderId(po.id!!)).hasSize(1)
        assertThat(pickOrderRepository.findByIdAndClient(po.id!!, 1L)).isNotNull
    }
}
