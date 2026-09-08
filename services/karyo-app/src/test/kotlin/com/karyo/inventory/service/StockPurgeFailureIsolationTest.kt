package com.karyo.inventory.service

import com.karyo.auth.config.SystemPropertyService
import com.karyo.events.outbox.OutboxService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val CLIENT_ID = 9040L
private const val RETENTION_KEY = "karyo.inventory.purge.retention-days"

/**
 * Kotlin/Mockito NPE workaround (same idiom as `UserManagementServiceTransactionTest.anyObj`):
 * [org.mockito.ArgumentMatchers.any] returns `null` internally for Mockito's own bookkeeping,
 * which is fine for a Java reference-type slot but NPEs Kotlin's non-null-by-default unboxing
 * for a param like [OutboxService.publish]'s `payload: Any`. Not needed for `aggregateId`/
 * `tenantId` (primitive `Long`) or `aggregateType`/`eventType` (`String`) below -- `anyLong()`/
 * `anyString()` already return real, non-null values for exactly this reason.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = org.mockito.ArgumentMatchers.any<T>() ?: null as T

/**
 * I3 (adjudication A9, defect-burndown-5): a candidate whose own per-candidate transaction
 * throws must not prevent the OTHER candidates in the same [StockPurgeService.purge] call from
 * purging -- the whole point of moving off one all-or-nothing transaction. [OutboxService] is
 * the real bean everywhere else in the suite; here it is the ONE collaborator swapped for a
 * Mockito mock ([InjectMock]), rigged to throw only for the failing candidate's aggregate id,
 * so [QuarkusTransaction.requiringNew]'s own failure/rollback path runs for real (this is a
 * `@QuarkusTest`, not a plain unit test -- the transaction manager is the genuine one) while the
 * OTHER candidate's outbox call is a real no-op passthrough. This is its own test class (not a
 * method on [StockPurgeServiceTest]) because `@InjectMock` replaces the CDI bean for every test
 * in the class, and every other stock-purge test needs the REAL [OutboxService] to assert its
 * own outbox rows.
 */
@QuarkusTest
class StockPurgeFailureIsolationTest {

    @InjectMock
    lateinit var outboxService: OutboxService

    @Inject
    lateinit var purgeService: StockPurgeService

    @Inject
    lateinit var stockUnitRepository: com.karyo.inventory.repository.StockUnitRepository

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
            .body("""{"number":"$number","name":"SPF Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":90400,""" +
                    """"storageLocationName":"SPF-LOC"}""",
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

    private fun seedProductWithStock(stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("SPF-${s.toString().takeLast(10)}")
        val number = "SPF-SKU-$s"
        val pid = createProduct(number, iu)
        val ul = createUnitLoad("UL-SPF-$s")
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

    @Test
    @TestSecurity(user = "spf", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "layout-read", "layout-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9040"), Claim(key = "tenant_code", value = "ACME")])
    fun `a candidate whose per-candidate transaction throws does not block the rest of the same tick`() {
        setRetentionDays(CLIENT_ID, 7)
        val failingId = seedProductWithStock(10.0)
        val okId = seedProductWithStock(10.0)
        makeDeletable(failingId, daysAgo = 10)
        makeDeletable(okId, daysAgo = 10)

        // The failing candidate's own outbox publish throws; every other candidate's call
        // (including the healthy one) is a real no-op passthrough on the mock. Matched by
        // inspecting the invocation's own aggregateId argument, not by an `eq()` matcher --
        // `eq()`'s generic overload returns null internally, which NPEs on unboxing into
        // `publish`'s primitive `Long aggregateId` slot from Kotlin (`anyLong()`/`anyString()`
        // below have dedicated primitive-returning overloads for exactly this reason).
        doAnswer { invocation ->
            val aggregateId = invocation.getArgument<Long>(1)
            if (aggregateId == failingId) {
                throw IllegalStateException("boom -- rigged failure for the failure-isolation test")
            }
        }.`when`(outboxService).publish(anyString(), anyLong(), anyString(), anyObj(), anyLong())

        val result = purgeService.purge(CLIENT_ID)

        assertThat(stockUnitRepository.findById(failingId))
            .`as`("the failing candidate's own transaction rolled back -- it survives, to retry next tick")
            .isNotNull()
        assertThat(stockUnitRepository.findById(okId))
            .`as`("the healthy candidate in the SAME purge() call must still be purged")
            .isNull()
        assertThat(result.failed)
            .`as`("the failure is counted, not silently dropped")
            .isEqualTo(1)
        assertThat(result.stockUnits)
            .`as`("exactly the healthy candidate was purged")
            .isEqualTo(1)
    }
}
