package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickLifecycleService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.PickTopUpService
import com.karyo.fulfillment.spi.PlannedPick
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.orders.domain.model.OrderLineReservation
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.security.TenantContext
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Row 15: `PickTopUpService.topUp` / `addPicksToOrder` — behavioral parity with myWMS's
 * add-picks-to-order behavior, plus
 * the "covered slice" derivation described in its KDoc. See task-2-report.md for the full
 * reasoning behind the CANCELED-coverage test below.
 */
@QuarkusTest
class PickTopUpServiceTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var lifecycleService: PickLifecycleService
    @Inject lateinit var topUpService: PickTopUpService
    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var deliveryOrderLookup: DeliveryOrderLookup
    @Inject lateinit var orderLineReservationRepository: OrderLineReservationRepository
    @Inject lateinit var stockReserver: StockReserver
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var entityManager: EntityManager

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun reservedAmountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("reservedAmount")
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

    /** One order line's product + a fully-available stock unit of [stockAmount]. */
    private fun seedLine(stockAmount: Double): Pair<Long, Long> {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-${s.toString().takeLast(10)}"); val num = "P-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        val stockUnitId = createStock(ul, pid, num, stockAmount)
        return pid to stockUnitId
    }

    private fun releaseOrderFor(lines: List<Pair<Long, Double>>): Long {
        val body = lines.joinToString(",") { (itemDataId, amount) -> """{"itemDataId":$itemDataId,"amount":$amount}""" }
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[$body]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /**
     * Persists a brand-new `OrderLineReservation` row directly, bypassing
     * `OrderService.retryReservation` (which has a pre-existing, unrelated landmine: calling it
     * AFTER an order has already advanced to STARTED via `releaseToPicking` makes
     * `promoteIfFullyReserved` attempt an illegal PROCESSABLE-from-STARTED transition and throw).
     * This directly simulates "a reservation exists for this line that the original release-to-
     * picking pass never captured as a Pick" -- exactly the situation `topUp` is meant to close,
     * without fighting an unrelated bug in a different module.
     */
    @Transactional
    fun seedReservation(lineId: Long, stockUnitId: Long, amount: BigDecimal) {
        orderLineReservationRepository.persist(
            OrderLineReservation().apply {
                this.lineId = lineId
                this.stockUnitId = stockUnitId
                this.amount = amount
            },
        )
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `topUp adds a pick for a line whose reservation only appeared after the original release, without double-reserving it`() {
        seedPackStaging()
        val (pidA, _) = seedLine(stockAmount = 100.0)
        val s = System.nanoTime()
        val iuB = createItemUnit("OU-B-${s.toString().takeLast(10)}")
        val numB = "P-B-$s"
        val pidB = createProduct(numB, iuB) // B has NO stock yet -- release reserves nothing for it.
        val orderId = releaseOrderFor(listOf(pidA to 60.0, pidB to 40.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picksBefore = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picksBefore).hasSize(1) // only A's slice existed at release time

        // B gets stock now, plus a directly-seeded reservation slice (see seedReservation's KDoc)
        // -- backed FOR REAL via reserveOnStockUnit, simulating what the normal reservation
        // pipeline (OrderService.reserveLine/retryReservation) would already have done. This key
        // never had ANY pick (canceled or otherwise), so it is "genuinely new" -- topUp must NOT
        // reserve it again.
        val ulB = createUnitLoad("UL-B-$s")
        val suB = createStock(ulB, pidB, numB, 50.0)
        val lineBId = deliveryOrderLookup.findForPicking(orderId)!!.lines.single { it.itemDataId == pidB }.lineId
        seedReservation(lineBId, suB, BigDecimal(40))
        assertThat(stockReserver.reserveOnStockUnit(suB, BigDecimal(40), "test-setup")).isTrue
        entityManager.clear()
        assertThat(reservedAmountOf(suB)).isEqualTo(40.0)

        val updated = topUpService.topUp(pickOrder.id!!)
        entityManager.clear()

        // Order state NOT recalculated by the add (myWMS-faithful, KDoc'd on addPicksToOrder).
        assertThat(updated.state).isEqualTo(PickState.RELEASED.code)
        val picksAfter = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picksAfter).hasSize(2)
        val newPick = picksAfter.single { it.sourceStockUnitId == suB }
        assertThat(newPick.state).isEqualTo(PickState.RELEASED.code)
        assertThat(newPick.plannedAmount).isEqualByComparingTo(BigDecimal(40))
        assertThat(newPick.deliveryOrderLineId).isEqualTo(lineBId)
        // NOT double-reserved: still exactly 40, never bumped to 80.
        assertThat(reservedAmountOf(suB)).isEqualTo(40.0)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `addPicksToOrder resolves source amounts by the order's own clientId, not an unprimed ambient TenantContext`() {
        seedPackStaging()
        val (pidA, _) = seedLine(stockAmount = 100.0)
        val orderId = releaseOrderFor(listOf(pidA to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val order = pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!
        val lineId = deliveryOrderLookup.findForPicking(orderId)!!.lines.single { it.itemDataId == pidA }.lineId

        // A brand-new, fully-available stock unit for the SAME line, backed by a real reservation
        // -- mirrors the "topUp adds a pick..." test's product-B setup above, but reused here as a
        // planned pick fed straight into addPicksToOrder (the ExtinguishService entry, L155) rather
        // than through topUp()'s own re-derivation.
        val s = System.nanoTime()
        val ul = createUnitLoad("UL-C-$s")
        val su = createStock(ul, pidA, "P-$s", 30.0)
        assertThat(stockReserver.reserveOnStockUnit(su, BigDecimal(30), "test-setup")).isTrue
        entityManager.clear()

        val planned = PlannedPick(
            deliveryOrderLineId = lineId,
            itemDataId = pidA,
            itemDataNumber = "P-$s",
            lotNumber = null,
            sourceStockUnitId = su,
            amount = BigDecimal(30),
        )

        // Simulates a caller whose thread never primed TenantContext (same doctrine as
        // WaveScheduler's unprimed-thread bug on PickOrderService.sourceAmountsFor, see
        // WaveSchedulerIT) -- @OidcSecurity only primes TenantContext for REST calls, not this
        // direct service call, so tenantContext.clientId is left at its unset default (0) unless a
        // test sets it. Deliberately mismatched against the order's real clientId (1).
        tenantContext.clientId = 0L

        val rejected = topUpService.addPicksToOrder(order, listOf(planned))
        entityManager.clear()

        assertThat(rejected).isEmpty()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        val newPick = picks.single { it.sourceStockUnitId == su }
        // Before the fix: sourceAmountsFor's ambient findByItemDataId(itemDataId) saw client 0's
        // (empty) stock, so `full` was always false and this fully-covering slice was misclassified
        // PICK instead of COMPLETE, regardless of it exactly matching the stock unit's whole amount.
        assertThat(newPick.pickingType).isEqualTo(PickingType.COMPLETE.name)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `topUp with nothing new to cover is refused with ValidationFailed`() {
        seedPackStaging()
        val (pid, _) = seedLine(stockAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L
        val pickOrder = pickOrderService.releaseToPicking(orderId).single()

        assertThatThrownBy { topUpService.topUp(pickOrder.id!!) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `topUp is refused once the pick order has reached PICKED, even with an uncovered stale slice from a per-line cancel`() {
        seedPackStaging()
        val (pidA, suA) = seedLine(stockAmount = 50.0)
        val (pidB, suB) = seedLine(stockAmount = 50.0)
        val orderId = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        val pickA = picks.single { it.sourceStockUnitId == suA }
        val pickB = picks.single { it.sourceStockUnitId == suB }

        // B's per-line cancel leaves its OrderLineReservation slice on the books but excluded from
        // "have" -- an uncovered slice -- while A's confirm still drives the ORDER to PICKED (the
        // completion predicate treats CANCELED as terminal, see Task 1).
        lifecycleService.cancelPick(pickOrder.id!!, pickB.id!!, "ff", asManager = false)
        pickOrderService.confirmPick(pickA.id!!, BigDecimal(50), targetUnitLoadId = null)
        entityManager.clear()

        val orderAfter = pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!
        assertThat(orderAfter.state).isEqualTo(PickState.PICKED.code)

        assertThatThrownBy { topUpService.topUp(pickOrder.id!!) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling a pick per-line then topping up re-adds its slice, and the recovered pick can still be confirmed`() {
        seedPackStaging()
        val (pidA, _) = seedLine(stockAmount = 50.0)
        val (pidB, suB) = seedLine(stockAmount = 50.0)
        val orderId = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pickB = pickRepository.findByPickOrderId(pickOrder.id!!).single { it.sourceStockUnitId == suB }

        lifecycleService.cancelPick(pickOrder.id!!, pickB.id!!, "ff", asManager = false)
        entityManager.clear()
        // The cancel released the STOCK-side reservation -- but NOT the order-side
        // OrderLineReservation row (only OrderService.cancel touches that). See topUp's KDoc.
        assertThat(reservedAmountOf(suB)).isEqualTo(0.0)

        val updated = topUpService.topUp(pickOrder.id!!)
        entityManager.clear()
        assertThat(updated.state).isEqualTo(PickState.RELEASED.code)

        // The fix round's central assertion: the recovered pick is reservation-backed AT ADD-PICKS
        // TIME, before any confirm call -- topUp itself re-reserved suB (closing the gap where an
        // unbacked pick could silently consume a different order's live reservation at confirm).
        assertThat(reservedAmountOf(suB)).isEqualTo(50.0)

        val afterTopUp = pickRepository.findByPickOrderId(pickOrder.id!!)
        val recovered = afterTopUp.filter { it.sourceStockUnitId == suB && it.state != PickState.CANCELED.code }
        assertThat(recovered).hasSize(1)
        assertThat(recovered.single().plannedAmount).isEqualByComparingTo(BigDecimal(50))

        // Recovery still completes normally: pickStock releases the (now genuinely live) reservation
        // it just re-established at top-up time, then transfers the stock.
        val confirmed = pickOrderService.confirmPick(recovered.single().id!!, BigDecimal(50), targetUnitLoadId = null)
        assertThat(confirmed.state).isEqualTo(PickState.PICKED.code)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a recovered slice whose stock a different order reserved meanwhile is skipped, never consuming that order's reservation`() {
        seedPackStaging()
        val (pidA, suA) = seedLine(stockAmount = 50.0)
        val (pidB, suB) = seedLine(stockAmount = 50.0)
        val orderIdA = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderIdA).single()
        val pickB = pickRepository.findByPickOrderId(pickOrder.id!!).single { it.sourceStockUnitId == suB }
        lifecycleService.cancelPick(pickOrder.id!!, pickB.id!!, "ff", asManager = false)
        entityManager.clear()
        assertThat(reservedAmountOf(suB)).isEqualTo(0.0) // freed by the cancel, exactly like the recovery test

        // Order B (unrelated) legitimately reserves 30 of the now-free 50 on the SAME stock unit --
        // completely ordinary system behavior, no bug on B's side.
        releaseOrderFor(listOf(pidB to 30.0))
        entityManager.clear()
        assertThat(reservedAmountOf(suB)).isEqualTo(30.0)

        // Order A's top-up wants its stale 50-unit slice on suB back, but only 20 is available
        // (50 - 30) -- reserveOnStockUnit must fail, the slice must be SKIPPED (not partially
        // added), and since it was the only uncovered slice, topUp refuses with 409 instead of
        // silently creating an unbacked pick that would later consume B's reservation at confirm.
        assertThatThrownBy { topUpService.topUp(pickOrder.id!!) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)

        // The critical assertion: B's reservation is untouched, and no third pick was ever created.
        assertThat(reservedAmountOf(suB)).isEqualTo(30.0)
        assertThat(pickRepository.findByPickOrderId(pickOrder.id!!)).hasSize(2) // pickA + canceled pickB only
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `addPicksToOrder rejects a planned pick whose line does not belong to this order's delivery order`() {
        seedPackStaging()
        val (pid, su) = seedLine(stockAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L
        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val order = pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!

        val bogus = PlannedPick(
            deliveryOrderLineId = 999_999_999L,
            itemDataId = pid,
            itemDataNumber = "X",
            lotNumber = null,
            sourceStockUnitId = su,
            amount = BigDecimal.TEN,
        )

        val rejected = topUpService.addPicksToOrder(order, listOf(bogus))

        assertThat(rejected).containsExactly(bogus)
        assertThat(pickRepository.findByPickOrderId(pickOrder.id!!)).hasSize(1) // nothing added
    }

    /**
     * Row 20 (V605) review pin: `topUp` re-reads a live DeliveryOrder via `findForPicking`, which
     * an EXTINGUISH order (`deliveryOrderId == null`) has none of -- `loadOrderAndDeliveryOrder`'s
     * `requireOrderBound` guard must refuse with a clean 409 rather than NPE-ing on
     * `findForPicking(null)`. Seeds the EXT order directly (mirrors PickRollupLookupTest/
     * PickWorkProviderTest's persist-direct idiom) since ExtinguishService itself is covered
     * end-to-end in ExtinguishServiceTest.
     */
    @Transactional
    fun persistExtOrder(clientId: Long): Long {
        val order = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "EXT-TOPUP-${System.nanoTime()}"
            deliveryOrderId = null
            deliveryOrderNumber = null
            state = PickState.RELEASED.code
        }
        pickOrderRepository.persist(order)
        return order.id!!
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `topUp on an EXTINGUISH order is refused with ValidationFailed, not an NPE`() {
        tenantContext.clientId = 1L
        val extOrderId = persistExtOrder(1L)

        assertThatThrownBy { topUpService.topUp(extOrderId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }
}
