package com.karyo.orders

import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies [DeliveryOrderLookup.findShipTo] returns a populated [ShipToView] for an order
 * that has ship-to address fields, and null for a non-existent order.
 */
@QuarkusTest
class FindShipToTest {

    @Inject
    lateinit var lookup: DeliveryOrderLookup

    @Inject
    lateinit var tenantContext: TenantContext

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"FindShipTo Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findShipTo returns the order ship-to address`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("SHIPTO-IU-${suffix.toString().takeLast(8)}")
        val pid = createProduct("SHIPTO-SKU-$suffix", itemUnitId)

        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Acme","street":"5 Main","streetNumber":"5","zipCode":"12345",""" +
                """"city":"Springfield","country":"US","phone":"555","email":"a@b.com",""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        tenantContext.clientId = 1L
        val shipTo = lookup.findShipTo(orderId)
        assertThat(shipTo).isNotNull
        assertThat(shipTo!!.customerName).isEqualTo("Acme")
        assertThat(shipTo.street).isEqualTo("5 Main")
        assertThat(shipTo.streetNumber).isEqualTo("5")
        assertThat(shipTo.zipCode).isEqualTo("12345")
        assertThat(shipTo.city).isEqualTo("Springfield")
        assertThat(shipTo.country).isEqualTo("US")
        assertThat(shipTo.phone).isEqualTo("555")
        assertThat(shipTo.email).isEqualTo("a@b.com")
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findShipTo returns null for an unknown order`() {
        tenantContext.clientId = 1L
        assertThat(lookup.findShipTo(999999L)).isNull()
    }
}
