package com.karyo.app

import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
import com.karyo.inventory.service.ReservationTransferService
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.service.OrderService
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private const val CLIENT_ID = 1L

/**
 * Reconciliation between the DeliveryOrder cancel path and the pick lifecycle (defect-burndown
 * task 6). Two filed rows, pinned with REAL beans end to end (no mocks):
 *
 *  1. **I1 cross-order double-release** — `cancel` used to release every recorded
 *     [com.karyo.orders.domain.model.OrderLineReservation] slice unconditionally. A slice whose
 *     pick already went terminal (PICKED consumed its stock-side reservation at confirm, CANCELED
 *     released it) is stale; re-releasing it would walk `StockUnit.reservedAmount` down past what
 *     this order holds and consume a THIRD order's live reservation on the same stock unit.
 *     `StockService.releaseReservation` no longer silently clamps an over-release to zero — it
 *     refuses one outright — but the netting below (`unhandledRemainders`) is still the primary
 *     guard: this test's amounts happen to coincide, so a regression here would still land
 *     exactly on zero rather than throw, which is why the assertion checks the value, not a
 *     caught exception.
 *  2. **Stranded pick work** — cancel is legal up to PENDING(550), which includes a STARTED(500)
 *     order already released to picking, yet it canceled no pick work: the PickOrder stayed
 *     RELEASED and claimable, and its picks stayed confirmable against a reservation the cancel
 *     had just given away.
 */
@QuarkusTest
class OrderCancelReconciliationTest {

    @Inject lateinit var orderService: OrderService
    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var reservationRepository: OrderLineReservationRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var entityManager: EntityManager
    @Inject lateinit var reservationTransferService: ReservationTransferService

    // ── REST seeding helpers (mirrors PickCancelServiceTest / OrderReservationFlowTest) ──

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

    private fun amountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("amount")

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

    /** A private SKU with its own single stock unit holding [stockAmount]. Returns (itemDataId, stockUnitId). */
    private fun seedProductWithStock(stockAmount: Double): Pair<Long, Long> {
        val s = System.nanoTime()
        val itemUnitId = createItemUnit("OU-${s.toString().takeLast(10)}")
        val number = "P-$s"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-$s")
        return productId to createStock(unitLoadId, productId, number, stockAmount)
    }

