package com.karyo.inventory.service

import com.karyo.auth.config.SystemPropertyService
import com.karyo.inventory.repository.StockUnitRepository
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

private const val CLIENT_ID = 9030L
private const val RETENTION_KEY = "karyo.inventory.purge.retention-days"

/**
 * I3 (adjudication A9, defect-burndown-5): [StockPurgeService.purge] caps a single call at
 * `karyo.inventory.purge.batch-size`, so a tenant with more DELETABLE candidates than the batch
 * size makes monotonic progress across ticks instead of attempting its whole backlog in one
 * unbounded transaction. `@TestProfile` overrides the batch size down to 3 (rather than seeding
 * 501 real stock units against the 500 production default) and forces its own Quarkus app
 * instance, same shape as `StockPurgeSchedulerIntegrationTest`'s `enabled=true` override.
 */
@QuarkusTest
@TestProfile(StockPurgeBatchCapTest.SmallBatch::class)
class StockPurgeBatchCapTest {

    class SmallBatch : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.inventory.purge.batch-size" to "3")
    }

    @Inject
    lateinit var purgeService: StockPurgeService

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var systemPropertyService: SystemPropertyService

    // ── REST seed helpers (mirrors StockPurgeServiceTest) ──────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"SPB Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":90300,""" +
                    """"storageLocationName":"SPB-LOC"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** One product + a fully-available source stock unit of [stockAmount], own unit load. */
    private fun seedProductWithStock(stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("SPB-${s.toString().takeLast(10)}")
        val number = "SPB-SKU-$s"
        val pid = createProduct(number, iu)
        val ul = createUnitLoad("UL-SPB-$s")
        return createStock(ul, pid, number, stockAmount)
    }

    @Transactional
    fun setRetentionDays(clientId: Long, days: Int) {
        systemPropertyService.set(clientId, RETENTION_KEY, null, days.toString())
    }

    @Transactional
    fun makeDeletable(stockUnitId: Long, daysAgo: Long) {
        val su = stockUnitRepository.findById(stockUnitId)!!
        su.state = com.karyo.inventory.api.vo.StockState.DELETABLE.code
        su.modified = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
    }

    // ── Test ─────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "spb", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "layout-read", "layout-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9030"), Claim(key = "tenant_code", value = "ACME")])
    fun `purge caps a single call at the configured batch size, leftover candidates wait for the next call`() {
        setRetentionDays(CLIENT_ID, 7)
        val ids = (1..4).map {
            val suId = seedProductWithStock(10.0)
            makeDeletable(suId, daysAgo = 10)
            suId
        }

        val firstResult = purgeService.purge(CLIENT_ID)
        assertThat(firstResult.stockUnits)
            .`as`("one call must purge at most the configured batch size (3), not the whole backlog of 4")
            .isEqualTo(3)
        val survivingAfterFirst = ids.count { stockUnitRepository.findById(it) != null }
        assertThat(survivingAfterFirst)
            .`as`("exactly one candidate must remain, waiting for the next tick")
            .isEqualTo(1)

        val secondResult = purgeService.purge(CLIENT_ID)
        assertThat(secondResult.stockUnits)
            .`as`("the leftover candidate is picked up on the next call")
            .isEqualTo(1)
        assertThat(ids.all { stockUnitRepository.findById(it) == null })
            .`as`("all four candidates are gone after two calls")
            .isTrue()
    }
}
