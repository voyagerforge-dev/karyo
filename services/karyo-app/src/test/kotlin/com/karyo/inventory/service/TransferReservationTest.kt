package com.karyo.inventory.service

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.orders.dto.CreateDeliveryOrderLineRequest
import com.karyo.orders.dto.CreateDeliveryOrderRequest
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.service.OrderService
import com.karyo.orders.vo.OrderState
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.narayana.jta.QuarkusTransaction
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

private const val ACME = 1L
private const val GLOBEX = 2L

/**
 * Row 13: `POST /api/v1/stock-units/{id}/transfer-reservation`
 * ([ReservationTransferService.transferReservation]) end to end with real beans -- inventory's
 * reservedAmount arithmetic, the orders-side `order_line_reservations` repoint, and the
 * fulfillment-side open-pick repoint, all inside one transaction.
 *
 * `ReservationTransferTest.kt` (singular "reservation") already exists and, despite the name,
 * exercises `reserveStock`/`releaseReservation`/`transferStock` -- it is not prior art here.
 */
@QuarkusTest
class TransferReservationTest {

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var pickOrderService: PickOrderService

    @Inject
    lateinit var pickRepository: PickRepository

    @Inject
    lateinit var pickOrderRepository: PickOrderRepository

    @Inject
    lateinit var orderLineReservationRepository: OrderLineReservationRepository

    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var unitLoadService: UnitLoadService

    @Inject
    lateinit var reservationTransferService: ReservationTransferService

    @Inject
    lateinit var storageLocationRepository: StorageLocationRepository

    // ── REST seeding helpers (existing services, matching sibling-test style) ────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Transfer Reservation Product","itemUnitId":$itemUnitId}""")
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

