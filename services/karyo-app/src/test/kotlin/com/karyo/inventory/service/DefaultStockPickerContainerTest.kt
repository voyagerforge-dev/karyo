package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockPicker
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@QuarkusTest
class DefaultStockPickerContainerTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `createPickContainer makes an empty Pick Bin UL at the staging location`() {
        tenantContext.clientId = 1L
        val ulId = stockPicker.createPickContainer(
            clientId = 1L,
            unitLoadTypeId = 2L,
            locationId = 100L,
            locationName = "A-01-01",
            labelId = "PC-TEST-${System.nanoTime()}",
        )

        given().`when`().get("/api/v1/unit-loads/$ulId")
            .then().statusCode(200)
            .body("storageLocationName", org.hamcrest.CoreMatchers.`is`("A-01-01"))
            .body("stockUnits.size()", org.hamcrest.CoreMatchers.`is`(0))
    }
}
