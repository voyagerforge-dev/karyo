package com.karyo.inventory.service

import com.karyo.security.PrincipalKind
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
 * Exercises [StockService.readAmount] — Σ on-hand amount for one item at one location.
 * Structural template is `ReservationTransferTest`/`TransferToCarrierTest`: seed data through
 * the `/api/v1` REST surface, then call the service directly.
 *
 * TenantContext is @RequestScoped and is normally populated by TenantFilter on HTTP requests
 * only. These calls go straight into the service, so clientId (and, where the scoping is the
 * point of the test, principalKind) is primed by hand.
 *
 * `labelId` is UNIQUE and the test DB is shared across a run, so every fixture carries a
 * bounded unique suffix. Item ids and location ids are likewise minted fresh per test — they
 * are cross-module ids with no FK, so an unshared pair fully isolates each test's aggregate
 * from every other test's rows.
 */
@QuarkusTest
class ReadAmountTest {

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    private fun uniq() = System.nanoTime().toString().takeLast(6)

    /** A fresh cross-module id, unique within a run. */
    private fun freshId(): Long = System.nanoTime()

    private fun createUnitLoad(
        label: String,
        locationId: Long,
        clientId: Long? = null,
    ): Long {
        val clientField = if (clientId != null) """"clientId":$clientId,""" else ""
        return given()
            .contentType(ContentType.JSON)
            .body(
                """{$clientField"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createStock(
        unitLoadId: Long,
        itemDataId: Long,
        amount: Double,
        state: Int = 300,
    ): Long =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"ITEM-$itemDataId",""" +
                    """"amount":$amount,"unitLoadId":$unitLoadId,"state":$state}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `sums the amounts of several stock units of the same item at the same location`() {
        val s = uniq()
        val item = freshId()
        val loc = freshId()
        val ulA = createUnitLoad("UL-SUM-A-$s", loc)
        val ulB = createUnitLoad("UL-SUM-B-$s", loc)
        createStock(ulA, item, 100.0)
        createStock(ulB, item, 25.5)
        tenantContext.clientId = 1L

        val total = stockService.readAmount(item, loc, tenantContext)

        assertThat(total).isEqualByComparingTo(BigDecimal("125.5"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `excludes stock of a different item at that location`() {
        val s = uniq()
        val item = freshId()
        val otherItem = freshId()
        val loc = freshId()
        val ul = createUnitLoad("UL-ITEM-$s", loc)
        createStock(ul, item, 40.0)
        createStock(ul, otherItem, 999.0)
        tenantContext.clientId = 1L

        val total = stockService.readAmount(item, loc, tenantContext)

        assertThat(total).isEqualByComparingTo(BigDecimal("40"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `excludes stock of the same item at a different location`() {
        val s = uniq()
        val item = freshId()
        val loc = freshId()
        val otherLoc = freshId()
        createStock(createUnitLoad("UL-LOC-HERE-$s", loc), item, 40.0)
        createStock(createUnitLoad("UL-LOC-AWAY-$s", otherLoc), item, 999.0)
        tenantContext.clientId = 1L

        val total = stockService.readAmount(item, loc, tenantContext)

        assertThat(total).isEqualByComparingTo(BigDecimal("40"))
    }

    /**
     * Pins the ON_STOCK-only decision: INCOMING stock is physically en route, not yet on hand,
     * so it must not appear in an "amount at this location" total.
     */
    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `excludes stock that is not ON_STOCK`() {
        val s = uniq()
        val item = freshId()
        val loc = freshId()
        val ul = createUnitLoad("UL-STATE-$s", loc)
        createStock(ul, item, 40.0, state = 300)
        createStock(ul, item, 999.0, state = 100)
        tenantContext.clientId = 1L

        val total = stockService.readAmount(item, loc, tenantContext)

        assertThat(total).isEqualByComparingTo(BigDecimal("40"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `returns zero rather than null when there is no matching stock`() {
        tenantContext.clientId = 1L

        val total = stockService.readAmount(freshId(), freshId(), tenantContext)

        assertThat(total).isNotNull()
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO)
    }

    /**
     * The scoping test. An aggregate cannot be corrected by filtering rows after the database
     * has summed them, so the owner predicate has to be inside the query — if it were dropped,
     * this owner's total would silently include the other owner's 999.
     *
     * Seeding runs as an ops principal so both owners' unit loads can be created in one test;
     * the assertion then runs as owner 1.
     */
    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `an owner principal's total excludes another owner's stock`() {
        val s = uniq()
        val item = freshId()
        val loc = freshId()
        createStock(createUnitLoad("UL-OWN-1-$s", loc, clientId = 1), item, 40.0)
        createStock(createUnitLoad("UL-OWN-2-$s", loc, clientId = 2), item, 999.0)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val total = stockService.readAmount(item, loc, tenantContext)

        assertThat(total).isEqualByComparingTo(BigDecimal("40"))
    }
}
