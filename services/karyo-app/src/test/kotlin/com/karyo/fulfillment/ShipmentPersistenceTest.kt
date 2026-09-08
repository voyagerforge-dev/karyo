package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class ShipmentPersistenceTest {
    @Inject
    lateinit var shipmentRepository: ShipmentRepository

    @Inject
    lateinit var shippingUnitRepository: ShippingUnitRepository

    @Test
    @Transactional
    fun `persist a shipment with a shipping unit and line`() {
        // deliveryOrderId is intentionally NOT a small literal -- see the identical note in
        // PickOrderPersistenceTest. A hardcoded 10L here would let a later, unrelated test's
        // freshly-created order (if it happens to be assigned id 10 on the shared Testcontainers
        // DB) collide with this row: PackingService.openPacking's
        // `shipmentRepository.findByDeliveryOrderId` would then find THIS leftover shipment and
        // 409 with "a shipment already exists" for an order that never actually had one.
        val deliveryOrderId = System.nanoTime()
        val shp = Shipment().apply {
            clientId = 1L
            shipmentNumber = "SHP-TEST-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = ShipmentState.PACKING.code
        }
        shipmentRepository.persist(shp)

        val su = ShippingUnit().apply {
            clientId = 1L
            shipmentId = shp.id!!
            shippingUnitNumber = "SU-TEST-${System.nanoTime()}"
            type = "CARTON"
            weight = BigDecimal("2.500")
            state = ShipmentState.PACKED.code
            unitLoadId = 5L
        }
        shippingUnitRepository.persist(su)

        val line = ShippingUnitLine().apply {
            clientId = 1L
            shippingUnitId = su.id!!
            itemDataId = 7L
            itemDataNumber = "SKU-7"
            amount = BigDecimal("40.0000")
            sourcePickId = 99L
            lotNumber = "L1"
        }
        shippingUnitRepository.persistLine(line)

        assertThat(shipmentRepository.findByIdAndClient(shp.id!!, 1L)).isNotNull
        assertThat(shippingUnitRepository.findByShipmentId(shp.id!!)).hasSize(1)
        assertThat(shippingUnitRepository.findLinesByUnitId(su.id!!)).hasSize(1)
    }

    /**
     * Row 2 (defect-tail-2, 2026-08-17): before the fix, [ShipmentRepository.findByDeliveryOrderId]
     * had no `ORDER BY`, so once a delivery order accumulated more than one `Shipment` row (S4:
     * repeated cancel/reopen cycles leave one CANCELED row per prior attempt plus the current live
     * one), `firstResult()` could return either row depending on incidental database row order.
     * [PackingService.openPacking]'s duplicate-shipment guard needs the LATEST row specifically --
     * it must see a CANCELED state as "the order is free to pack again", never a stale CANCELED
     * row from an earlier attempt when a later live shipment already exists. Persists the older
     * CANCELED row FIRST and the newer live row SECOND (ids increase with insertion order), so a
     * finder without an `ORDER BY id DESC` has a real chance of returning the wrong one; this test
     * fails intermittently (row-order dependent) against the pre-fix code and passes deterministically
     * under the fix.
     */
    @Test
    @Transactional
    fun `findByDeliveryOrderId returns the latest shipment when more than one exists`() {
        val deliveryOrderId = System.nanoTime()
        val older = Shipment().apply {
            clientId = 1L
            shipmentNumber = "SHP-TEST-OLD-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = ShipmentState.CANCELED.code
        }
        shipmentRepository.persist(older)

        val newer = Shipment().apply {
            clientId = 1L
            shipmentNumber = "SHP-TEST-NEW-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = ShipmentState.PACKING.code
        }
        shipmentRepository.persist(newer)

        val found = shipmentRepository.findByDeliveryOrderId(deliveryOrderId, 1L)
        assertThat(found).isNotNull
        assertThat(found!!.id)
            .`as`("must return the newer, live shipment, not the older CANCELED one")
            .isEqualTo(newer.id)
        assertThat(found.state)
            .`as`("the returned row's state must be the current one, not a stale CANCELED row")
            .isEqualTo(ShipmentState.PACKING.code)
    }
}
