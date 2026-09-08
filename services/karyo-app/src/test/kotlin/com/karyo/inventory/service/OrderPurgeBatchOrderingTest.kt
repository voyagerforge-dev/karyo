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

private const val ACME = 1L
private const val RETENTION_KEY = "karyo.inventory.purge.retention-days"

/**
 * Row 1 (defect-tail-2), candidate-ordering half: [StockUnitRepository.findPurgeCandidates] now
 * carries `order by su.modified asc, su.id asc`. `@TestProfile` overrides the purge batch size
 * down to 1 (same technique as `StockPurgeBatchCapTest`), a WHOLE-CLASS override -- kept in its
 * own file, separate from `OrderPurgeBlockerNettingTest`'s three block/unblock scenarios, so a
 * leftover DELETABLE row from an unrelated test method can never crowd the batch-of-1 window this
 * test depends on (see that class's KDoc for the full reasoning).
 */
@QuarkusTest
@TestProfile(OrderPurgeBatchOrderingTest.SmallBatch::class)
class OrderPurgeBatchOrderingTest {

    class SmallBatch : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.inventory.purge.batch-size" to "1")
    }

    @Inject
    lateinit var purgeService: StockPurgeService

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var systemPropertyService: SystemPropertyService

    // ── REST seed helpers (mirrors StockPurgeBatchCapTest) ─────────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Purge Ordering Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,""" +
                    """"storageLocationName":"A-01-01"}""",
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
        val iu = createItemUnit("OPO-${s.toString().takeLast(10)}")
        val number = "OPO-SKU-$s"
        val pid = createProduct(number, iu)
        val ul = createUnitLoad("UL-OPO-$s")
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

    // ── Test ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "opo", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "layout-read", "layout-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9040"), Claim(key = "tenant_code", value = "ACME")])
    fun `with the batch capped at 1, the older candidate purges first`() {
        // Two unblocked DELETABLE candidates for a client id unique to this test class (9040, not
        // ACME's normal 1) so no other test's leftover DELETABLE rows for client 1 can ever share
        // this run's batch-of-1 window -- the class-level TestProfile isolation still leaves every
        // OTHER test class free to run against client 1 in the same shared database.
        val clientId = 9040L
        setRetentionDays(clientId, 7)
        val olderId = seedProductWithStock(10.0)
        val newerId = seedProductWithStock(10.0)
        makeDeletable(olderId, daysAgo = 20)
        makeDeletable(newerId, daysAgo = 10)

        purgeService.purge(clientId)

        assertThat(stockUnitRepository.findById(olderId))
            .`as`("the older (by modified) candidate must purge first when the batch is capped")
            .isNull()
        assertThat(stockUnitRepository.findById(newerId))
            .`as`("the newer candidate waits for a later tick")
            .isNotNull()
    }
}
