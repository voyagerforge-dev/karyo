package com.karyo.inventory

import com.karyo.inventory.api.spi.StockCountingPort
import com.karyo.inventory.api.vo.LockType
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
 * Integration test for [StockCountingPort] — enumerate + lock + release.
 *
 * Seeding pattern mirrors ReplenishmentSourceSelectorTest / DefaultStockPickerTest:
 * REST helpers (authed via @TestSecurity + @OidcSecurity) create a unit load at a
 * dedicated storageLocationId, then create an ON_STOCK (state=300) stock unit on it.
 * The JWT client_id claim (4401) is stamped on all created entities.
 * TenantContext.clientId is then primed directly so the port's tenant-filter matches.
 *
 * clientId 4401 is reserved for this suite; no other suite uses locationId 4401x range.
 */
@QuarkusTest
class StockCountingPortEnumLockTest {

    @Inject
    lateinit var port: StockCountingPort

    @Inject
    lateinit var tenantContext: TenantContext

    // ── seed helpers ────────────────────────────────────────────────────────

    data class SeedCtx(val locationId: Long, val unitLoadId: Long, val stockUnitId: Long)

    /** Creates a unit load at the given storageLocationId; returns the UL id. */
    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates an ON_STOCK (state=300) stock unit on the given UL; returns the stock-unit id. */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"SCP-${System.nanoTime().toString().takeLast(8)}",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Seeds one location → one unit load → one stock unit (amount given). */
    private fun seedStockAtLocation(clientId: Long, amount: Double): SeedCtx {
        val locationId = 44010L
        val suffix = System.nanoTime().toString().takeLast(8)
        val ulId = createUnitLoad("UL-SCP-$suffix", locationId)
        val suId = createStock(ulId, 4401_001L, amount)
        tenantContext.clientId = clientId
        return SeedCtx(locationId, ulId, suId)
    }

    /** Reads the lockType field of a stock unit directly via REST. */
    private fun lockTypeOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getInt("lockType")

    // ── test ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4401"), Claim(key = "tenant_code", value = "ACME")])
    fun `enumerates stock at a location and locks then releases it`() {
        val ctx = seedStockAtLocation(clientId = 4401L, amount = 25.0)

        val stock = port.findStockAtLocation(ctx.locationId)
        assertThat(stock).hasSize(1)
        assertThat(stock.first().stockUnitId).isEqualTo(ctx.stockUnitId)
        assertThat(stock.first().amount).isEqualByComparingTo("25")

        port.lockForCount(listOf(ctx.stockUnitId))
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.STOCKTAKING.code)

        port.releaseCount(listOf(ctx.stockUnitId))
        assertThat(lockTypeOf(ctx.stockUnitId)).isEqualTo(LockType.UNLOCKED.code)
    }

    /**
     * An OPS principal (client 0) must be read-unscoped, same as every other read path in the
     * module (see [com.karyo.security.readScope]) — ops staff physically handle every goods
     * owner's stock during a cycle count. Before the fix, [findStockAtLocation] compared raw
     * `it.clientId == tenant.clientId` (0 == 4402, never true), so an OPS principal found no
     * countable stock at all.
     */
    @Test
    @TestSecurity(user = "opsuser", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ]
    )
    fun `an OPS principal finds another owner's stock at a location`() {
        val locationId = 44020L
        val owner = 4402L
        val suffix = System.nanoTime().toString().takeLast(8)
        // OPS has no ambient owner (UnitLoadService.resolveOwner) — clientId must be explicit.
        val ulId = given().contentType(ContentType.JSON)
            .body(
                """{"clientId":$owner,"labelId":"UL-SCP-OPS-$suffix","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        val suId = createStock(ulId, 4402_001L, 15.0)
        tenantContext.clientId = 0L
        tenantContext.principalKind = com.karyo.security.PrincipalKind.OPS

        val stock = port.findStockAtLocation(locationId)

        assertThat(stock)
            .`as`("an OPS principal (read-unscoped) must find ACME's stock at this location")
            .anyMatch { it.stockUnitId == suId }
    }
}