    private fun lockStock(stockUnitId: Long) {
        given().contentType(ContentType.JSON)
            .body("""{"lockType":1}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/lock")
            .then().statusCode(200)
    }

    private fun stockOf(stockUnitId: Long): Pair<Double, Double> {
        val json = given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200).extract().jsonPath()
        return json.getDouble("amount") to json.getDouble("reservedAmount")
    }

    /**
     * Persists a synthetic terminal pick history directly (PickOrder already at PICKED, so
     * DefaultPickCancelPort.cancelOpenWorkForDeliveryOrder's force-finish loop -- state < PICKED
     * -- never touches it): [plannedAmount] of [itemDataId] on [sourceStockUnitId], attributed to
     * [orderId]/[lineId]. Shared by witnesses that need controlled terminal-pick history without
     * driving the full confirm/shortfall pipeline (which would net the WHOLE plannedAmount off
     * reservedAmount, not just a chosen portion).
     */
    private fun persistTerminalPick(
        orderId: Long,
        lineId: Long,
        itemDataId: Long,
        itemNumber: String,
        sourceStockUnitId: Long,
        plannedAmount: BigDecimal,
    ) {
        QuarkusTransaction.requiringNew().call {
            val po = PickOrder().apply {
                clientId = ACME
                pickOrderNumber = "PO-TERM-${System.nanoTime()}"
                deliveryOrderId = orderId
                state = PickState.PICKED.code
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

    private fun createOrder(itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"customerName":"Transfer Reservation Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun releaseOrder(orderId: Long): io.restassured.response.ValidatableResponse =
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)

    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /**
     * Cross-client witness setup: `CreateLocationRequest` carries no `clientId` field (unlike
     * unit loads), so a PACK_STAGING location always inherits the SEEDING request's ambient JWT
     * client. `DefaultStagingLocationLookup.findPackStaging` scopes by the ACTING order's own
     * clientId, so a location seeded once under the OPS request's ambient client (0) is invisible
     * to GLOBEX's or ACME's own releaseToPicking call -- forced directly onto the entity instead.
     *
     * Returns the forced location's id so the caller can clean it up (see
     * [cleanupPackStaging]): unlike every other fixture in this test, this row's `clientId` is
     * mutated directly onto the entity rather than scoped by the ambient JWT/REST write path, so
     * it survives as a real GLOBEX- or ACME-owned `PACK_STAGING` location in the shared Dev
     * Services database. `StagingLocationLookupTest`'s "another client sees no staging location"
     * check (`findPackStaging(2L)` expecting null, GLOBEX == 2 here) fails if a GLOBEX row from
     * this test is still present when the suite runs both classes -- defect-burndown-5 gate.
     */
    private fun seedPackStagingFor(clientId: Long): Long {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        val locationId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")
        QuarkusTransaction.requiringNew().run {
            storageLocationRepository.findById(locationId)!!.clientId = clientId
        }
        return locationId
    }

    /**
     * Test-isolation fix (defect-burndown-5 gate): deletes a location seeded by
     * [seedPackStagingFor], undoing the direct-mutation fixture so it does not outlive this
     * test in the shared Dev Services database. Same rationale/precedent as
     * `StockPurgeServiceTest.cleanupGlobexFixture`.
     */
    @Transactional
    fun cleanupPackStaging(locationId: Long) {
        storageLocationRepository.deleteById(locationId)
    }

    /** One product + a fully-available source stock unit of [stockAmount]. */
    private fun seedProductWithStock(stockAmount: Double): Pair<Long, Long> {
        val s = System.nanoTime()
        val iu = createItemUnit("TR-${s.toString().takeLast(10)}")
        val number = "TR-SKU-$s"
        val pid = createProduct(number, iu)
        val ul = createUnitLoad("UL-TR-$s")
        val stockUnitId = createStock(ul, pid, number, stockAmount)
        return pid to stockUnitId
    }

    private fun transferReservation(fromId: Long, toId: Long, amount: BigDecimal? = null): io.restassured.response.ValidatableResponse {
        val amountField = if (amount != null) ""","amount":$amount""" else ""
        return given().contentType(ContentType.JSON)
            .body("""{"targetStockUnitId":$toId$amountField}""")
            .`when`().post("/api/v1/stock-units/$fromId/transfer-reservation")
            .then()
    }

    // ── Tests ──────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a full transfer moves the reserved amount and repoints the order line reservation`() {
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        val orderId = createOrder(pid, amount = 40.0)
        val release = releaseOrder(orderId)
        val lineId = release.extract().jsonPath().getLong("order.lines[0].id")
        tenantContext.clientId = ACME

        val targetId = createStock(createUnitLoad("UL-TR-TGT-${System.nanoTime()}"), pid, "TR-SKU-TGT", 100.0)

        val response = transferReservation(sourceId, targetId)
        response.statusCode(200)
        assertThat(response.extract().jsonPath().getDouble("reservedAmount")).isEqualTo(40.0)

        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        val (targetAmount, targetReserved) = stockOf(targetId)
        assertThat(sourceAmount).isEqualTo(100.0)
        assertThat(sourceReserved).isEqualTo(0.0)
        assertThat(targetAmount).isEqualTo(100.0)
        assertThat(targetReserved).isEqualTo(40.0)

        val reservation = orderLineReservationRepository.findByLineId(lineId).single()
        assertThat(reservation.stockUnitId)
            .`as`("the order-line reservation must follow the reserved amount to the target")
            .isEqualTo(targetId)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit whose reservation has a terminal pick splits, moving only the live remainder`() {
        // defect-burndown-5 row 1485, superseding the old refuse-whole ruling (see task-2's
        // fix-round-1 addendum): the terminal-consumed portion of order1's reservation row stays
        // keyed to the SOURCE stock unit forever (unhandledRemainders nets terminal-pick totals
        // against sourceStockUnitId, which never moves); only order2's live remainder transfers.
        seedPackStaging()
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)

        val order1Id = createOrder(pid, amount = 30.0)
        val release1 = releaseOrder(order1Id)
        val line1Id = release1.extract().jsonPath().getLong("order.lines[0].id")
        val order2Id = createOrder(pid, amount = 20.0)
        val release2 = releaseOrder(order2Id)
        val line2Id = release2.extract().jsonPath().getLong("order.lines[0].id")
        tenantContext.clientId = ACME

        val pickOrder1 = pickOrderService.releaseToPicking(order1Id).single()
        val terminalPick = pickRepository.findByPickOrderId(pickOrder1.id!!).single()
        pickOrderService.confirmPick(terminalPick.id!!, BigDecimal("30"), targetUnitLoadId = null)

        // order2's pick stays open; order1's OrderLineReservation row is still recorded against
        // sourceId (it is deleted only at order cancel, never on its pick going terminal) -- that
        // is the stale row the split leaves behind.
        pickOrderService.releaseToPicking(order2Id).single()

        val (sourceAmountBefore, sourceReservedBefore) = stockOf(sourceId)
        assertThat(sourceAmountBefore).isEqualTo(70.0)
        assertThat(sourceReservedBefore)
            .`as`("order1's slice was released at confirm; only order2's slice remains reserved")
            .isEqualTo(20.0)

        val targetId = createStock(createUnitLoad("UL-TR-SPLIT-${System.nanoTime()}"), pid, "TR-SKU-SPLIT", 50.0)

        val response = transferReservation(sourceId, targetId)
        response.statusCode(200)
        assertThat(response.extract().jsonPath().getDouble("reservedAmount")).isEqualTo(20.0)

        entityManager.clear()
        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        assertThat(sourceAmount).`as`("amount never moves on a reservation transfer").isEqualTo(70.0)
        assertThat(sourceReserved).`as`("order1's terminal-consumed slice already nets to zero").isEqualTo(0.0)
        val (targetAmount, targetReserved) = stockOf(targetId)
        assertThat(targetAmount).isEqualTo(50.0)
        assertThat(targetReserved).`as`("order2's live remainder followed the transfer").isEqualTo(20.0)

        // order1's row stays on the source, unshrunk (its terminal portion equals its whole
        // amount, so it is left entirely alone -- not even touched).
        val order1Row = orderLineReservationRepository.findByLineId(line1Id).single()
        assertThat(order1Row.stockUnitId).`as`("order1's consumed portion still keys to S").isEqualTo(sourceId)
        assertThat(order1Row.amount).isEqualByComparingTo(BigDecimal("30"))
        // order2's row followed the live remainder to the target.
        val order2Row = orderLineReservationRepository.findByLineId(line2Id).single()
        assertThat(order2Row.stockUnitId).`as`("order2's row now keys to T").isEqualTo(targetId)
        assertThat(order2Row.amount).isEqualByComparingTo(BigDecimal("20"))
    }

    @Test
    @TestSecurity(
        user = "ops",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `a foreign client's terminal-only row stays invisible and untouched after a changeClient reassignment (third-party-order, A2)`() {
        // Adjudication A2's mandatory "third-party-order" witness: an order of client B (GLOBEX)
        // holds the terminal slice while client A (ACME) aggregates. GLOBEX's terminal pick
        // already released its portion of reservedAmount to zero, so UnitLoadService.changeClient
        // (which only refuses a LIVE claim) can reassign the stock unit to ACME. GLOBEX's
        // OrderLineReservation row is never touched by that reassignment -- it still keys to the
        // stock unit, but its line belongs to a DeliveryOrder of a DIFFERENT clientId, so every
        // ref-mover query (which scopes to the stock unit's CURRENT owner) simply never sees it.
        val globexStagingLocId = seedPackStagingFor(GLOBEX)
        val s = System.nanoTime()
        val itemUnitId = createItemUnit("TR-3P-${s.toString().takeLast(10)}")
        val number = "TR-3P-SKU-$s"
        val pid = createProduct(number, itemUnitId)
        val sourceUlId = createUnitLoad("UL-TR-3P-SRC-$s", clientId = GLOBEX)
        val sourceId = createStock(sourceUlId, pid, number, 100.0)

        // OPS stays unscoped for the whole test: orderService.create/release take clientId
        // explicitly, but the internal ProductLookup.findById call reads the AMBIENT
        // TenantContext's readScope, which must permit the shared product regardless of which
        // client's order is being built.
        tenantContext.clientId = GLOBEX
        tenantContext.principalKind = PrincipalKind.OPS
        val o1 = orderService.create(
            CreateDeliveryOrderRequest(
                customerName = "GLOBEX Customer",
                lines = listOf(CreateDeliveryOrderLineRequest(itemDataId = pid, amount = BigDecimal("30"))),
            ),
            GLOBEX,
        )
        val release1 = orderService.release(o1.id, GLOBEX)
        val line1Id = release1.order.lines.single().id
        val pickOrder1 = pickOrderService.releaseToPicking(o1.id).single()
        val terminalPick = pickRepository.findByPickOrderId(pickOrder1.id!!).single()
        pickOrderService.confirmPick(terminalPick.id!!, BigDecimal("30"), targetUnitLoadId = null)
        entityManager.clear()
        assertThat(stockOf(sourceId).second).`as`("GLOBEX's terminal pick released its slice").isEqualTo(0.0)

        // assertUnencumbered only blocks a LIVE reservation -- none remains, so the reassignment
        // to ACME succeeds even though the stale row is still recorded.
        unitLoadService.changeClient(sourceUlId, ACME, activityCode = null, tenantContext)
        val acmeStagingLocId = seedPackStagingFor(ACME)

        tenantContext.clientId = ACME
        val o2 = orderService.create(
            CreateDeliveryOrderRequest(
                customerName = "ACME Customer",
                lines = listOf(CreateDeliveryOrderLineRequest(itemDataId = pid, amount = BigDecimal("20"))),
            ),
            ACME,
        )
        val release2 = orderService.release(o2.id, ACME)
        val line2Id = release2.order.lines.single().id
        assertThat(stockOf(sourceId).second).isEqualTo(20.0)

        val targetId = createStock(createUnitLoad("UL-TR-3P-TGT-$s", clientId = ACME), pid, "TR-3P-SKU-TGT-$s", 100.0)

        val result = reservationTransferService.transferReservation(sourceId, targetId, amount = null, tenant = tenantContext)
        assertThat(result.reservedAmount).isEqualByComparingTo(BigDecimal("20"))
        entityManager.clear()

        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        assertThat(sourceAmount).isEqualTo(70.0)
        assertThat(sourceReserved).isEqualTo(0.0)
        val (_, targetReserved) = stockOf(targetId)
        assertThat(targetReserved).isEqualTo(20.0)

        // GLOBEX's row is invisible to ACME-scoped ref movers -- it stays pointing at the source,
        // untouched, forever.
        val o1Row = orderLineReservationRepository.findByLineId(line1Id).single()
        assertThat(o1Row.stockUnitId).isEqualTo(sourceId)
        assertThat(o1Row.amount).isEqualByComparingTo(BigDecimal("30"))
        // ACME's row followed the live remainder to the target.
        val o2Row = orderLineReservationRepository.findByLineId(line2Id).single()
        assertThat(o2Row.stockUnitId).isEqualTo(targetId)
        assertThat(o2Row.amount).isEqualByComparingTo(BigDecimal("20"))

        // Test-isolation fix (defect-burndown-5 gate): these two locations were force-mutated
        // onto GLOBEX/ACME directly (see seedPackStagingFor's KDoc), so unlike every other
        // fixture in this test they would otherwise outlive it in the shared Dev Services
        // database and pollute StagingLocationLookupTest's tenant-scoping assertion.
        cleanupPackStaging(globexStagingLocId)
        cleanupPackStaging(acmeStagingLocId)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a single reservation row partially consumed by a terminal pick shrinks in place and the remainder moves`() {
        // Witness (c): ONE OrderLineReservation row (amount 50) whose slice was only PARTIALLY
        // resolved by a terminal pick (plannedAmount 20) -- the row itself was never touched by
        // that pick going terminal. The split must shrink the row to the terminal portion (20)
        // in place on S and persist a NEW row carrying the live remainder (30) on T. Review
        // finding 2: followed by a cancel proving unhandledRemainders reconciles the SHRUNK
        // source row and the NEW target row end to end.
        seedPackStaging()
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        val orderId = createOrder(pid, amount = 50.0)
        val release = releaseOrder(orderId)
        val lineId = release.extract().jsonPath().getLong("order.lines[0].id")
        tenantContext.clientId = ACME

        // Simulate the terminal-pick history directly: a PickOrder+Pick already at PICKED (not
        // driven through releaseToPicking/confirmPick, which would create a SECOND, still-open
        // pick for the other 30 and, at cancel, try to release it against a stock unit the split
        // already emptied). The reservedAmount side-effect (pickStock + releaseUnpickedReservation
        // together always net a terminal pick's full plannedAmount off reservedAmount) is
        // reproduced explicitly via releaseReservation below.
        persistTerminalPick(orderId, lineId, pid, "TR-SKU-PARTIAL", sourceId, BigDecimal("20"))
        stockService.releaseReservation(sourceId, BigDecimal("20"), "test-setup", tenantContext)

        val (_, sourceReservedBefore) = stockOf(sourceId)
        assertThat(sourceReservedBefore).isEqualTo(30.0)

        val targetId = createStock(createUnitLoad("UL-TR-PARTIAL-TGT-${System.nanoTime()}"), pid, "TR-SKU-PARTIAL-TGT", 100.0)

        transferReservation(sourceId, targetId).statusCode(200)
        entityManager.clear()

        val (_, sourceReserved) = stockOf(sourceId)
        assertThat(sourceReserved).isEqualTo(0.0)
        val (_, targetReserved) = stockOf(targetId)
        assertThat(targetReserved).isEqualTo(30.0)

        val rows = orderLineReservationRepository.findByLineId(lineId)
        val onSource = rows.single { it.stockUnitId == sourceId }
        assertThat(onSource.amount)
            .`as`("the row shrinks in place to exactly the terminal portion")
            .isEqualByComparingTo(BigDecimal("20"))
        val onTarget = rows.single { it.stockUnitId == targetId }
        assertThat(onTarget.amount)
            .`as`("a NEW row carries the live remainder to the target")
            .isEqualByComparingTo(BigDecimal("30"))

        // Review finding 2: cancel now, and prove unhandledRemainders reconciles BOTH rows
        // correctly -- the shrunk source row (20) nets exactly against the terminal pick's own
        // plannedAmount (20), releasing nothing there, while the live remainder row on the
        // target (30) was never terminal-consumed and releases in full.
        val response = orderService.cancel(orderId, ACME)
        entityManager.clear()

        assertThat(response.state).isEqualTo(OrderState.CANCELED.code)
        val (_, sourceReservedAfterCancel) = stockOf(sourceId)
        assertThat(sourceReservedAfterCancel)
            .`as`("the shrunk source row already nets to zero against its terminal pick -- no release, no over-release")
            .isEqualTo(0.0)
        val (_, targetReservedAfterCancel) = stockOf(targetId)
        assertThat(targetReservedAfterCancel)
            .`as`("the live remainder row on the target was genuinely still live and releases in full")
            .isEqualTo(0.0)
        assertThat(orderLineReservationRepository.findByLineId(lineId))
            .`as`("both the shrunk source row and the new target row are deleted at cancel")
            .isEmpty()
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `requesting other than the live remainder on a mixed source is refused with 409 naming the live amount`() {
        // Witness (d): amount discipline. A mixed source (order1 terminal 30, order2 live 20)
        // only accepts a request equal to the live remainder (20) -- more or less both refuse,
        // and the refusal message names the live amount.
        seedPackStaging()
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        val order1Id = createOrder(pid, amount = 30.0)
        releaseOrder(order1Id)
        val order2Id = createOrder(pid, amount = 20.0)
        releaseOrder(order2Id)
        tenantContext.clientId = ACME

        val pickOrder1 = pickOrderService.releaseToPicking(order1Id).single()
        val terminalPick = pickRepository.findByPickOrderId(pickOrder1.id!!).single()
        pickOrderService.confirmPick(terminalPick.id!!, BigDecimal("30"), targetUnitLoadId = null)
        pickOrderService.releaseToPicking(order2Id).single()

        val targetId = createStock(createUnitLoad("UL-TR-DISC-${System.nanoTime()}"), pid, "TR-SKU-DISC", 50.0)

        val tooMuch = transferReservation(sourceId, targetId, BigDecimal("25"))
        tooMuch.statusCode(409)
        assertThat(tooMuch.extract().jsonPath().getString("detail")).contains("20")

        val tooLittle = transferReservation(sourceId, targetId, BigDecimal("10"))
        tooLittle.statusCode(409)
        assertThat(tooLittle.extract().jsonPath().getString("detail")).contains("20")

        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        assertThat(sourceAmount).`as`("amount untouched by either refusal").isEqualTo(70.0)
        assertThat(sourceReserved).`as`("reservedAmount untouched by either refusal").isEqualTo(20.0)
        val (targetAmount, targetReserved) = stockOf(targetId)
        assertThat(targetAmount).isEqualTo(50.0)
        assertThat(targetReserved).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a slice backed by two rows folds before subtracting terminal, not per row (review finding 1)`() {
        // Review finding 1: OrderService.unhandledRemainders folds reservation rows by (lineId,
        // stockUnitId) BEFORE subtracting the slice's terminal total -- a slice can be backed by
        // MORE THAN ONE row (release short-reserve, stock topped up, retry-reservation reserves
        // again; see unhandledRemainders' own KDoc). Reproduced via the REAL production path:
        // an order for 30 against a stock unit that initially only has 15 available creates row1
        // (15, shortage 15); topping the stock up and retrying creates row2 (15) on the SAME
        // stock unit -- two rows, one slice, 30 total. A synthetic terminal history of 20 (a
        // PickOrder+Pick already at PICKED, so cancel's force-finish loop never touches it) means
        // the TRUE live remainder is 30 - 20 = 10, not the 0 a naive per-row subtraction
        // (20 subtracted from EACH row) would floor both rows to.
        seedPackStaging()
        val s = System.nanoTime()
        val itemUnitId = createItemUnit("TR-SLICE-${s.toString().takeLast(10)}")
        val number = "TR-SLICE-SKU-$s"
        val pid = createProduct(number, itemUnitId)
        val sourceId = createStock(createUnitLoad("UL-TR-SLICE-$s"), pid, number, 15.0)

        val orderId = createOrder(pid, amount = 30.0)
        val release = releaseOrder(orderId)
        val lineId = release.extract().jsonPath().getLong("order.lines[0].id")
        assertThat(release.extract().jsonPath().getDouble("order.lines[0].reservedAmount"))
            .`as`("only the 15 available reserves on release -- row1")
            .isEqualTo(15.0)
        tenantContext.clientId = ACME

        // Top the stock up so the shortfall (15) can be retried against the SAME stock unit --
        // it is the item's only stock unit, so the retry has nowhere else to go.
        stockService.adjustAmount(sourceId, BigDecimal("30"), "test-setup", tenantContext)
        given().`when`().post("/api/v1/delivery-orders/$orderId/retry-reservation").then().statusCode(200)
        entityManager.clear()
        val rowsBeforeSplit = orderLineReservationRepository.findByLineId(lineId)
        assertThat(rowsBeforeSplit).`as`("two rows now back this one slice").hasSize(2)
        assertThat(rowsBeforeSplit.sumOf { it.amount }).isEqualByComparingTo(BigDecimal("30"))
        assertThat(stockOf(sourceId).second).isEqualTo(30.0)

        // Synthetic terminal history: 20 already consumed on this slice (same technique as
        // witness (c) -- see persistTerminalPick's KDoc).
        persistTerminalPick(orderId, lineId, pid, number, sourceId, BigDecimal("20"))
        stockService.releaseReservation(sourceId, BigDecimal("20"), "test-setup", tenantContext)
        val (_, sourceReservedBefore) = stockOf(sourceId)
        assertThat(sourceReservedBefore)
            .`as`("the true live remainder (30 total - 20 terminal = 10), not 0")
            .isEqualTo(10.0)

        val targetId = createStock(createUnitLoad("UL-TR-SLICE-TGT-${System.nanoTime()}"), pid, "TR-SLICE-SKU-TGT-$s", 100.0)

        // The true live remainder (10) transfers -- a pre-fix per-row computation would have
        // reported live=0 here and refused this with 409, permanently stranding the 10 units.
        val response = transferReservation(sourceId, targetId)
        response.statusCode(200)
        assertThat(response.extract().jsonPath().getDouble("reservedAmount")).isEqualTo(10.0)
        entityManager.clear()

        val (_, sourceReserved) = stockOf(sourceId)
        assertThat(sourceReserved).`as`("the terminal-consumed 20 stays keyed to the source").isEqualTo(0.0)
        val (_, targetReserved) = stockOf(targetId)
        assertThat(targetReserved).`as`("only the true live remainder followed the transfer").isEqualTo(10.0)

        val rows = orderLineReservationRepository.findByLineId(lineId)
        val onSource = rows.filter { it.stockUnitId == sourceId }
        assertThat(onSource.sumOf { it.amount })
            .`as`("the terminal portion (20) stays on the source, split across its original rows")
            .isEqualByComparingTo(BigDecimal("20"))
        val onTarget = rows.filter { it.stockUnitId == targetId }
        assertThat(onTarget.sumOf { it.amount })
            .`as`("the live remainder (10) moved to the target")
            .isEqualByComparingTo(BigDecimal("10"))

        // Cancel nets to zero on BOTH units: the source's 20 already matches its terminal
        // history exactly (no release), and the target's 10 was genuinely still live (released
        // in full).
        val cancelResponse = orderService.cancel(orderId, ACME)
        entityManager.clear()
        assertThat(cancelResponse.state).isEqualTo(OrderState.CANCELED.code)
        assertThat(stockOf(sourceId).second).`as`("nets to zero on the source").isEqualTo(0.0)
        assertThat(stockOf(targetId).second).`as`("nets to zero on the target").isEqualTo(0.0)
        assertThat(orderLineReservationRepository.findByLineId(lineId)).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a full transfer repoints an open pick when none of the source's picks are terminal`() {
        // The previously-working case, reconfirmed after the Critical fix above: a source whose
        // pick history is entirely open (no terminal slice at all) still transfers normally.
        seedPackStaging()
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        val orderId = createOrder(pid, amount = 20.0)
        releaseOrder(orderId)
        tenantContext.clientId = ACME

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val openPick = pickRepository.findByPickOrderId(pickOrder.id!!).single()

        val targetId = createStock(createUnitLoad("UL-TR-OPEN-${System.nanoTime()}"), pid, "TR-SKU-OPEN", 50.0)

        transferReservation(sourceId, targetId).statusCode(200)
        // The pick above was loaded via a direct injected call before the REST transfer ran, so
        // it is cached in this test's own persistence context (L1). The REST call's bulk JPQL
        // update bypasses that cache -- clear it so the read below hits the database, not the
        // stale pre-transfer object (same idiom as PickTopUpServiceTest).
        entityManager.clear()

        assertThat(pickRepository.findById(openPick.id!!)!!.sourceStockUnitId)
            .`as`("a non-terminal pick is a live claim -- it follows the reservation")
            .isEqualTo(targetId)
        val (_, sourceReserved) = stockOf(sourceId)
        assertThat(sourceReserved).isEqualTo(0.0)
        val (_, targetReserved) = stockOf(targetId)
        assertThat(targetReserved).isEqualTo(20.0)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `transferring a reservation to the same stock unit is refused with 400`() {
        val (_, sourceId) = seedProductWithStock(stockAmount = 100.0)
        tenantContext.clientId = ACME
        stockService.reserveStock(sourceId, BigDecimal("30"), "test-setup", tenantContext)

        transferReservation(sourceId, sourceId).statusCode(400)

        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        assertThat(sourceAmount).isEqualTo(100.0)
        assertThat(sourceReserved).isEqualTo(30.0)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `transferring more than the source has reserved is refused with 409`() {
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        tenantContext.clientId = ACME
        stockService.reserveStock(sourceId, BigDecimal("30"), "test-setup", tenantContext)
        val targetId = createStock(createUnitLoad("UL-TR-C-${System.nanoTime()}"), pid, "TR-SKU-C", 100.0)

        transferReservation(sourceId, targetId, BigDecimal("50")).statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a target without enough unreserved amount is refused with 409`() {
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        tenantContext.clientId = ACME
        stockService.reserveStock(sourceId, BigDecimal("30"), "test-setup", tenantContext)
        val targetId = createStock(createUnitLoad("UL-TR-D-${System.nanoTime()}"), pid, "TR-SKU-D", 20.0)

        transferReservation(sourceId, targetId).statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a target holding a different item is refused with 422`() {
        val (_, sourceId) = seedProductWithStock(stockAmount = 100.0)
        tenantContext.clientId = ACME
        stockService.reserveStock(sourceId, BigDecimal("30"), "test-setup", tenantContext)
        val (otherPid, _) = seedProductWithStock(stockAmount = 100.0)
        val targetId = createStock(createUnitLoad("UL-TR-E-${System.nanoTime()}"), otherPid, "TR-SKU-E", 100.0)

        transferReservation(sourceId, targetId).statusCode(422)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `a target owned by a different client is refused with 409`() {
        val s = System.nanoTime()
        val itemDataId = s
        val sourceUl = createUnitLoad("UL-TR-F-SRC-$s", clientId = ACME)
        val sourceId = createStock(sourceUl, itemDataId, "TR-SKU-F", 100.0)
        val targetUl = createUnitLoad("UL-TR-F-TGT-$s", clientId = GLOBEX)
        val targetId = createStock(targetUl, itemDataId, "TR-SKU-F", 100.0)

        tenantContext.clientId = 0L
        tenantContext.principalKind = PrincipalKind.OPS
        stockService.reserveStock(sourceId, BigDecimal("10"), "test-setup", tenantContext)

        transferReservation(sourceId, targetId).statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a locked target is refused with 409`() {
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        tenantContext.clientId = ACME
        stockService.reserveStock(sourceId, BigDecimal("30"), "test-setup", tenantContext)
        val targetId = createStock(createUnitLoad("UL-TR-G-${System.nanoTime()}"), pid, "TR-SKU-G", 100.0)
        lockStock(targetId)

        transferReservation(sourceId, targetId).statusCode(409)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a partial transfer while references exist is refused with 409`() {
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        val orderId = createOrder(pid, amount = 40.0)
        releaseOrder(orderId)
        tenantContext.clientId = ACME
        val targetId = createStock(createUnitLoad("UL-TR-H-${System.nanoTime()}"), pid, "TR-SKU-H", 100.0)

        // 20 is less than the source's full reservedAmount (40) -- a partial move while the
        // order-line reservation still references the source.
        transferReservation(sourceId, targetId, BigDecimal("20")).statusCode(409)

        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        assertThat(sourceAmount).isEqualTo(100.0)
        assertThat(sourceReserved).isEqualTo(40.0)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `every refusal leaves both stock units byte for byte unchanged`() {
        val (pid, sourceId) = seedProductWithStock(stockAmount = 100.0)
        tenantContext.clientId = ACME
        stockService.reserveStock(sourceId, BigDecimal("30"), "test-setup", tenantContext)

        val targetValidId = createStock(createUnitLoad("UL-TR-I-VALID-${System.nanoTime()}"), pid, "TR-SKU-I-V", 100.0)
        val targetSmallId = createStock(createUnitLoad("UL-TR-I-SMALL-${System.nanoTime()}"), pid, "TR-SKU-I-S", 10.0)
        val (otherPid, _) = seedProductWithStock(stockAmount = 100.0)
        val targetWrongItemId = createStock(createUnitLoad("UL-TR-I-WRONG-${System.nanoTime()}"), otherPid, "TR-SKU-I-W", 100.0)
        val targetLockedId = createStock(createUnitLoad("UL-TR-I-LOCK-${System.nanoTime()}"), pid, "TR-SKU-I-L", 100.0)
        lockStock(targetLockedId)

        // Over-amount: 1000 exceeds the source's reservedAmount (30).
        transferReservation(sourceId, targetValidId, BigDecimal("1000")).statusCode(409)
        // Insufficient target capacity: a full transfer (30) exceeds targetSmall's available (10).
        transferReservation(sourceId, targetSmallId).statusCode(409)
        // Wrong item.
        transferReservation(sourceId, targetWrongItemId).statusCode(422)
        // Locked target.
        transferReservation(sourceId, targetLockedId).statusCode(409)

        val (sourceAmount, sourceReserved) = stockOf(sourceId)
        assertThat(sourceAmount).`as`("source amount must be untouched by every refusal above").isEqualTo(100.0)
        assertThat(sourceReserved).`as`("source reservedAmount must be untouched by every refusal above").isEqualTo(30.0)

        val (validAmount, validReserved) = stockOf(targetValidId)
        assertThat(validAmount).isEqualTo(100.0)
        assertThat(validReserved).isEqualTo(0.0)

        val (smallAmount, smallReserved) = stockOf(targetSmallId)
        assertThat(smallAmount).isEqualTo(10.0)
        assertThat(smallReserved).isEqualTo(0.0)

        val (wrongAmount, wrongReserved) = stockOf(targetWrongItemId)
        assertThat(wrongAmount).isEqualTo(100.0)
        assertThat(wrongReserved).isEqualTo(0.0)

        val (lockedAmount, lockedReserved) = stockOf(targetLockedId)
        assertThat(lockedAmount).isEqualTo(100.0)
        assertThat(lockedReserved).isEqualTo(0.0)
    }
}
