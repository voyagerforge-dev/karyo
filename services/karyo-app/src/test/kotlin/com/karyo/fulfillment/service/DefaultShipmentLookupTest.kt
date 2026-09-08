package com.karyo.fulfillment.service

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.spi.ShipmentLookup
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Integration test (REAL beans) for [ShipmentLookup.findByDeliveryOrderIds] -- the batch
 * read seam backing carrier/service/tracking on [com.karyo.orders.dto.DeliveryOrderResponse].
 * Tenant-scoped exactly like [com.karyo.product.spi.ProductLookup.findNamesByIds]: unknown
 * ids and foreign-tenant ids are simply absent from the result map (honest gap).
 */
@QuarkusTest
class DefaultShipmentLookupTest {

    @Inject
    lateinit var shipmentLookup: ShipmentLookup

    @Inject
    lateinit var shipmentRepository: ShipmentRepository

    @Inject
    lateinit var shipmentOrderRepository: ShipmentOrderRepository

    @Inject
    lateinit var tenantContext: TenantContext

    @Transactional
    fun persistShipment(deliveryOrderId: Long, clientId: Long, carrierName: String? = "UPS"): Long {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-LOOKUP-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = ShipmentState.SHIPPED.code
            this.carrierName = carrierName
            carrierService = "GROUND"
            trackingNumber = "1Z999AA1${System.nanoTime()}"
            shippedAt = Instant.now()
        }
        shipmentRepository.persist(shp)
        return shp.id!!
    }

    /** Sprint C: a GROUP shipment -- [Shipment.deliveryOrderId] null, members in `shipment_orders`. */
    @Transactional
    fun persistGroupShipment(consolidationGroupId: Long, clientId: Long, memberOrderIds: List<Long>): Long {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-GROUP-${System.nanoTime()}"
            this.consolidationGroupId = consolidationGroupId
            state = ShipmentState.PACKED.code
            carrierName = "UPS"
            carrierService = "GROUND"
            trackingNumber = "1Z999GRP${System.nanoTime()}"
        }
        shipmentRepository.persist(shp)
        memberOrderIds.forEach { orderId ->
            val so = ShipmentOrder().apply {
                this.clientId = clientId
                this.shipmentId = shp.id!!
                this.deliveryOrderId = orderId
                this.deliveryOrderNumber = "ORD-$orderId"
            }
            shipmentOrderRepository.persist(so)
        }
        return shp.id!!
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8801"), Claim(key = "tenant_code", value = "ACME")])
    fun `findByDeliveryOrderIds resolves a group shipment's members alongside a per-order shipment`() {
        val clientId = 8801L
        // A fresh group id per run: fulfillment V613 allows only ONE live shipment per
        // consolidation group and the index is not tenant-scoped (a group id is globally unique),
        // so a literal here would collide with GroupShipmentModelIT's fixture on the shared DB.
        persistGroupShipment(consolidationGroupId = System.nanoTime(), clientId = clientId, memberOrderIds = listOf(501L, 502L))
        persistShipment(503L, clientId = clientId, carrierName = "FedEx")

        tenantContext.clientId = clientId
        val result = shipmentLookup.findByDeliveryOrderIds(setOf(501L, 502L, 503L))

        assertThat(result.keys).containsExactlyInAnyOrder(501L, 502L, 503L)
        assertThat(result[501L]!!.carrierName).isEqualTo("UPS")
        assertThat(result[501L]!!.state).isEqualTo(ShipmentState.PACKED.code)
        assertThat(result[502L]!!.carrierName).isEqualTo("UPS")
        assertThat(result[502L]!!.state).isEqualTo(ShipmentState.PACKED.code)
        assertThat(result[503L]!!.carrierName).isEqualTo("FedEx")
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findByDeliveryOrderIds returns summary for own-tenant ids, omits unknown and foreign-tenant ids`() {
        val base = System.nanoTime()
        val ownOrderId = base
        val foreignOrderId = base + 1
        val unknownOrderId = base + 2

        persistShipment(ownOrderId, clientId = 1L, carrierName = "UPS")
        persistShipment(foreignOrderId, clientId = 999L, carrierName = "FedEx")

        tenantContext.clientId = 1L
        val result = shipmentLookup.findByDeliveryOrderIds(setOf(ownOrderId, foreignOrderId, unknownOrderId))

        assertThat(result).containsKey(ownOrderId)
        assertThat(result[ownOrderId]!!.carrierName).isEqualTo("UPS")
        assertThat(result[ownOrderId]!!.carrierService).isEqualTo("GROUND")
        assertThat(result[ownOrderId]!!.trackingNumber).isNotBlank()
        assertThat(result[ownOrderId]!!.stateName).isEqualTo("SHIPPED")
        assertThat(result[ownOrderId]!!.state).isEqualTo(ShipmentState.SHIPPED.code)
        assertThat(result).doesNotContainKey(foreignOrderId)
        assertThat(result).doesNotContainKey(unknownOrderId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findByDeliveryOrderIds returns empty map for empty input`() {
        tenantContext.clientId = 1L
        assertThat(shipmentLookup.findByDeliveryOrderIds(emptySet())).isEmpty()
    }
}
