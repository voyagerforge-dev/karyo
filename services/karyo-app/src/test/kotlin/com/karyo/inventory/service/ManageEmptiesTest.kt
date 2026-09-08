package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockPicker
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
 * Register row 16: [UnitLoadTerminator.trashIfEmpty]'s manageEmpties suppressor.
 *
 * A manageEmpties unit load type is the reusable-container case (tote/pallet going back into
 * circulation), so trashIfEmpty must keep an emptied unit load of that type alive. It must NOT
 * suppress the ship-out case: a unit load whose stock physically left via shipping is gone for a
 * different reason and stays gone regardless of the type flag.
 */
@QuarkusTest
class ManageEmptiesTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    private fun createUnitLoadType(name: String, manageEmpties: Boolean): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","manageEmpties":$manageEmpties}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, unitLoadTypeId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":$unitLoadTypeId,""" +
                    """"storageLocationId":100,"storageLocationName":"A-01-01"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a stock unit in ON_STOCK(300) on [ulId]. */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"ME-${System.nanoTime().toString().takeLast(8)}",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun moveToState(id: Long, state: Int) {
        given().contentType(ContentType.JSON)
            .body("""{"state":$state}""")
            .`when`().post("/api/v1/stock-units/$id/change-state").then().statusCode(200)
    }

    private fun unitLoadState(id: Long): Int =
        given().`when`().get("/api/v1/unit-loads/$id").then().statusCode(200).extract().jsonPath().getInt("state")

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `emptying a unit load of a manageEmpties type leaves it alive`() {
        val typeId = createUnitLoadType("ULT-ME-${System.nanoTime()}", manageEmpties = true)
        val ulId = createUnitLoad("UL-ME-${System.nanoTime()}", typeId)
        val stockId = createStock(ulId, 5501L, 10.0)

        given().`when`().delete("/api/v1/stock-units/$stockId").then().statusCode(204)

        // Still alive: not flipped to DELETABLE(1000).
        assertThat(unitLoadState(ulId)).isNotEqualTo(1000)
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `emptying a unit load of an ordinary type still trashes it`() {
        val typeId = createUnitLoadType("ULT-ORD-${System.nanoTime()}", manageEmpties = false)
        val ulId = createUnitLoad("UL-ORD-${System.nanoTime()}", typeId)
        val stockId = createStock(ulId, 5502L, 10.0)

        given().`when`().delete("/api/v1/stock-units/$stockId").then().statusCode(204)

        assertThat(unitLoadState(ulId)).isEqualTo(1000)
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a manageEmpties type still trashes a unit load that was shipped rather than emptied`() {
        val typeId = createUnitLoadType("ULT-SHIP-${System.nanoTime()}", manageEmpties = true)
        val ulId = createUnitLoad("UL-SHIP-${System.nanoTime()}", typeId)
        val stockId = createStock(ulId, 5503L, 10.0)
        // Reach PACKED(650): ON_STOCK(300) -> PICKED(600) -> PACKED(650) are both forward-only legal.
        moveToState(stockId, 600)
        moveToState(stockId, 650)

        // TenantContext is @RequestScoped (populated by TenantFilter only during REST requests);
        // for the direct SPI call set clientId to match the seeding REST calls (clientId=1).
        tenantContext.clientId = 1L
        stockPicker.shipContainer(ulId)

        // Gone because it shipped, not because manageEmpties suppressed the trash.
        assertThat(unitLoadState(ulId)).isEqualTo(1000)
    }
}
