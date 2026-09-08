package com.karyo.inventory.service

import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Row 18: real-bean, end-to-end proof that [StockPurgeScheduler.runOnce] hard-deletes an
 * eligible DELETABLE stock unit for a real (non-zero) tenant WITHOUT anything priming
 * `TenantContext` first -- the exact shape of a real `@Scheduled` invocation. Modeled on
 * `ReplenishmentSchedulerIntegrationTest`/`MonitorEvaluatorIntegrationTest` (see either for why
 * a real-bean regression guard is needed here: `StockPurgeServiceTest` calls
 * [StockPurgeService.purge] directly with an explicit `clientId`, which cannot exercise
 * [StockUnitRepository.clientIdsWithDeletableStock]'s own unscoped tenant-loop read, or catch an
 * ambient `TenantContext` read anywhere transitively below it -- a mocked-collaborator unit test
 * cannot provide this guard either way, since mocking would hide exactly the call graph this
 * class exists to walk for real).
 *
 * `@TestProfile` forces its own Quarkus app instance, so `karyo.inventory.purge.enabled=true` is
 * scoped to this one class and does not affect the rest of the suite's default (`false`).
 *
 * The retention window is deliberately left at its SC16 catalog default (30 days) rather than
 * overridden to 0: this test's DB is shared across the whole suite within one test run (Dev
 * Services reuses the same Postgres container across `@TestProfile` switches), and a 0-day
 * retention would make [StockPurgeScheduler.runOnce] eligible to hard-delete ANY tenant's
 * DELETABLE stock, not just this test's own fixture. Instead only [deletableId]'s own `modified`
 * stamp is individually backdated 400 days -- everything else in the shared database, whatever
 * client it belongs to, stays inside the 30-day window and is safely skipped.
 *
 * Seeding (item -> product -> unit load -> stock, then `DELETE /stock-units/{id}`) goes through
 * REST, same as the sibling integration tests -- those HTTP calls run on the test server's own
 * thread/request scope and do not leak into the TEST METHOD's own `TenantContext`.
 */
@QuarkusTest
@TestProfile(StockPurgeSchedulerIntegrationTest.PurgeEnabled::class)
class StockPurgeSchedulerIntegrationTest {

    class PurgeEnabled : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.inventory.purge.enabled" to "true")
    }

    @Inject
    lateinit var scheduler: StockPurgeScheduler

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    companion object {
        private const val CLIENT_ID = 9010L

        /** I2's own client -- never given any stock unit at all, so
         *  [StockUnitRepository.clientIdsWithDeletableStock] can never surface it. The only way
         *  this client is swept is via the scheduler's union with
         *  [UnitLoadRepository.clientIdsWithEmptyTerminal]. */
        private const val EMPTY_TERMINAL_CLIENT_ID = 9020L
    }

    // ── REST seed helpers (mirrors ReplenishmentSchedulerIntegrationTest) ──────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"SPS Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":90100,"storageLocationName":"SPS-LOC"}""",
            )
            .`when`().post("/api/v1/unit-loads").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, itemNumber: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber",""" +
                    """"amount":10,"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun deleteStockUnit(id: Long) {
        given().`when`().delete("/api/v1/stock-units/$id").then().statusCode(204)
    }

    @Transactional
    fun backdate(stockUnitId: Long, daysAgo: Long) {
        val su = stockUnitRepository.findById(stockUnitId)!!
        su.modified = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
    }

    /** I2 fixture: flips an already-empty unit load straight to a
     *  [com.karyo.inventory.service.UnitLoadTerminator.GONE_STATES] state, with no stock unit
     *  ever created on it -- the "no DELETABLE stock candidate, but one empty-terminal unit
     *  load" shape row I2 names. */
    @Transactional
    fun makeUnitLoadTerminal(unitLoadId: Long) {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        ul.state = StockState.DELETABLE.code
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "sps",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9010"), Claim(key = "tenant_code", value = "ACME")])
    fun `runOnce hard-deletes an eligible DELETABLE stock unit for a real tenant with no TenantContext primed`() {
        val ns = System.nanoTime()

        val deletableIu = createItemUnit("IU-SPS-DEL-${ns.toString().takeLast(8)}")
        val deletablePid = createProduct("SPS-DEL-SKU-$ns", deletableIu)
        val deletableUl = createUnitLoad("UL-SPS-DEL-$ns")
        val deletableId = createStock(deletableUl, deletablePid, "SPS-DEL-SKU-$ns")
        deleteStockUnit(deletableId)
        backdate(deletableId, daysAgo = 400)

        val liveIu = createItemUnit("IU-SPS-LIVE-${ns.toString().takeLast(8)}")
        val livePid = createProduct("SPS-LIVE-SKU-$ns", liveIu)
        val liveUl = createUnitLoad("UL-SPS-LIVE-$ns")
        val liveId = createStock(liveUl, livePid, "SPS-LIVE-SKU-$ns")

        // The point of this test: NOTHING primes TenantContext.clientId here -- this reproduces
        // the exact shape of a real @Scheduled invocation.
        scheduler.runOnce()

        assertThat(stockUnitRepository.findById(deletableId)).isNull()
        assertThat(stockUnitRepository.findById(liveId)).isNotNull()
    }

    @Test
    @TestSecurity(
        user = "sps-i2",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9020"), Claim(key = "tenant_code", value = "ACME")])
    fun `runOnce purges a terminal empty unit load even when its client has no DELETABLE stock candidate (I2)`() {
        val ns = System.nanoTime()
        val ulId = createUnitLoad("UL-SPS-EMPTY-$ns")
        makeUnitLoadTerminal(ulId)

        // The point of this test: EMPTY_TERMINAL_CLIENT_ID never had ANY stock unit, so
        // clientIdsWithDeletableStock() alone can never surface it. Before the I2 fix, runOnce's
        // tenant loop is built ONLY from that query, so this client is never visited and ulId
        // survives forever, even though it is a real, terminal, zero-stock candidate.
        scheduler.runOnce()

        assertThat(unitLoadRepository.findById(ulId))
            .`as`("a terminal empty unit load must be reaped even when its own client has no " +
                "DELETABLE stock candidate to put it on the scheduler's tenant loop")
            .isNull()
    }
}
