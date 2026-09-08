package com.karyo.app.admin

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.Matchers.greaterThan
import org.junit.jupiter.api.Test

@QuarkusTest
class AdminExtensionsResourceTest {

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN"])
    fun `admin sees the live SPI registry with resolved implementations`() {
        given()
            .`when`().get("/api/v1/admin/extensions")
            .then().statusCode(200)
            .body("size()", greaterThan(0))
            // ProductLookup is implemented once, by product-core's DefaultProductLookup.
            .body("find { it.spiInterface == 'ProductLookup' }.implementations", hasItem("DefaultProductLookup"))
            .body("find { it.spiInterface == 'ProductLookup' }.implementationCount", equalTo(1))
            .body("find { it.spiInterface == 'ProductLookup' }.module", equalTo("karyo-product"))
            // ShipmentLookup is implemented once, by fulfillment-core's DefaultShipmentLookup.
            .body("find { it.spiInterface == 'ShipmentLookup' }.implementations", hasItem("DefaultShipmentLookup"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    fun `non-admin is forbidden`() {
        given()
            .`when`().get("/api/v1/admin/extensions")
            .then().statusCode(403)
    }
}
