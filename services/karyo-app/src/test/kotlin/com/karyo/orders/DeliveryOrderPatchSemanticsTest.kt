package com.karyo.orders

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Real-DB, real-service proof of D2 tri-state PATCH semantics on the five
 * `UpdateDeliveryOrderRequest` fields (notes/pickingHint/packingHint/shippingHint/
 * externalNumber). Unlike [com.karyo.orders.api.v1.DeliveryOrderResourceTest] (which mocks
 * `OrderService`), this hits the real `OrderService.update` + Postgres round-trip, so it
 * proves the business behavior, not just deserialization shape.
 *
 * `explicit null clears a previously-set notes field` is the previously-impossible operation
 * this task fixes: against the pre-D2 `String?` + `?.let` merge, sending `"notes": null`
 * left the field unchanged (indistinguishable from omitting it) -- this test was written
 * and run red against that code before `UpdateDeliveryOrderRequest.notes` was switched to
 * `Patchable<String>`.
 */
@QuarkusTest
class DeliveryOrderPatchSemanticsTest {

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Patch Semantics Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a CREATED-state order with notes/pickingHint/externalNumber pre-populated. */
    private fun createOrderWithHints(): Long {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("PATCH-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("PATCH-SKU-$suffix", itemUnitId)
        return given().contentType(ContentType.JSON).body(
            """{"customerName":"Patch Co","notes":"original notes",""" +
                """"pickingHint":"original picking hint","externalNumber":"EXT-ORIG",""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `explicit null clears a previously-set notes field`() {
        val id = createOrderWithHints()

        given().contentType(ContentType.JSON)
            .body("""{"notes":null}""")
            .`when`().put("/api/v1/delivery-orders/$id")
            .then().statusCode(200)

        given().`when`().get("/api/v1/delivery-orders/$id")
            .then().statusCode(200)
            .body("notes", `is`(nullValue()))
            // Sibling field omitted from the PUT body must stay untouched.
            .body("pickingHint", `is`("original picking hint"))
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `omitting a field leaves its previous value unchanged`() {
        val id = createOrderWithHints()

        given().contentType(ContentType.JSON)
            .body("""{"customerName":"Patch Co Renamed"}""")
            .`when`().put("/api/v1/delivery-orders/$id")
            .then().statusCode(200)

        given().`when`().get("/api/v1/delivery-orders/$id")
            .then().statusCode(200)
            .body("customerName", `is`("Patch Co Renamed"))
            .body("notes", `is`("original notes"))
            .body("pickingHint", `is`("original picking hint"))
            .body("externalNumber", `is`("EXT-ORIG"))
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a supplied value sets the field`() {
        val id = createOrderWithHints()

        given().contentType(ContentType.JSON)
            .body("""{"pickingHint":"handle with care"}""")
            .`when`().put("/api/v1/delivery-orders/$id")
            .then().statusCode(200)

        given().`when`().get("/api/v1/delivery-orders/$id")
            .then().statusCode(200)
            .body("pickingHint", `is`("handle with care"))
            .body("notes", `is`("original notes"))
    }

    /**
     * Review-fix round (@Size restoration, 2026-07-25): before [com.karyo.common.patch.PatchableSize]
     * was wired onto `notes`, this request reached `OrderService.update` and Hibernate/Postgres
     * with an oversized value -- `notes` is `varchar(2000)`, so a 3000-char value overflowed the
     * column and the request 500'd. Written and run RED against the pre-fix DTO (500, not 400)
     * before `@field:PatchableSize(max = 2000)` was added to `notes`.
     */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an oversized notes value returns 400, not 500`() {
        val id = createOrderWithHints()
        val oversized = "x".repeat(3000)

        given().contentType(ContentType.JSON)
            .body("""{"notes":"$oversized"}""")
            .`when`().put("/api/v1/delivery-orders/$id")
            .then().statusCode(400)
    }

    /** Same oversized-value proof for `externalNumber` (limit 100, distinct from notes' 2000). */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an oversized externalNumber value returns 400, not 500`() {
        val id = createOrderWithHints()
        val oversized = "y".repeat(200)

        given().contentType(ContentType.JSON)
            .body("""{"externalNumber":"$oversized"}""")
            .`when`().put("/api/v1/delivery-orders/$id")
            .then().statusCode(400)
    }
}
