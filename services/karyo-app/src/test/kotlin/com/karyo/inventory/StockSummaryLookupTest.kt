package com.karyo.inventory

import com.karyo.inventory.api.spi.StockSummaryLookup
import com.karyo.inventory.service.StockService
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
import java.math.BigDecimal

/**
 * Integration test for [StockSummaryLookup] (R12a, replenishment sprint Task 5) — real beans,
 * no mocks. Seeding pattern mirrors [com.karyo.inventory.ReplenishmentSourceSelectorTest]: REST
 * helpers create a unit load at a given `storageLocationId`, then an ON_STOCK (state=300)
 * stock unit on it. Each test uses its own dedicated `clientId` (9101-9105 range).
 */
@QuarkusTest
class StockSummaryLookupTest {

    @Inject
    lateinit var lookup: StockSummaryLookup

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    private fun ns() = System.nanoTime()

    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, amount: Double, state: Int = 300): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"SSL-$itemDataId",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":$state}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun lockStock(stockUnitId: Long) {
        given().contentType(ContentType.JSON)
            .body("""{"lockType":1}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/lock")
            .then().statusCode(200)
    }

    // ── (a) sum + count across a 2-location set ──

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9101"), Claim(key = "tenant_code", value = "ACME")])
    fun `sums amount and counts rows across a 2-location set`() {
        val itemDataId = 9101_001L
        val suffix = ns()
        val locA = 9200L
        val locB = 9201L

        createStock(createUnitLoad("UL-SSL-A-$suffix", locA), itemDataId, 30.0)
        createStock(createUnitLoad("UL-SSL-B-$suffix", locB), itemDataId, 20.0)

        val summary = lookup.summaryInLocations(itemDataId, 9101L, setOf(locA, locB))

        assertThat(summary.totalAmount).isEqualByComparingTo("50.0")
        assertThat(summary.stockCount).isEqualTo(2)
    }

    // ── (b) excludes locked / reserved / non-ON_STOCK / out-of-set stock ──

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9102"), Claim(key = "tenant_code", value = "ACME")])
    fun `excludes locked, reserved, non-ON_STOCK, and out-of-set stock`() {
        val itemDataId = 9102_001L
        val suffix = ns()
        val includedLoc = 9210L
        val lockedLoc = 9211L
        val reservedLoc = 9212L
        val incomingLoc = 9213L
        val outOfSetLoc = 9214L

        // Included: settled ON_STOCK, unlocked, unreserved.
        createStock(createUnitLoad("UL-SSL-INC-$suffix", includedLoc), itemDataId, 15.0)

        // Excluded: locked.
        val lockedStockId = createStock(createUnitLoad("UL-SSL-LOCK-$suffix", lockedLoc), itemDataId, 99.0)
        lockStock(lockedStockId)

        // Excluded: partially reserved.
        val reservedStockId = createStock(createUnitLoad("UL-SSL-RESV-$suffix", reservedLoc), itemDataId, 99.0)
        tenantContext.clientId = 9102L
        stockService.reserveStock(reservedStockId, BigDecimal("10"), "test-reserve", tenantContext)

        // Excluded: not yet ON_STOCK (state=100 INCOMING).
        createStock(createUnitLoad("UL-SSL-INCOM-$suffix", incomingLoc), itemDataId, 99.0, state = 100)

        // Excluded: physically present but at a location NOT in the query set.
        createStock(createUnitLoad("UL-SSL-OUT-$suffix", outOfSetLoc), itemDataId, 99.0)

        val summary = lookup.summaryInLocations(
            itemDataId, 9102L,
            setOf(includedLoc, lockedLoc, reservedLoc, incomingLoc),
        )

        assertThat(summary.totalAmount).isEqualByComparingTo("15.0")
        assertThat(summary.stockCount).isEqualTo(1)
    }

    // ── (c) empty location set -> zero, no query ──

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9103"), Claim(key = "tenant_code", value = "ACME")])
    fun `an empty location set returns zero without querying`() {
        val summary = lookup.summaryInLocations(9103_001L, 9103L, emptySet())

        assertThat(summary.totalAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(summary.stockCount).isEqualTo(0)
    }

    // ── (d) tenant isolation ──

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9104"), Claim(key = "tenant_code", value = "ACME")])
    fun `a different clientId does not see this tenant's stock`() {
        val itemDataId = 9104_001L
        val suffix = ns()
        val locId = 9220L

        createStock(createUnitLoad("UL-SSL-TEN-$suffix", locId), itemDataId, 40.0)

        val ownTenant = lookup.summaryInLocations(itemDataId, 9104L, setOf(locId))
        val otherTenant = lookup.summaryInLocations(itemDataId, 9105L, setOf(locId))

        assertThat(ownTenant.totalAmount).isEqualByComparingTo("40.0")
        assertThat(otherTenant.totalAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(otherTenant.stockCount).isEqualTo(0)
    }
}
