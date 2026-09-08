package com.karyo.inventory.service

import com.karyo.auth.config.SystemPropertyService
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.vo.PickState
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.orders.vo.OrderState
import com.karyo.stocktaking.domain.model.CountLine
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val ACME = 1L
private const val GLOBEX = 2L
private const val RETENTION_KEY = "karyo.inventory.purge.retention-days"

/**
 * Row 18: [StockPurgeService.purge] with real beans -- retention-window arithmetic, every
 * [com.karyo.inventory.api.spi.PurgeBlockerLookup] implementation's live-reference guard, and
 * the journal/outbox trail, all inside one transaction.
 *
 * Fixtures deliberately mutate persisted state directly (not through [StockService.deleteStock]/
 * [UnitLoadTerminator.trashIfEmpty]) to reach states those write paths would not otherwise
 * produce in combination -- same idiom as `AdjustDeletableGuardTest`'s `seedCrossOwnerRig`.
 * `modified` is backdated by plain assignment, the same mechanism `karyo-demo` uses to build
 * backdated history (`BaseEntity` timestamps are assignable fields, not `@CreationTimestamp`).
 *
 * The three live-flow blockers not covered by their own named scenario here (non-terminal
 * transport order, pick order target container, open-session count line) are grouped into one
 * additional test rather than three near-identical ones.
 */
@QuarkusTest
class StockPurgeServiceTest {

    @Inject
    lateinit var purgeService: StockPurgeService

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    @Inject
    lateinit var outboxRepository: OutboxEventRepository

    @Inject
    lateinit var systemPropertyService: SystemPropertyService

    @Inject
    lateinit var em: EntityManager

