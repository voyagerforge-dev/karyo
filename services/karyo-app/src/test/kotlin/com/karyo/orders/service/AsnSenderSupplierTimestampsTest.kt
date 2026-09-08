package com.karyo.orders.service

import com.karyo.orders.repository.AsnRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Integration test (REAL beans, worklist B1) for the ASN `senderName` / `supplierName`
 * API surface and the `started`/`finished` lifecycle timestamps stamped in
 * [AsnService].
 *
 * The REST layer is exercised end-to-end (the mock-based [com.karyo.orders.api.v1.AsnResourceTest]
 * proves serialization only); timestamp assertions read the entity via [AsnRepository]
 * because — mirroring [DeliveryOrder][com.karyo.orders.domain.model.DeliveryOrder] —
 * `started`/`finished` are internal lifecycle stamps, not response fields.
 *
 * Stamping rule mirrored from [OrderService]: `started` on the transition to
 * RELEASED, `finished` on reaching a terminal state (OrderService stamps it in
 * `cancel()`; the ASN's terminal FINISHED path stamps it too). Idempotency comes
 * from the forward-only state machine, not a null-guard: a repeat transition is a
 * 409 before any stamp is touched.
 */
@QuarkusTest
class AsnSenderSupplierTimestampsTest {

    @Inject
    lateinit var asnRepository: AsnRepository

    @Inject
    lateinit var entityManager: EntityManager

    // ── REST seeding helpers (pattern from OrderProgressionStartedTest) ──────

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"ASN B1 Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun seedProduct(suffix: String): Long {
        val itemUnitId = createItemUnit("AB1-$suffix")
        return createProduct("ASN-B1-SKU-$suffix", itemUnitId)
    }

    private fun createAsn(productId: Long, extraFields: String = ""): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{$extraFields"lines":[{"itemDataId":$productId,"expectedAmount":10}]}""")
            .`when`().post("/api/v1/asns")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Fresh-from-DB read: clears the test session's first-level cache so REST-side mutations are visible. */
    private fun findAsn(id: Long): com.karyo.orders.domain.model.Asn {
        entityManager.clear()
        return asnRepository.findById(id)!!
    }

    // ── senderName + supplierName round-trips ────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create round-trips supplierName and senderName`() {
        val productId = seedProduct(System.nanoTime().toString().takeLast(6))

        val id = given()
            .contentType(ContentType.JSON)
            .body(
                """{"supplierName":"Northwind Traders","senderName":"3PL Dispatch BV",""" +
                    """"lines":[{"itemDataId":$productId,"expectedAmount":10}]}"""
            )
            .`when`().post("/api/v1/asns")
            .then()
            .statusCode(201)
            .body("supplierName", `is`("Northwind Traders"))
            .body("senderName", `is`("3PL Dispatch BV"))
            .extract().jsonPath().getLong("id")

        given()
            .`when`().get("/api/v1/asns/$id")
            .then()
            .statusCode(200)
            .body("supplierName", `is`("Northwind Traders"))
            .body("senderName", `is`("3PL Dispatch BV"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update sets supplierName and senderName while CREATED`() {
        val productId = seedProduct(System.nanoTime().toString().takeLast(6))
        val id = createAsn(productId)

        given()
            .`when`().get("/api/v1/asns/$id")
            .then().statusCode(200)
            .body("supplierName", nullValue())
            .body("senderName", nullValue())

        given()
            .contentType(ContentType.JSON)
            .body("""{"supplierName":"Acme Distribution","senderName":"Contoso Freight"}""")
            .`when`().put("/api/v1/asns/$id")
            .then()
            .statusCode(200)
            .body("supplierName", `is`("Acme Distribution"))
            .body("senderName", `is`("Contoso Freight"))

        given()
            .`when`().get("/api/v1/asns/$id")
            .then().statusCode(200)
            .body("supplierName", `is`("Acme Distribution"))
            .body("senderName", `is`("Contoso Freight"))
    }

    // ── started / finished stamping (rule mirrored from OrderService) ────────

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release stamps started once - a repeat release is 409 and does not touch the stamp`() {
        val productId = seedProduct(System.nanoTime().toString().takeLast(6))
        val id = createAsn(productId)

        assertThat(findAsn(id).started).isNull()

        given().`when`().post("/api/v1/asns/$id/release").then().statusCode(200)

        val afterRelease = findAsn(id)
        assertThat(afterRelease.started).isNotNull()
        assertThat(afterRelease.finished).isNull()
        val stamped = afterRelease.started

        given().`when`().post("/api/v1/asns/$id/release").then().statusCode(409)

        val afterRepeat = findAsn(id)
        assertThat(afterRepeat.started).isEqualTo(stamped)
        assertThat(afterRepeat.finished).isNull()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `finish stamps finished and preserves started`() {
        val productId = seedProduct(System.nanoTime().toString().takeLast(6))
        val id = createAsn(productId)

        given().`when`().post("/api/v1/asns/$id/release").then().statusCode(200)
        val startedStamp = findAsn(id).started

        given().`when`().post("/api/v1/asns/$id/finish").then().statusCode(200)

        val finished = findAsn(id)
        assertThat(finished.finished).isNotNull()
        assertThat(finished.started).isEqualTo(startedStamp)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel stamps finished as the terminal timestamp - started stays null pre-release`() {
        val productId = seedProduct(System.nanoTime().toString().takeLast(6))
        val id = createAsn(productId)

        given().`when`().post("/api/v1/asns/$id/cancel").then().statusCode(200)

        val canceled = findAsn(id)
        assertThat(canceled.finished).isNotNull()
        assertThat(canceled.started).isNull()
    }
}
