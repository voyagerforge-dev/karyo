package com.karyo.fulfillment

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.PickLifecycleService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.ShippingLifecycleService
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class PackingServiceNegativeTest {

    @Inject lateinit var packingService: PackingService

    @Inject lateinit var pickOrderService: PickOrderService

    @Inject lateinit var lifecycleService: PickLifecycleService

    @Inject lateinit var pickRepository: PickRepository

    @Inject lateinit var pickOrderRepository: PickOrderRepository

    @Inject lateinit var shippingLifecycleService: ShippingLifecycleService

    @Inject lateinit var entityManager: EntityManager

    @Inject lateinit var tenantContext: TenantContext

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun seedAndReleaseOrderFor60(): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "ORD-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":60.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /** Drives a released order to PICKED(600): release to picking, then confirm every pick at full amount. */
    private fun driveToPicked(orderId: Long) {
        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        po.getList<Map<String, Any>>("[0].picks").forEach { pick ->
            val amt = (pick["plannedAmount"] as Number).toDouble()
            given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
        }
    }

    /** A two-line order (own product + stock unit each) released for [amount] each, not yet picked. */
    private fun seedAndReleaseTwoLineOrder(amount: Double): Long {
        val s = System.nanoTime()
        val iuA = createItemUnit("OUA-$s"); val numA = "PA-$s"
        val pidA = createProduct(numA, iuA); val ulA = createUnitLoad("ULA-$s")
        createStock(ulA, pidA, numA, amount)
        val iuB = createItemUnit("OUB-$s"); val numB = "PB-$s"
        val pidB = createProduct(numB, iuB); val ulB = createUnitLoad("ULB-$s")
        createStock(ulB, pidB, numB, amount)
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","lines":[{"itemDataId":$pidA,"amount":$amount},
                    |{"itemDataId":$pidB,"amount":$amount}]}""".trimMargin(),
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `openPacking on a non-PICKED order is rejected`() {
        val orderId = seedAndReleaseOrderFor60()
        tenantContext.clientId = 1L
        assertThatThrownBy { packingService.openPacking(orderId) }
            .isInstanceOf(FulfillmentException.NotPackable::class.java)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `double open is rejected`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        driveToPicked(orderId)
        tenantContext.clientId = 1L
        packingService.openPacking(orderId)
        assertThatThrownBy { packingService.openPacking(orderId) }
            .isInstanceOf(FulfillmentException.NotPackable::class.java)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pack with non-positive weight is rejected`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        driveToPicked(orderId)
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        assertThatThrownBy { packingService.pack(shipment.id!!, BigDecimal.ZERO, "CARTON") }
            .isInstanceOf(FulfillmentException.InvalidPackRequest::class.java)
    }

    // Task-1-review finding #2 (adjudicated, not re-litigated): PICKED-reuse pin tests. See
    // PickLifecycleService.cancelOrder's KDoc for the ruling this pair enforces.

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a force-finished PARTIAL order (1 of 2 picks PICKED) can open packing`() {
        seedPackStaging()
        val orderId = seedAndReleaseTwoLineOrder(50.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        pickOrderService.confirmPick(picks.first().id!!, picks.first().plannedAmount, targetUnitLoadId = null)
        entityManager.clear()

        // Force-finish cancels the still-open second pick; the order lands on PICKED because one
        // pick was genuinely picked -- this IS packable by design (myWMS: pack what was picked).
        val canceled = lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false)
        assertThat(canceled.state).isEqualTo(PickState.PICKED.code)
        entityManager.clear()

        val shipment = packingService.openPacking(orderId)
        assertThat(shipment).isNotNull
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an all-canceled order (nothing ever picked) cannot open packing`() {
        seedPackStaging()
        val orderId = seedAndReleaseTwoLineOrder(50.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val canceled = lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false)
        assertThat(canceled.state).isEqualTo(PickState.CANCELED.code)
        entityManager.clear()

        assertThatThrownBy { packingService.openPacking(orderId) }
            .isInstanceOf(FulfillmentException.NotPackable::class.java)
    }

    /**
     * WORKLIST defect row "a canceled `DeliveryOrder` can coexist with open packing", filed by the
     * task-6 review and closed here (2026-07-31 final gate).
     *
     * The pair above establishes that a PARTIAL force-finish legitimately lands on PICKED and is
     * packable. Task 6 then made the delivery-order cancel cascade force-finish open pick work, so
     * that packable-PICKED PickOrder is now a ONE-CALL outcome of `POST /delivery-orders/{id}/cancel`
     * (DeliveryOrder → CANCELED, PickOrder → PICKED because one pick was genuinely picked). Packing
     * must therefore consult the DeliveryOrder's own state, not just the PickOrder's, or a canceled
     * order acquires a Shipment and can be dispatched.
     */
    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a CANCELED delivery order cannot open packing even when its pick order is PICKED`() {
        seedPackStaging()
        val orderId = seedAndReleaseTwoLineOrder(50.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        pickOrderService.confirmPick(picks.first().id!!, picks.first().plannedAmount, targetUnitLoadId = null)
        entityManager.clear()

        // The PRIMARY cancel path -- one REST call. It cascades into the pick side, force-finishing
        // the still-open second pick; one genuinely PICKED pick leaves the PickOrder on PICKED.
        given().`when`().post("/api/v1/delivery-orders/$orderId/cancel").then().statusCode(200)
            .body("stateName", `is`("CANCELED"))
        entityManager.clear()
        assertThat(pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!.state)
            .isEqualTo(PickState.PICKED.code)

        assertThatThrownBy { packingService.openPacking(orderId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
            .hasMessageContaining(orderId.toString())
            .hasMessageContaining("CANCELED")
    }

    /**
     * Task 6 (S4, outbound-completion sprint): a canceled Shipment (as opposed to a canceled
     * DeliveryOrder, the case above) must NOT permanently block packing -- cancel is meant to
     * free the order for a repack. [PackingService.openPacking]'s duplicate-shipment guard
     * ignores a shipment sitting at CANCELED.
     */
    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `openPacking succeeds again for an order whose only shipment was canceled`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        driveToPicked(orderId)
        tenantContext.clientId = 1L
        val firstShipment = packingService.openPacking(orderId)
        packingService.pack(firstShipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()

        val canceled = shippingLifecycleService.cancel(firstShipment.id!!)
        assertThat(canceled.state).isEqualTo(ShipmentState.CANCELED.code)
        entityManager.clear()

        val secondShipment = packingService.openPacking(orderId)
        assertThat(secondShipment).isNotNull
        assertThat(secondShipment.id).isNotEqualTo(firstShipment.id)
        assertThat(secondShipment.state).isEqualTo(ShipmentState.PACKING.code)
    }
}