    // ── REST seed helpers (mirrors TransferReservationTest) ────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Stock Purge Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, clientId: Long? = null): Long {
        val clientField = if (clientId != null) ""","clientId":$clientId""" else ""
        return given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,""" +
                    """"storageLocationName":"A-01-01"$clientField}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double, state: Int = 300): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":$state}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createOrder(itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"customerName":"Stock Purge Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun releaseOrder(orderId: Long) {
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
    }

    /** One product + a fully-available source stock unit of [stockAmount]. */
    private fun seedProductWithStock(stockAmount: Double, clientId: Long? = null): Pair<Long, Long> {
        val s = System.nanoTime()
        val iu = createItemUnit("SPT-${s.toString().takeLast(10)}")
        val number = "SPT-SKU-$s"
        val pid = createProduct(number, iu)
        val ul = createUnitLoad("UL-SPT-$s", clientId)
        val stockUnitId = createStock(ul, pid, number, stockAmount)
        return pid to stockUnitId
    }

    // ── direct-mutation fixture helpers ─────────────────────────────────────────────────────

    @Transactional
    fun setRetentionDays(clientId: Long, days: Int) {
        systemPropertyService.set(clientId, RETENTION_KEY, null, days.toString())
    }

    /**
     * Test-isolation fix (defect-burndown-5, register row filed against this test): `the purge
     * only ever touches the client it was called for` deliberately leaves GLOBEX's DELETABLE
     * stock row un-purged (that is the whole point of the assertion), so without this cleanup
     * both that row and GLOBEX's own [RETENTION_KEY] override would outlive the test in the
     * shared Dev Services database, and a LATER `StockPurgeSchedulerIntegrationTest` run sharing
     * that database would sweep it -- by accident, not by design. Deletes the fixture directly
     * (bypassing [purgeService], which is exactly what this test proves must NOT touch GLOBEX)
     * and resets the SC16 override via [SystemPropertyService.delete] so the key reverts to its
     * catalog default for any later test reading it.
     */
    @Transactional
    fun cleanupGlobexFixture(stockUnitId: Long, unitLoadId: Long) {
        stockUnitRepository.deleteById(stockUnitId)
        unitLoadRepository.deleteById(unitLoadId)
        systemPropertyService.delete(GLOBEX, RETENTION_KEY, null)
    }

    /** Flips [stockUnitId] to DELETABLE and backdates `modified` by [daysAgo] days. */
    @Transactional
    fun makeDeletable(stockUnitId: Long, daysAgo: Long) {
        val su = stockUnitRepository.findById(stockUnitId)!!
        su.state = StockState.DELETABLE.code
        su.modified = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
    }

    /** Backdates an ON_STOCK unit's `modified` without touching its state. */
    @Transactional
    fun backdateOnly(stockUnitId: Long, daysAgo: Long) {
        val su = stockUnitRepository.findById(stockUnitId)!!
        su.modified = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
    }

    @Transactional
    fun makeUnitLoadTerminal(unitLoadId: Long) {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        ul.state = StockState.DELETABLE.code
    }

    /**
     * Final-review wave, IMPORTANT 1: stamps a live `reservedAmount` directly on the entity, the
     * same shape the gap describes -- `POST /api/v1/stock-units/{id}/reserve` creates exactly
     * this scalar with no `order_line_reservations` row and no `Pick`, so no [PurgeBlockerLookup]
     * sees it either way.
     */
    @Transactional
    fun makeReserved(stockUnitId: Long, amount: Double) {
        val su = stockUnitRepository.findById(stockUnitId)!!
        su.reservedAmount = BigDecimal.valueOf(amount)
    }

    /** A minimal, real (non-terminal by default) pick referencing [sourceStockUnitId] --
     *  bypasses the order/release flow entirely, so no order_line_reservation rides along. */
    @Transactional
    fun persistPick(sourceStockUnitId: Long, clientId: Long, terminal: Boolean): Long {
        val po = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "SPT-PO-${System.nanoTime()}"
        }
        em.persist(po)
        val pick = Pick().apply {
            this.clientId = clientId
            pickOrderId = po.id!!
            itemDataId = 1L
            itemDataNumber = "SPT-PICK"
            this.sourceStockUnitId = sourceStockUnitId
            state = if (terminal) PickState.PICKED.code else PickState.CREATED.code
        }
        em.persist(pick)
        return pick.id!!
    }

    @Transactional
    fun persistPickOrderTargeting(unitLoadId: Long, clientId: Long): Long {
        val po = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "SPT-PO-TGT-${System.nanoTime()}"
            targetUnitLoadId = unitLoadId
        }
        em.persist(po)
        return po.id!!
    }

    @Transactional
    fun persistShippingUnitReferencing(unitLoadId: Long, clientId: Long): Long {
        val shipment = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SPT-SHIP-${System.nanoTime()}"
            deliveryOrderNumber = "SPT-DO-DUMMY"
        }
        em.persist(shipment)
        val unit = ShippingUnit().apply {
            this.clientId = clientId
            shipmentId = shipment.id!!
            shippingUnitNumber = "SPT-SU-${System.nanoTime()}"
            this.unitLoadId = unitLoadId
        }
        em.persist(unit)
        return unit.id!!
    }

    @Transactional
    fun persistOpenTransportOrder(sourceStockUnitId: Long, unitLoadId: Long, clientId: Long): Long {
        val to = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "SPT-TO-${System.nanoTime()}"
            transportType = TransportType.MOVE
            this.unitLoadId = unitLoadId
            unitLoadLabel = "SPT-UL-$unitLoadId"
            sourceLocationId = 1L
            sourceLocationName = "SPT-LOC"
            this.sourceStockUnitId = sourceStockUnitId
            state = OrderState.CREATED.code
        }
        em.persist(to)
        return to.id!!
    }

    @Transactional
    fun persistOpenCountLineReferencing(unitLoadId: Long, clientId: Long): Long {
        val session = CountSession().apply {
            this.clientId = clientId
            sessionNumber = "SPT-CS-${System.nanoTime()}"
            state = CountSessionState.OPEN.code
        }
        em.persist(session)
        val order = CountOrder().apply {
            this.clientId = clientId
            sessionId = session.id!!
            orderNumber = "SPT-CO-${System.nanoTime()}"
            locationId = 1L
            locationName = "SPT-LOC"
        }
        em.persist(order)
        val line = CountLine().apply {
            this.clientId = clientId
            countOrderId = order.id!!
            this.unitLoadId = unitLoadId
            itemDataId = 1L
            itemDataNumber = "SPT-CL"
            plannedAmount = BigDecimal.ZERO
        }
        em.persist(line)
        return line.id!!
    }

    /**
     * Row 18 fix round 1 (Critical 1): a minimal, real pick whose TARGET (not source) is
     * [targetStockUnitId] -- terminal state, to make the point that unlike a pick's source
     * reference, the target reference must still block even once the pick itself is done.
     * `sourceStockUnitId` is a dummy id (0), never a real candidate, since the column is
     * non-null but this fixture is only exercising the target half.
     */
    @Transactional
    fun persistPickTargeting(targetStockUnitId: Long, clientId: Long): Long {
        val po = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "SPT-PO-TGTSTOCK-${System.nanoTime()}"
        }
        em.persist(po)
        val pick = Pick().apply {
            this.clientId = clientId
            pickOrderId = po.id!!
            itemDataId = 1L
            itemDataNumber = "SPT-PICK-TGT"
            sourceStockUnitId = 0L
            this.targetStockUnitId = targetStockUnitId
            state = PickState.PICKED.code
        }
        em.persist(pick)
        return pick.id!!
    }

    /**
     * Row 18 fix round 1 (Critical 2): rides [childUnitLoadId] onto [carrierUnitLoadId] via the
     * real `unit_loads.carrier_unit_load_id` foreign key ([UnitLoadService.transferToCarrier]'s
     * nested-container feature), bypassing that service since this is only exercising the FK's
     * effect on the purge candidate query, not the transfer business rules.
     */
    @Transactional
    fun setCarrier(childUnitLoadId: Long, carrierUnitLoadId: Long) {
        val child = unitLoadRepository.findById(childUnitLoadId)!!
        val carrier = unitLoadRepository.findById(carrierUnitLoadId)!!
        child.carrierUnitLoad = carrier
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a DELETABLE stock unit older than the retention window is removed`() {
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId)).isNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a DELETABLE stock unit inside the retention window is left alone`() {
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        makeDeletable(suId, daysAgo = 3)

        purgeService.purge(ACME)

        val after = stockUnitRepository.findById(suId)
        assertThat(after).isNotNull()
        assertThat(after!!.state).isEqualTo(StockState.DELETABLE.code)
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an ON_STOCK stock unit is never touched`() {
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        backdateOnly(suId, daysAgo = 400)

        purgeService.purge(ACME)

        val after = stockUnitRepository.findById(suId)
        assertThat(after).isNotNull()
        assertThat(after!!.state).isEqualTo(StockState.ON_STOCK.code)
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit referenced by an open pick is skipped and survives`() {
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        persistPick(suId, ACME, terminal = false)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        val after = stockUnitRepository.findById(suId)
        assertThat(after).`as`("a stock unit an open pick still sources must survive purge").isNotNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit referenced by an order line reservation is skipped and survives`() {
        setRetentionDays(ACME, 7)
        val (pid, suId) = seedProductWithStock(50.0)
        val orderId = createOrder(pid, amount = 20.0)
        // The order's own selection reserves against suId -- it is the only ON_STOCK unit for
        // this item -- creating the order_line_reservations row this test proves blocks purge.
        releaseOrder(orderId)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        val after = stockUnitRepository.findById(suId)
        assertThat(after).`as`("a stock unit a live order-line reservation still references must survive purge").isNotNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit referenced only by a terminal pick is still removed`() {
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        persistPick(suId, ACME, terminal = true)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId))
            .`as`("a terminal pick is history, not a live claim -- it must not block purge")
            .isNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a DELETABLE stock unit with a live reservedAmount survives purge`() {
        // Final-review wave, IMPORTANT 1: reservedAmount is a reference-free claim -- no
        // order_line_reservations row, no Pick -- so no PurgeBlockerLookup ever saw it. Without
        // the `reservedAmount <= 0` predicate on findPurgeCandidates, this row is offered up and
        // hard-deleted out from under whatever integrator still holds the reservation. This test
        // fails against the pre-fix code (the row is null after purge).
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        makeReserved(suId, 20.0)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId))
            .`as`("a stock unit with a live reservedAmount must survive purge")
            .isNotNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit referenced only as a pick target survives purge`() {
        // Fix round 1 (Critical 1): picks.target_stock_unit_id is the physical container the
        // picked quantity ended up on, read on demand forever by ShipmentDocumentService's
        // BOL/packing-slip/content-list routes -- unlike the SOURCE reference (which stops
        // mattering once the pick is terminal), the target reference must block regardless of
        // the pick's own state. terminal = true here specifically to prove that.
        setRetentionDays(ACME, 7)
        val (_, suId) = seedProductWithStock(50.0)
        persistPickTargeting(suId, ACME)
        makeDeletable(suId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId))
            .`as`("a stock unit a pick still targets must survive purge -- document generation reads it on demand forever")
            .isNotNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load is removed once its last stock unit is gone and its state is terminal`() {
        setRetentionDays(ACME, 7)
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-SPT-TERM-$s")
        val iu = createItemUnit("SPT-TERM-${s.toString().takeLast(10)}")
        val pid = createProduct("SPT-TERM-SKU-$s", iu)
        val suId = createStock(ulId, pid, "SPT-TERM-SKU-$s", 50.0)
        makeDeletable(suId, daysAgo = 10)
        makeUnitLoadTerminal(ulId)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId)).isNull()
        assertThat(unitLoadRepository.findById(ulId))
            .`as`("the unit load must be removed in the same purge pass that empties it")
            .isNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load referenced by a shipping unit is skipped and survives`() {
        setRetentionDays(ACME, 7)
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-SPT-SHIP-$s")
        val iu = createItemUnit("SPT-SHIP-${s.toString().takeLast(10)}")
        val pid = createProduct("SPT-SHIP-SKU-$s", iu)
        val suId = createStock(ulId, pid, "SPT-SHIP-SKU-$s", 50.0)
        makeDeletable(suId, daysAgo = 10)
        makeUnitLoadTerminal(ulId)
        persistShippingUnitReferencing(ulId, ACME)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(suId)).isNull()
        assertThat(unitLoadRepository.findById(ulId))
            .`as`("a unit load a shipping record still references must survive purge")
            .isNotNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a terminal stock-empty unit load still carrying a live child survives, and the rest of the tick still completes`() {
        // Fix round 1 (Critical 2): unit_loads.carrier_unit_load_id is a real, self-referencing
        // foreign key (inventory V102). Without excluding a still-carrying candidate,
        // UnitLoadRepository.findEmptyTerminal would offer this unit load up, no
        // PurgeBlockerLookup would catch it (carrier nesting is purely internal to inventory),
        // and the delete would hit the FK constraint at flush time -- rolling back the WHOLE
        // transactional purge(clientId) call, including the unrelated candidate below that has
        // nothing to do with the carrier relationship. This test fails outright (an unhandled
        // persistence exception, before any assertion even runs) under the bug and passes clean
        // under the fix -- that is the point of it.
        setRetentionDays(ACME, 7)
        val s = System.nanoTime()

        val carrierUlId = createUnitLoad("UL-SPT-CARRIER-$s")
        // Item-unit name has a 20-char DB limit (CreateItemUnitRequest.name, @Size(max = 20)) --
        // "SPT-CAR-" (not "SPT-CARRIER-") keeps the 10-digit nanoTime suffix under that cap.
        val carrierIu = createItemUnit("SPT-CAR-${s.toString().takeLast(10)}")
        val carrierPid = createProduct("SPT-CARRIER-SKU-$s", carrierIu)
        val carrierStockId = createStock(carrierUlId, carrierPid, "SPT-CARRIER-SKU-$s", 50.0)
        makeDeletable(carrierStockId, daysAgo = 10)
        makeUnitLoadTerminal(carrierUlId)

        val childUlId = createUnitLoad("UL-SPT-CHILD-$s")
        setCarrier(childUlId, carrierUlId)

        // An unrelated candidate for the SAME client, to prove the rest of the tick still
        // completes rather than the whole transaction rolling back.
        val otherUlId = createUnitLoad("UL-SPT-OTHER-$s")
        val otherIu = createItemUnit("SPT-OTH-${s.toString().takeLast(10)}")
        val otherPid = createProduct("SPT-OTHER-SKU-$s", otherIu)
        val otherStockId = createStock(otherUlId, otherPid, "SPT-OTHER-SKU-$s", 50.0)
        makeDeletable(otherStockId, daysAgo = 10)
        makeUnitLoadTerminal(otherUlId)

        purgeService.purge(ACME)

        // The carrier's own stock is purged normally -- it is the UNIT LOAD that is blocked,
        // not its (already-gone) stock.
        assertThat(stockUnitRepository.findById(carrierStockId)).isNull()
        assertThat(unitLoadRepository.findById(carrierUlId))
            .`as`("a unit load still carrying a live child must survive purge, not throw an FK violation")
            .isNotNull()

        // The rest of this client's tick completed in the same transaction.
        assertThat(stockUnitRepository.findById(otherStockId))
            .`as`("an unrelated candidate in the same tick must still be purged")
            .isNull()
        assertThat(unitLoadRepository.findById(otherUlId))
            .`as`("an unrelated candidate's unit load in the same tick must still be purged")
            .isNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load holding surviving stock is left alone`() {
        setRetentionDays(ACME, 7)
        val s = System.nanoTime()
        val ulId = createUnitLoad("UL-SPT-MIX-$s")
        val iu = createItemUnit("SPT-MIX-${s.toString().takeLast(10)}")
        val pid = createProduct("SPT-MIX-SKU-$s", iu)
        val goneId = createStock(ulId, pid, "SPT-MIX-SKU-$s", 10.0)
        val liveId = createStock(ulId, pid, "SPT-MIX-SKU-$s", 40.0)
        makeDeletable(goneId, daysAgo = 10)
        // ulId is deliberately never flipped terminal: it still carries liveId's live stock.

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(goneId)).isNull()
        assertThat(stockUnitRepository.findById(liveId)).isNotNull()
        assertThat(unitLoadRepository.findById(ulId))
            .`as`("a unit load still holding live stock must never be a purge candidate")
            .isNotNull()
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `each purge writes a journal row and an outbox row before the delete`() {
        setRetentionDays(ACME, 7)
        val s = System.nanoTime()
        val ulLabel = "UL-SPT-AUDIT-$s"
        val ulId = createUnitLoad(ulLabel)
        val iu = createItemUnit("SPT-AUDIT-${s.toString().takeLast(10)}")
        val pid = createProduct("SPT-AUDIT-SKU-$s", iu)
        val suId = createStock(ulId, pid, "SPT-AUDIT-SKU-$s", 50.0)
        makeDeletable(suId, daysAgo = 10)
        makeUnitLoadTerminal(ulId)

        purgeService.purge(ACME)

        // Stock-unit creation itself already wrote a CREATED(1) journal row against suId, so
        // scope this to the DELETED(8) purge row specifically, not "any row for this id".
        val stockJournalRows = journalRepository.find(
            "stockUnitId = ?1 and recordType = ?2",
            suId, JournalRecordType.DELETED.code,
        ).list()
        assertThat(stockJournalRows).hasSize(1)

        val stockOutboxRows = outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", suId, "StockUnitPurged",
        ).list()
        assertThat(stockOutboxRows).hasSize(1)

        val ulOutboxRows = outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", ulId, "UnitLoadPurged",
        ).list()
        assertThat(ulOutboxRows).hasSize(1)

        // Important 1 (fix round 1): the unit-load purge's own journal row -- exercised by
        // JournalService.recordUnitLoadPurged. Keyed on `stockUnitId is null` (not just
        // fromUnitLoad = label): recordStockUnitPurged ALSO stamps fromUnitLoad with the same
        // label on the STOCK row's own journal entry, so fromUnitLoad alone matches both rows --
        // stockUnitId is what distinguishes the unit-load-only entry recordUnitLoadPurged writes
        // (there is no surviving stock unit to key it on, so it never sets that column).
        val ulJournalRows = journalRepository.find(
            "clientId = ?1 and recordType = ?2 and fromUnitLoad = ?3 and stockUnitId is null",
            ACME, JournalRecordType.DELETED.code, ulLabel,
        ).list()
        assertThat(ulJournalRows)
            .`as`("the unit-load purge must write its own journal row, not just the stock unit's")
            .hasSize(1)
    }

    @Test
    @TestSecurity(user = "spt-ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `the purge only ever touches the client it was called for`() {
        setRetentionDays(ACME, 7)
        setRetentionDays(GLOBEX, 7)
        // Direct unit-load + stock creation (no product/item-unit calls, which need
        // product-write) -- same OPS-principal shape as TransferReservationTest's cross-client
        // test: itemDataId is just a bare Long, no FK behind it.
        val s = System.nanoTime()
        val acmeUl = createUnitLoad("UL-SPT-CID-ACME-$s", clientId = ACME)
        val acmeStockId = createStock(acmeUl, s, "SPT-CID-ACME-$s", 50.0)
        val globexUl = createUnitLoad("UL-SPT-CID-GLOBEX-$s", clientId = GLOBEX)
        val globexStockId = createStock(globexUl, s, "SPT-CID-GLOBEX-$s", 50.0)
        makeDeletable(acmeStockId, daysAgo = 10)
        makeDeletable(globexStockId, daysAgo = 10)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(acmeStockId))
            .`as`("the requested client's DELETABLE stock must be purged")
            .isNull()
        assertThat(stockUnitRepository.findById(globexStockId))
            .`as`("purging one client must never touch another client's stock")
            .isNotNull()

        cleanupGlobexFixture(globexStockId, globexUl)
    }

    @Test
    @TestSecurity(user = "spt", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the remaining live-work blockers (transport order, pick order target, open count session) all survive purge`() {
        setRetentionDays(ACME, 7)

        // (a) non-terminal transport order blocks a stock unit's purge.
        val (_, toStockId) = seedProductWithStock(50.0)
        val toUnitLoadId = stockUnitRepository.findById(toStockId)!!.unitLoad.id!!
        persistOpenTransportOrder(toStockId, toUnitLoadId, ACME)
        makeDeletable(toStockId, daysAgo = 10)

        // (b) a pick order's target container blocks a unit load's purge.
        val s = System.nanoTime()
        val pickTargetUlId = createUnitLoad("UL-SPT-PKTGT-$s")
        val iu1 = createItemUnit("SPT-PKTGT-${s.toString().takeLast(10)}")
        val pid1 = createProduct("SPT-PKTGT-SKU-$s", iu1)
        val pickTargetStockId = createStock(pickTargetUlId, pid1, "SPT-PKTGT-SKU-$s", 50.0)
        makeDeletable(pickTargetStockId, daysAgo = 10)
        makeUnitLoadTerminal(pickTargetUlId)
        persistPickOrderTargeting(pickTargetUlId, ACME)

        // (c) a count line in an open session blocks a unit load's purge.
        val s2 = System.nanoTime()
        val countUlId = createUnitLoad("UL-SPT-CNT-$s2")
        val iu2 = createItemUnit("SPT-CNT-${s2.toString().takeLast(10)}")
        val pid2 = createProduct("SPT-CNT-SKU-$s2", iu2)
        val countStockId = createStock(countUlId, pid2, "SPT-CNT-SKU-$s2", 50.0)
        makeDeletable(countStockId, daysAgo = 10)
        makeUnitLoadTerminal(countUlId)
        persistOpenCountLineReferencing(countUlId, ACME)

        purgeService.purge(ACME)

        assertThat(stockUnitRepository.findById(toStockId))
            .`as`("a stock unit a non-terminal transport order still sources must survive purge")
            .isNotNull()
        assertThat(unitLoadRepository.findById(pickTargetUlId))
            .`as`("a unit load a pick order still targets must survive purge")
            .isNotNull()
        assertThat(unitLoadRepository.findById(countUlId))
            .`as`("a unit load an open-session count line still references must survive purge")
            .isNotNull()
    }
}
