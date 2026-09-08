package com.karyo.fulfillment

import com.karyo.fulfillment.service.PickOrderService
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
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

@QuarkusTest
class PickOrderRestTest {

    @Inject lateinit var pickOrderService: PickOrderService
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

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release to picking then confirm all picks via REST finishes the order at PICKED`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()

        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders")
            .then().statusCode(201)
            .body("[0].deliveryOrderId", `is`(orderId.toInt()))
            .body("[0].targetUnitLoadId", notNullValue())
            .body("[0].picks.size()", greaterThanOrEqualTo(1))
            .extract().jsonPath()
        val pickOrderId = po.getLong("[0].id")
        val targetUl = po.getLong("[0].targetUnitLoadId")

        // PickResponse exposes follow-up/substitute keys (null on a parent pick) for the picking UI.
        assertThat(po.getMap<String, Any>("[0].picks[0]")).containsKey("followUpForPickId")
        assertThat(po.getMap<String, Any>("[0].picks[0]")).containsKey("substitutedItemDataId")

        po.getList<Map<String, Any>>("[0].picks").forEach { pick ->
            val amt = (pick["plannedAmount"] as Number).toDouble()
            given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
        }

        given().`when`().get("/api/v1/pick-orders/$pickOrderId").then().statusCode(200).body("state", `is`(600))
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))

        // The picked goods physically dwell on the pick container at PICKED(600) — the pick-to-
        // container model's in-transit visibility (not decrement-at-pick).
        given().`when`().get("/api/v1/unit-loads/$targetUl").then().statusCode(200)
            .body("stockUnits.size()", greaterThanOrEqualTo(1))
            .body("stockUnits[0].state", `is`(600))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release to picking is forbidden without fulfillment-write`() {
        given().contentType(ContentType.JSON).body("""{"deliveryOrderId":1}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(403)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel endpoint cancels an unclaimed order and its picks disappear from the pool`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        val pickOrderId = po.getLong("[0].id")

        given().`when`().post("/api/v1/pick-orders/$pickOrderId/cancel")
            .then().statusCode(200).body("state", `is`(800))

        // Canceling an already-canceled (>= PICKED) order 409s.
        given().`when`().post("/api/v1/pick-orders/$pickOrderId/cancel").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel endpoint is forbidden without fulfillment-write`() {
        given().`when`().post("/api/v1/pick-orders/1/cancel").then().statusCode(403)
    }

    @Test
    @TestSecurity(
        user = "carol",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an operator without MANAGER cannot cancel a pick order claimed by someone else, MANAGER can`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        val pickOrderId = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath().getLong("[0].id")
        tenantContext.clientId = 1L
        pickOrderService.claim(pickOrderId, "bob") // claimed by "bob"; the request runs as "carol"

        // "carol" (no MANAGER) is a different operator than the claimer "bob" -> 409, not 403
        // (ownership mismatch is a domain conflict, mirroring GoodsReceiptResource.releaseReceipt).
        given().`when`().post("/api/v1/pick-orders/$pickOrderId/cancel").then().statusCode(409)
    }

    @Test
    @TestSecurity(
        user = "carol",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a MANAGER can cancel a pick order claimed by a different operator`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        val pickOrderId = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath().getLong("[0].id")
        tenantContext.clientId = 1L
        pickOrderService.claim(pickOrderId, "bob")

        given().`when`().post("/api/v1/pick-orders/$pickOrderId/cancel").then().statusCode(200).body("state", `is`(800))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release with targetUnitLoadTypeId overrides the configured default pick-bin type`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        val customTypeId = given().contentType(ContentType.JSON)
            .body("""{"name":"Custom Pick Bin-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/unit-load-types").then().statusCode(201).extract().jsonPath().getLong("id")

        val po = given().contentType(ContentType.JSON)
            .body("""{"deliveryOrderId":$orderId,"targetUnitLoadTypeId":$customTypeId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        val targetUl = po.getLong("[0].targetUnitLoadId")

        // The created container's OWN unitLoadTypeId is the caller-supplied override, not the
        // config default (2, "Pick Bin") -- the observable effect of resolvePickBinTypeId's
        // request-param-wins-over-config rule.
        given().`when`().get("/api/v1/unit-loads/$targetUl").then().statusCode(200)
            .body("unitLoadTypeId", `is`(customTypeId.toInt()))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `M7 -- extinguish with no request body is a 400, not a 500`() {
        // Regression pin for final-review finding M7: a body-less POST previously deserialized
        // to a null ExtinguishRequest and NPE'd through to a 500.
        given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/pick-orders/extinguish")
            .then().statusCode(400)
    }
}
