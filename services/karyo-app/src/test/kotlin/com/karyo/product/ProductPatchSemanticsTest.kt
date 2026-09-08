package com.karyo.product

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
 * Real-DB, real-service proof of D2 tri-state PATCH semantics on
 * `UpdateProductRequest.description` / `.defaultPackagingUnitId`. Unlike
 * [com.karyo.product.api.v1.ProductResourceTest] (which mocks `ProductService`), this hits
 * the real `ProductService.updateProduct` + Postgres round-trip.
 *
 * `explicit null clears a previously-set description` and
 * `explicit null clears a previously-set defaultPackagingUnitId` are the previously-
 * impossible operations this task fixes: against the pre-D2 `String?`/`Long?` + `?.let`
 * merge, sending an explicit JSON `null` left the field unchanged -- these were written and
 * run red against that code before the two fields were switched to `Patchable<T>`.
 */
@QuarkusTest
class ProductPatchSemanticsTest {

    /** Item-unit `name` is capped at 20 chars server-side -- keep generated names short. */
    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProductWithDescription(itemUnitId: Long, description: String): Long {
        val suffix = System.nanoTime()
        return given().contentType(ContentType.JSON)
            .body(
                """{"number":"PATCH-SKU-$suffix","name":"Patch Semantics Product",""" +
                    """"description":"$description","itemUnitId":$itemUnitId}""",
            )
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun addPackagingUnit(productId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"Box of 12","amount":12}""")
            .`when`().post("/api/v1/products/$productId/packaging-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `explicit null clears a previously-set description`() {
        val itemUnitId = createItemUnit("PDU1-${System.nanoTime().toString().takeLast(8)}")
        val pid = createProductWithDescription(itemUnitId, "original description")

        given().contentType(ContentType.JSON)
            .body("""{"description":null}""")
            .`when`().put("/api/v1/products/$pid")
            .then().statusCode(200)

        given().`when`().get("/api/v1/products/$pid")
            .then().statusCode(200)
            .body("description", `is`(nullValue()))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `omitting description leaves it unchanged`() {
        val itemUnitId = createItemUnit("PDU2-${System.nanoTime().toString().takeLast(8)}")
        val pid = createProductWithDescription(itemUnitId, "original description")

        given().contentType(ContentType.JSON)
            .body("""{"name":"Renamed Product"}""")
            .`when`().put("/api/v1/products/$pid")
            .then().statusCode(200)

        given().`when`().get("/api/v1/products/$pid")
            .then().statusCode(200)
            .body("name", `is`("Renamed Product"))
            .body("description", `is`("original description"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `explicit null clears a previously-set defaultPackagingUnitId`() {
        val itemUnitId = createItemUnit("PDU3-${System.nanoTime().toString().takeLast(8)}")
        val pid = createProductWithDescription(itemUnitId, "has a packaging unit")
        val puId = addPackagingUnit(pid)

        given().contentType(ContentType.JSON)
            .body("""{"defaultPackagingUnitId":$puId}""")
            .`when`().put("/api/v1/products/$pid")
            .then().statusCode(200)
            .body("defaultPackagingUnitId", `is`(puId.toInt()))

        given().contentType(ContentType.JSON)
            .body("""{"defaultPackagingUnitId":null}""")
            .`when`().put("/api/v1/products/$pid")
            .then().statusCode(200)

        given().`when`().get("/api/v1/products/$pid")
            .then().statusCode(200)
            .body("defaultPackagingUnitId", `is`(nullValue()))
    }

    /**
     * Review-fix round (@Size restoration, 2026-07-25): before
     * [com.karyo.common.patch.PatchableSize] was wired onto `description`, this request reached
     * `ProductService.updateProduct` and Hibernate/Postgres with an oversized value --
     * `description` is `varchar(2000)`, so a 3000-char value overflowed the column and the
     * request 500'd. Written and run RED against the pre-fix DTO (500, not 400) before
     * `@field:PatchableSize(max = 2000)` was added to `description`.
     */
    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an oversized description value returns 400, not 500`() {
        val itemUnitId = createItemUnit("PDU4-${System.nanoTime().toString().takeLast(8)}")
        val pid = createProductWithDescription(itemUnitId, "original description")
        val oversized = "x".repeat(3000)

        given().contentType(ContentType.JSON)
            .body("""{"description":"$oversized"}""")
            .`when`().put("/api/v1/products/$pid")
            .then().statusCode(400)
    }
}
