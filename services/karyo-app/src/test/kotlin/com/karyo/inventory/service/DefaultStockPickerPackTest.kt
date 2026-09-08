package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
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

@QuarkusTest
class DefaultStockPickerPackTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a stock unit in ON_STOCK(300) on [ulId]. */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"PACK","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Forward-only state change via the REST route (used here to reach PICKED(600)). */
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
    fun `packContainer flips PICKED stock on the UL to PACKED`() {
        val itemDataId = 3300L
        val ul = createUnitLoad("UL-PACK-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 50.0)
        // Reach PICKED(600): ON_STOCK(300) -> PICKED(600) is forward-only legal.
        moveToState(stockId, 600)

        // TenantContext is @RequestScoped (populated by TenantFilter only during REST requests);
        // for the direct SPI call set clientId to match the seeding REST calls (clientId=1).
        tenantContext.clientId = 1L

        val count = stockPicker.packContainer(ul)

        assertThat(count).isEqualTo(1)
        assertThat(stockState(stockId)).isEqualTo(650)
    }

    /**
     * Row :2030: the explicit-`clientId` overload must resolve its scope from the passed
     * `clientId` ALONE. Proven from both sides in one test, because either side on its own is
     * ambiguous: the foreign call runs while the ambient context holds the CORRECT owner (so a
     * zero can only come from the explicit argument), and the owner call runs while the
     * ambient context holds client 0 -- the unprimed default a non-REST caller actually sees --
     * so a success can only come from the explicit argument too.
     */
    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packContainer with explicit clientId ignores the ambient tenant and a foreign one touches nothing`() {
        val itemDataId = 3301L
        val ul = createUnitLoad("UL-PACK-X-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 10.0)
        moveToState(stockId, 600)

        // Ambient holds the RIGHT owner; the foreign explicit clientId must still flip nothing.
        // `StockService.findByUnitLoad` filters by read scope, so a foreign scope simply sees an
        // empty unit load -- zero flipped, no throw.
        tenantContext.clientId = OWNER_CLIENT
        assertThat(stockPicker.packContainer(ul, FOREIGN_CLIENT)).isEqualTo(0)
        assertThat(stockState(stockId)).isEqualTo(600)

        // Ambient now holds the unprimed default (client 0); the owner explicit clientId must
        // still flip the row.
        tenantContext.clientId = 0L
        assertThat(stockPicker.packContainer(ul, OWNER_CLIENT)).isEqualTo(1)
        assertThat(stockState(stockId)).isEqualTo(650)
    }

    /**
     * Burndown-6 final review (F1): the explicit-`clientId` overloads scope by the passed
     * `clientId` but ATTRIBUTE by whoever is actually on the request. Several callers of these
     * overloads are plain REST paths with a human on them (pack-out, shipment cancel, shortfall
     * recovery); before this fix their journal rows all read `operatorName = "system"`, and one
     * shipment-cancel transaction could even mix a real username with `system` row by row.
     *
     * Both halves in one method, in this order: the unset half runs FIRST, while
     * [TenantContext.username] is still untouched at its `"system"` class default (the injected
     * bean is request-scoped and would keep any value set earlier in the same method), so the
     * port/scheduler callers with no human on the request are proven unchanged.
     */
    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packContainer with explicit clientId journals the acting username, or system when none is primed`() {
        val ulSystem = createUnitLoad("UL-PACK-SYS-${System.nanoTime()}")
        val systemStockId = createStock(ulSystem, 3302L, 7.0)
        moveToState(systemStockId, 600)

        // username deliberately NOT set: an unprimed request scope (a @Scheduled tick, a
        // port-to-port call) still reads the `"system"` default, exactly as before this change.
        tenantContext.clientId = OWNER_CLIENT
        assertThat(stockPicker.packContainer(ulSystem, OWNER_CLIENT)).isEqualTo(1)
        assertThat(newestJournalRow(systemStockId).operatorName)
            .`as`("no human on the request, so the row stays attributed to system")
            .isEqualTo("system")

        val ul = createUnitLoad("UL-PACK-WHO-${System.nanoTime()}")
        val stockId = createStock(ul, 3303L, 5.0)
        moveToState(stockId, 600)

        // A human IS on this request (TenantFilter would have set both fields from the JWT).
        tenantContext.username = "packer-jo"
        assertThat(stockPicker.packContainer(ul, OWNER_CLIENT)).isEqualTo(1)
        assertThat(newestJournalRow(stockId).operatorName)
            .`as`("the explicit-clientId overload must not erase the acting operator")
            .isEqualTo("packer-jo")
    }

    /** Newest journal row for [stockUnitId], the one the pack just wrote. */
    private fun newestJournalRow(stockUnitId: Long): InventoryJournal =
        journalRepository.find("stockUnitId = ?1 order by id desc", stockUnitId).firstResult()
            ?: error("no journal row for stock unit $stockUnitId")

    private companion object {
        const val OWNER_CLIENT = 1L
        const val FOREIGN_CLIENT = 9977L
    }
}
