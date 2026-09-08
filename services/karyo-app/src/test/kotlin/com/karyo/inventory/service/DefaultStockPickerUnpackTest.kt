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
 * S4 (outbound-completion sprint, task 6): [StockPicker.unpackContainer], the deliberate
 * forward-only exception that restores PACKED stock ahead of a pre-manifest shipment
 * cancel/unit-removal.
 */
@QuarkusTest
class DefaultStockPickerUnpackTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a stock unit in ON_STOCK(300) on [ulId]. */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"UNPACK","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Forward-only state change via the REST route. */
    private fun moveToState(id: Long, state: Int) {
        given().contentType(ContentType.JSON)
            .body("""{"state":$state}""")
            .`when`().post("/api/v1/stock-units/$id/change-state").then().statusCode(200)
    }

    private fun stockState(id: Long): Int =
        given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200).extract().jsonPath().getInt("state")

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unpackContainer with restoreToOnStock false flips PACKED stock back to PICKED`() {
        val itemDataId = 3400L
        val ul = createUnitLoad("UL-UNPACK-PICKED-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 50.0)
        moveToState(stockId, 600)
        moveToState(stockId, 650)
        tenantContext.clientId = 1L

        val count = stockPicker.unpackContainer(ul, restoreToOnStock = false)

        assertThat(count).isEqualTo(1)
        assertThat(stockState(stockId)).isEqualTo(600)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unpackContainer with restoreToOnStock true flips PACKED stock back to ON_STOCK`() {
        val itemDataId = 3401L
        val ul = createUnitLoad("UL-UNPACK-ONSTOCK-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 50.0)
        moveToState(stockId, 600)
        moveToState(stockId, 650)
        tenantContext.clientId = 1L

        val count = stockPicker.unpackContainer(ul, restoreToOnStock = true)

        assertThat(count).isEqualTo(1)
        assertThat(stockState(stockId)).isEqualTo(300)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unpackContainer leaves non-PACKED stock on the unit load untouched`() {
        val itemDataId = 3402L
        val ul = createUnitLoad("UL-UNPACK-MIXED-${System.nanoTime()}")
        val packedId = createStock(ul, itemDataId, 30.0)
        moveToState(packedId, 600)
        moveToState(packedId, 650)
        val onStockId = createStock(ul, itemDataId, 20.0)
        tenantContext.clientId = 1L

        val count = stockPicker.unpackContainer(ul, restoreToOnStock = false)

        assertThat(count).isEqualTo(1)
        assertThat(stockState(packedId)).isEqualTo(600)
        assertThat(stockState(onStockId)).isEqualTo(300)
    }

    /**
     * Row :2030: the explicit-`clientId` overload must resolve its scope from the passed
     * `clientId` ALONE -- proven from both sides, see the twin test in
     * `DefaultStockPickerPackTest` for why one side on its own would be ambiguous.
     */
    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unpackContainer with explicit clientId ignores the ambient tenant and a foreign one touches nothing`() {
        val itemDataId = 3403L
        val ul = createUnitLoad("UL-UNPACK-X-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 40.0)
        moveToState(stockId, 600)
        moveToState(stockId, 650)

        // Ambient holds the RIGHT owner; the foreign explicit clientId must still flip nothing.
        tenantContext.clientId = OWNER_CLIENT
        assertThat(stockPicker.unpackContainer(ul, restoreToOnStock = false, clientId = FOREIGN_CLIENT)).isEqualTo(0)
        assertThat(stockState(stockId)).`as`("a foreign scope leaves the stock PACKED").isEqualTo(650)

        // Ambient holds the unprimed default (client 0); the owner explicit clientId still works.
        tenantContext.clientId = 0L
        assertThat(stockPicker.unpackContainer(ul, restoreToOnStock = false, clientId = OWNER_CLIENT)).isEqualTo(1)
        assertThat(stockState(stockId)).isEqualTo(600)
    }

    private companion object {
        const val OWNER_CLIENT = 1L
        const val FOREIGN_CLIENT = 9977L
    }
}
