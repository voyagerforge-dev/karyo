package com.karyo.inventory.service

import com.karyo.auth.config.SystemPropertyService
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.security.TenantContext
import io.quarkus.narayana.jta.QuarkusTransaction
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
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val ACME = 1L
private const val RETENTION_KEY = "karyo.inventory.purge.retention-days"

/**
 * Row 1 (defect-tail-2): [OrderPurgeBlockerLookup]'s terminal-netting fix -- the three block/
 * unblock scenarios. The candidate-ordering half of the row (`ORDER BY` on
 * [StockUnitRepository.findPurgeCandidates]) has its own test class,
 * `OrderPurgeBatchOrderingTest`, deliberately NOT merged into this one: that test needs a
 * `@TestProfile`-overridden batch size of 1 for the WHOLE class, and every DELETABLE row any
 * test method in a shared class leaves behind (this suite does not roll back between methods --
 * REST-seeded rows persist in the shared Dev Services database, same as every sibling
 * `StockPurgeService*Test`) becomes a same-tenant candidate a batch-of-1 run can pick up INSTEAD
 * of the row a later test method means to exercise. Uses the default (500) batch size instead, so
 * a handful of leftover rows from earlier methods or sibling classes can never crowd out the one
 * candidate a given test cares about.
 *
 * Fixtures copy the REST-seeding and `persistTerminalPick`-style idioms from
 * `StockPurgeServiceTest` and `TransferReservationTest` directly (per-file idiom, not imported
 * across test classes).
 */
@QuarkusTest
class OrderPurgeBlockerNettingTest {

    @Inject
    lateinit var purgeService: StockPurgeService

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var orderLineReservationRepository: OrderLineReservationRepository

    @Inject
    lateinit var pickOrderRepository: PickOrderRepository

    @Inject
    lateinit var pickRepository: PickRepository

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var systemPropertyService: SystemPropertyService

    // ── REST seed helpers (mirrors StockPurgeServiceTest / TransferReservationTest) ────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Purge Netting Product","itemUnitId":$itemUnitId}""")
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

    private fun createOrder(itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"customerName":"Purge Netting Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun releaseOrder(orderId: Long): io.restassured.response.ValidatableResponse =
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)

    /** One product + a fully-available source stock unit of [stockAmount], own unit load. */
    private fun seedProductWithStock(stockAmount: Double): Pair<Long, Long> {
        val s = System.nanoTime()
        val iu = createItemUnit("OPB-${s.toString().takeLast(10)}")
        val number = "OPB-SKU-$s"
        val pid = createProduct(number, iu)
        val ul = createUnitLoad("UL-OPB-$s")
        val stockUnitId = createStock(ul, pid, number, stockAmount)
        return pid to stockUnitId
    }

    // ── direct-mutation / synthetic-history fixture helpers ─────────────────────────────────

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

    /**
     * Persists a synthetic terminal pick history directly, same idiom as
     * `TransferReservationTest.persistTerminalPick`: [plannedAmount] of [itemDataId] on
     * [sourceStockUnitId], attributed to [lineId], WITHOUT driving the full release/confirm
     * pipeline (which would net the whole reservation, not a chosen portion).
     */
    private fun persistTerminalPick(lineId: Long, itemDataId: Long, itemNumber: String, sourceStockUnitId: Long, plannedAmount: BigDecimal) {
        QuarkusTransaction.requiringNew().call {
            val po = PickOrder().apply {
                clientId = ACME
                pickOrderNumber = "OPB-PO-TERM-${System.nanoTime()}"
            }
            pickOrderRepository.persist(po)
            pickRepository.persist(
                Pick().apply {
                    clientId = ACME
                    pickOrderId = po.id!!
                    deliveryOrderLineId = lineId
                    this.itemDataId = itemDataId
                    itemDataNumber = itemNumber
                    this.sourceStockUnitId = sourceStockUnitId
                    this.plannedAmount = plannedAmount
                    pickedAmount = plannedAmount
                    state = PickState.PICKED.code
                },
            )
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "opb", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit whose only reservation row is wholly terminal-consumed is purged`() {
        // The row-13 fix (defect-burndown-5) established that OrderLineReservationRefMover's
        // splitBySlice leaves a wholly-terminal-consumed row on the source stock unit FOREVER --
        // it is never deleted except at order cancel. Before this fix, OrderPurgeBlockerLookup
        // blocked on that row's mere EXISTENCE; this test proves the netting fix stops counting
        // it once its own terminal-planned total fully covers it.
        setRetentionDays(ACME, 7)
        val (pid, suId) = seedProductWithStock(50.0)
        val orderId = createOrder(pid, amount = 30.0)
        val release = releaseOrder(orderId)
        val lineId = release.extract().jsonPath().getLong("order.lines[0].id")
        tenantContext.clientId = ACME

        persistTerminalPick(lineId, pid, "OPB-SKU-TERM", suId, BigDecimal("30"))
        // The scalar reservedAmount side effect a real confirm would have produced -- released
        // directly here (same idiom TransferReservationTest uses), so the row becomes a genuine
        // findPurgeCandidates candidate (its `reservedAmount <= 0` predicate) independent of the
        // order_line_reservations netting under test.
        stockService.releaseReservation(suId, BigDecimal("30"), "test-setup", tenantContext)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId))
            .`as`("a slice that is fully terminal-consumed has nothing left for a future cancel to release -- it must not block purge")
            .isNull()
    }

    @Test
    @TestSecurity(user = "opb", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit with a live, non-consumed reservation row still blocks`() {
        // No terminal pick history at all -- the row's whole amount is live. reservedAmount is
        // released directly (bypassing order cancel) purely so this stock unit becomes a
        // findPurgeCandidates candidate, isolating the OrderPurgeBlockerLookup netting arithmetic
        // itself as the thing under test (same isolation technique as
        // StockPurgeServiceTest.makeReserved's direct-mutation fixtures).
        setRetentionDays(ACME, 7)
        val (pid, suId) = seedProductWithStock(50.0)
        val orderId = createOrder(pid, amount = 20.0)
        releaseOrder(orderId)
        tenantContext.clientId = ACME

        stockService.releaseReservation(suId, BigDecimal("20"), "test-setup", tenantContext)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId))
            .`as`("a slice with no terminal coverage at all is fully live -- it must still block purge")
            .isNotNull()
    }

    @Test
    @TestSecurity(user = "opb", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a mixed slice, only partly terminal-consumed, still blocks`() {
        // 30 reserved, only 20 terminal-consumed -- the per-slice fold leaves a live remainder of
        // 10, which must still block. Distinguishes this from the wholly-consumed case above: a
        // partial terminal history is not enough to clear the block.
        setRetentionDays(ACME, 7)
        val (pid, suId) = seedProductWithStock(50.0)
        val orderId = createOrder(pid, amount = 30.0)
        val release = releaseOrder(orderId)
        val lineId = release.extract().jsonPath().getLong("order.lines[0].id")
        tenantContext.clientId = ACME

        persistTerminalPick(lineId, pid, "OPB-SKU-MIXED", suId, BigDecimal("20"))
        stockService.releaseReservation(suId, BigDecimal("30"), "test-setup", tenantContext)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId))
            .`as`("a slice with amount 30 and terminal 20 has a live remainder of 10 -- it must still block purge")
            .isNotNull()
    }
}