    /** Creates + releases an order over [lines] (itemDataId to amount). Short order number: PO-<n>-XXXXXX must fit VARCHAR(40). */
    private fun releaseOrderFor(lines: List<Pair<Long, Double>>): Long {
        val body = lines.joinToString(",") { (itemDataId, amount) -> """{"itemDataId":$itemDataId,"amount":$amount}""" }
        val orderNumber = "DO-${System.nanoTime().toString().takeLast(9)}"
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"orderNumber":"$orderNumber","customerName":"C","lines":[$body]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    private fun lineIdsOf(orderId: Long): List<Long> =
        orderService.findById(orderId, CLIENT_ID).lines.map { it.id }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel after confirmed picks does not release another order's reservation (I1)`() {
        seedPackStaging()
        // Two SKUs so O1 keeps ONE open pick after the confirm below — a single-line order would
        // complete the PickOrder, drive the DeliveryOrder to PICKED(600) and make cancel illegal.
        val (pidA, suA) = seedProductWithStock(stockAmount = 20.0)
        val (pidB, _) = seedProductWithStock(stockAmount = 20.0)
        val o1 = releaseOrderFor(listOf(pidA to 10.0, pidB to 10.0))
        tenantContext.clientId = CLIENT_ID

        val pickOrder = pickOrderService.releaseToPicking(o1).single()
        val pickA = pickRepository.findByPickOrderId(pickOrder.id!!).single { it.sourceStockUnitId == suA }
        // Confirm A in full: suA goes 20/reserved 10 -> amount 10, reserved 0 (the pick consumed it).
        pickOrderService.confirmPick(pickA.id!!, BigDecimal(10), targetUnitLoadId = null)
        entityManager.clear()
        assertThat(amountOf(suA)).isEqualTo(10.0)
        assertThat(reservedAmountOf(suA)).isEqualTo(0.0)

        // A THIRD party takes the freed stock: O2 reserves the remaining 10 on suA.
        val o2 = releaseOrderFor(listOf(pidA to 10.0))
        val o2LineIds = lineIdsOf(o2)
        assertThat(reservedAmountOf(suA)).isEqualTo(10.0)

        // O1 is STARTED(500) < PICKED(600), so cancel is legal.
        orderService.cancel(o1, CLIENT_ID)
        entityManager.clear()

        // O1's suA slice is STALE (its pick is terminal/PICKED — the reservation was consumed at
        // confirm). Releasing it again would walk reservedAmount 10 -> 0 and steal O2's hold.
        assertThat(reservedAmountOf(suA)).isEqualTo(10.0)
        assertThat(amountOf(suA)).isEqualTo(10.0)
        // O2's own bookkeeping is untouched.
        assertThat(reservationRepository.findByLineIds(o2LineIds)).isNotEmpty
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel force-finishes open pick work and releases each reservation exactly once`() {
        seedPackStaging()
        val (pid, su) = seedProductWithStock(stockAmount = 20.0)
        val o1 = releaseOrderFor(listOf(pid to 10.0))
        tenantContext.clientId = CLIENT_ID

        val pickOrder = pickOrderService.releaseToPicking(o1).single()
        assertThat(reservedAmountOf(su)).isEqualTo(10.0)

        // A THIRD party holds the OTHER half of the same stock unit. Without this witness the
        // "exactly once" claim below is vacuous: a cascade-slice DOUBLE release (netting removed,
        // terminalPlannedBySlice returning nothing, or the step-1 cascade not being flush-visible
        // to step-2's query) would release 10 again — and since O2's 10 is exactly what remains
        // reserved on this stock unit, `StockService.releaseReservation`'s refusal (which only
        // fires when the amount EXCEEDS what's reserved) would not catch it either: the release
        // would go through and STEAL O2's hold, which is exactly what the assertion below checks.
        val o2 = releaseOrderFor(listOf(pid to 10.0))
        val o2LineIds = lineIdsOf(o2)
        assertThat(reservedAmountOf(su)).isEqualTo(20.0)

        val response = orderService.cancel(o1, CLIENT_ID)
        entityManager.clear()

        assertThat(response.state).isEqualTo(OrderState.CANCELED.code)
        // No stranded live work: the PickOrder is force-finished, out of findClaimable's pool, and
        // its pick can no longer be confirmed against a reservation the order gave up.
        val po = pickOrderRepository.findByIdAndClient(pickOrder.id!!, CLIENT_ID)!!
        assertThat(po.state).isEqualTo(PickState.CANCELED.code)
        assertThat(pickRepository.findByPickOrderId(po.id!!).single().state).isEqualTo(PickState.CANCELED.code)
        // Released EXACTLY once — the pick machinery released O1's 10 during the cascade, and the
        // remainder math must NOT repeat it. O2's 10 survives untouched.
        assertThat(reservedAmountOf(su)).isEqualTo(10.0)
        assertThat(reservationRepository.findByLineIds(o2LineIds)).isNotEmpty
        // ...and nothing was invented: the goods never moved.
        assertThat(amountOf(su)).isEqualTo(20.0)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel after a split-transfer still nets to zero on the source (defect-burndown-5, row 1485)`() {
        // Witness (b): after ReservationTransferService.transferReservation splits a mixed
        // source (o1 terminal, o2 live), o1's terminal-consumed row stays keyed to the SOURCE
        // stock unit. Canceling o1 afterwards must be a clean no-op on that stock unit --
        // unhandledRemainders nets its slice to zero (terminalBySlice == reservedBySlice, both
        // keyed at (line1, source)), no over-release, no under-release, and the order stays
        // cancelable.
        seedPackStaging()
        // Two SKUs so o1 keeps ONE open pick after the confirm below -- a single-line order
        // would complete the PickOrder, drive o1 to PICKED(600) and make cancel illegal (same
        // trick as the I1 test above).
        val (pidA, sourceId) = seedProductWithStock(stockAmount = 100.0)
        val (pidB, _) = seedProductWithStock(stockAmount = 20.0)
        val o1 = releaseOrderFor(listOf(pidA to 30.0, pidB to 10.0))
        tenantContext.clientId = CLIENT_ID
        val pickOrder1 = pickOrderService.releaseToPicking(o1).single()
        val pickA = pickRepository.findByPickOrderId(pickOrder1.id!!).single { it.sourceStockUnitId == sourceId }
        pickOrderService.confirmPick(pickA.id!!, BigDecimal(30), targetUnitLoadId = null)
        entityManager.clear()
        assertThat(reservedAmountOf(sourceId)).isEqualTo(0.0)

        val o2 = releaseOrderFor(listOf(pidA to 20.0))
        val o2LineIds = lineIdsOf(o2)
        assertThat(reservedAmountOf(sourceId)).isEqualTo(20.0)

        val targetId = createStock(createUnitLoad("UL-CANCEL-SPLIT-TGT-${System.nanoTime()}"), pidA, "P-SPLIT-TGT", 100.0)
        reservationTransferService.transferReservation(sourceId, targetId, amount = null, tenant = tenantContext)
        entityManager.clear()
        assertThat(reservedAmountOf(sourceId)).isEqualTo(0.0)
        assertThat(reservedAmountOf(targetId)).isEqualTo(20.0)

        // o1 is STARTED(500) < PICKED(600) (pidB's pick is still open), so cancel is legal.
        val response = orderService.cancel(o1, CLIENT_ID)
        entityManager.clear()

        assertThat(response.state).isEqualTo(OrderState.CANCELED.code)
        // o1's terminal-consumed slice nets to zero on the SOURCE -- neither an over-release
        // (which would walk reservedAmount negative or steal o2's hold, now safely on the
        // target) nor an under-release (which would strand a phantom reservation on the source).
        assertThat(reservedAmountOf(sourceId)).isEqualTo(0.0)
        assertThat(amountOf(sourceId)).isEqualTo(70.0)
        // The target, which now holds o2's live reservation, is entirely unaffected by o1's cancel.
        assertThat(reservedAmountOf(targetId)).isEqualTo(20.0)
        assertThat(reservationRepository.findByLineIds(o2LineIds)).isNotEmpty
    }
}
