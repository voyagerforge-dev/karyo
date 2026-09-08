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
 * Verifies the optional myWMS ship-to Address fields (street/streetNumber/zipCode/
 * city/country/phone/email) round-trip through create -> persist -> response, and
 * that omitting them entirely still creates an order (all 7 are nullable/optional).
 */
@QuarkusTest
class DeliveryOrderAddressTest {

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Address Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery order round-trips ship-to address fields`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("ADDR-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("ADDR-SKU-$suffix", itemUnitId)

        val id = given().contentType(ContentType.JSON).body(
            """{"customerName":"Acme","street":"5 Main","streetNumber":"5","zipCode":"12345",""" +
                """"city":"Springfield","country":"US","phone":"555","email":"a@b.com",""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/delivery-orders/$id")
            .then().statusCode(200)
            .body("street", `is`("5 Main"))
            .body("streetNumber", `is`("5"))
            .body("zipCode", `is`("12345"))
            .body("city", `is`("Springfield"))
            .body("country", `is`("US"))
            .body("phone", `is`("555"))
            .body("email", `is`("a@b.com"))
            .body("lines[0].itemDataName", `is`("Address Test Product"))
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery order without address still creates`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("NOADDR-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("NOADDR-SKU-$suffix", itemUnitId)

        val id = given().contentType(ContentType.JSON).body(
            """{"customerName":"NoAddr","lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/delivery-orders/$id")
            .then().statusCode(200)
            .body("customerName", `is`("NoAddr"))
            .body("street", `is`(nullValue()))
            .body("city", `is`(nullValue()))
            .body("email", `is`(nullValue()))
    }
}
