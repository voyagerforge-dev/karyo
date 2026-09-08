package com.karyo.fulfillment

import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
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
import org.junit.jupiter.api.Test

/**
 * D4 batching pin: the delivery-order LIST endpoint reports the derived pick rollup for every
 * order on the page. The one-lookup-per-page property itself is pinned by code reading
 * ([com.karyo.orders.service.OrderService.list] collects line ids across the whole page and
 * makes a single `PickRollupLookup.pickedAmountsByLineIds` call, mirroring the
 * `ShipmentLookup` batching).
 */
@QuarkusTest
class PickRollupBatchingTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var entityManager: EntityManager

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
    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double) {
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
    }
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
    private fun createAndReleaseOrder(itemDataId: Long, amount: Double, customer: String): Long {
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"$customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the list endpoint reports rollups for every order on the page`() {
        seedPackStaging()
        val s = System.nanoTime()
        val marker = "BATCH-$s"
        val iu = createItemUnit("OU-$s")
        val numA = "PA-$s"; val pidA = createProduct(numA, iu)
        val numB = "PB-$s"; val pidB = createProduct(numB, iu)
        createStock(createUnitLoad("ULA-$s"), pidA, numA, 100.0)
        createStock(createUnitLoad("ULB-$s"), pidB, numB, 100.0)
        val orderA = createAndReleaseOrder(pidA, 30.0, marker)
        val orderB = createAndReleaseOrder(pidB, 20.0, marker)
        tenantContext.clientId = 1L

        listOf(orderA, orderB).forEach { orderId ->
            val pickOrder = pickOrderService.releaseToPicking(orderId).single()
            pickRepository.findByPickOrderId(pickOrder.id!!).forEach { p ->
                pickOrderService.confirmPick(p.id!!, p.plannedAmount, targetUnitLoadId = null)
            }
        }
        entityManager.clear()

        val page = given().`when`().get("/api/v1/delivery-orders?q=$marker&size=20").then().statusCode(200)
            .extract().jsonPath()
        val content = page.getList<Map<String, Any>>("content")
        assertThat(content).hasSize(2)
        val pickedByOrderId = content.associate { order ->
            @Suppress("UNCHECKED_CAST")
            val line = (order["lines"] as List<Map<String, Any>>).single()
            (order["id"] as Number).toLong() to (line["pickedAmount"] as Number?)?.toDouble()
        }
        assertThat(pickedByOrderId[orderA]).isEqualTo(30.0)
        assertThat(pickedByOrderId[orderB]).isEqualTo(20.0)
    }
}
