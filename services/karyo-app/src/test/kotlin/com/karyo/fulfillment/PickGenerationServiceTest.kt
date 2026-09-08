package com.karyo.fulfillment

import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

@QuarkusTest
class PickGenerationServiceTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickRepository: PickRepository
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
    /** Seeds a product with [stockAmount] on a single stock unit, then creates + releases an order for [orderAmount]. */
    private fun seedAndReleaseOrder(stockAmount: Double, orderAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "ORD-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, stockAmount)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":$orderAmount}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    private fun seedAndReleaseOrderFor60(): Long = seedAndReleaseOrder(stockAmount = 100.0, orderAmount = 60.0)

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releaseToPicking creates a PickOrder with picks and advances the order to STARTED`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()

        assertThat(pickOrder.targetUnitLoadId).isNotNull
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picks).isNotEmpty
        assertThat(picks.sumOf { it.plannedAmount.toDouble() }).isEqualTo(60.0)
        assertThat(picks.all { it.state == PickState.RELEASED.code }).isTrue
        // 60 of 100 available -> a partial draw from the source unit -> PICK.
        assertThat(picks.all { it.pickingType == PickingType.PICK.name }).isTrue

        // Order advanced to STARTED(500)
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(500))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releasing an order that is already in picking is rejected as NotReleasable`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        tenantContext.clientId = 1L
        pickOrderService.releaseToPicking(orderId).single() // advances the order to STARTED(500)

        org.assertj.core.api.Assertions.assertThatThrownBy { pickOrderService.releaseToPicking(orderId).single() }
            .isInstanceOf(com.karyo.fulfillment.exception.FulfillmentException.NotReleasable::class.java)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a pick that takes the whole source stock unit is labeled COMPLETE`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrder(stockAmount = 100.0, orderAmount = 100.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()

        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        // Order amount == the single source unit's full amount -> a whole-unit move -> COMPLETE.
        assertThat(picks.sumOf { it.plannedAmount.toDouble() }).isEqualTo(100.0)
        assertThat(picks.all { it.pickingType == PickingType.COMPLETE.name }).isTrue
    }
}
