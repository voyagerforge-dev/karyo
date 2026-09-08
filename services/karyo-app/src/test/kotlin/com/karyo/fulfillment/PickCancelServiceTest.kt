package com.karyo.fulfillment

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.DefaultPickCancelPort
import com.karyo.fulfillment.service.PickLifecycleService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class PickCancelServiceTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var lifecycleService: PickLifecycleService
    @Inject lateinit var pickCancelPort: DefaultPickCancelPort
    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository
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

    /** One order line, its own product + stock unit, released for [orderAmount] of [stockAmount] available. */
    private fun seedLine(stockAmount: Double, orderAmount: Double): Pair<Long, Long> {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "P-$s"
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

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling an order before anything is picked cancels every pick and releases every reservation`() {
        seedPackStaging()
        val (pid, su) = seedLine(stockAmount = 100.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        assertThat(reservedAmountOf(su)).isEqualTo(60.0)

        val canceled = lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false)
        entityManager.clear()

        assertThat(canceled.state).isEqualTo(PickState.CANCELED.code)
        assertThat(canceled.operatorId).isNull()
        assertThat(canceled.finished).isNotNull
        assertThat(pickRepository.findByPickOrderId(pickOrder.id!!).all { it.state == PickState.CANCELED.code }).isTrue
        assertThat(reservedAmountOf(su)).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling an order with one pick already PICKED lands the order on PICKED and leaves that pick untouched`() {
        seedPackStaging()
        val (pidA, suA) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val (pidB, suB) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val orderId = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picks).hasSize(2)
        val pickA = picks.single { it.sourceStockUnitId == suA }
        val pickB = picks.single { it.sourceStockUnitId == suB }

        // Confirm A in full; B is left open.
        pickOrderService.confirmPick(pickA.id!!, BigDecimal(50), targetUnitLoadId = null)
        entityManager.clear()
        assertThat(reservedAmountOf(suB)).isEqualTo(50.0)

        val result = lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false)
        entityManager.clear()

        // Force-finish "some picks kept" branch: Karyo has no separate FINISHED code, so the order
        // lands on the same PICKED(600) terminal code full completion uses (see class KDoc).
        assertThat(result.state).isEqualTo(PickState.PICKED.code)
        val afterCancel = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(afterCancel.single { it.id == pickA.id }.state).isEqualTo(PickState.PICKED.code)
        assertThat(afterCancel.single { it.id == pickA.id }.pickedAmount).isEqualByComparingTo(BigDecimal(50))
        assertThat(afterCancel.single { it.id == pickB.id }.state).isEqualTo(PickState.CANCELED.code)
        // Only B's (still-open) reservation is released; A already consumed its own via pickStock.
        assertThat(reservedAmountOf(suB)).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling an order already at or past PICKED is refused with ValidationFailed`() {
        seedPackStaging()
        val (pid, _) = seedLine(stockAmount = 60.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        pickOrderService.confirmPick(pick.id!!, BigDecimal(60), targetUnitLoadId = null)
        entityManager.clear()

        assertThatThrownBy { lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling an order claimed by a different operator is refused unless asManager`() {
        seedPackStaging()
        val (pid, _) = seedLine(stockAmount = 60.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        pickOrderService.claim(pickOrder.id!!, "bob")
        entityManager.clear()

        assertThatThrownBy { lifecycleService.cancelOrder(pickOrder.id!!, "alice", asManager = false) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)

        val canceled = lifecycleService.cancelOrder(pickOrder.id!!, "alice", asManager = true)
        assertThat(canceled.state).isEqualTo(PickState.CANCELED.code)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `per-line cancel on a PICKED pick is a 409 no-op`() {
        seedPackStaging()
        val (pid, _) = seedLine(stockAmount = 60.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        pickOrderService.confirmPick(pick.id!!, BigDecimal(60), targetUnitLoadId = null)
        entityManager.clear()

        assertThatThrownBy { lifecycleService.cancelPick(pickOrder.id!!, pick.id!!, "ff", asManager = false) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `per-line cancel releases the reservation, marks the pick CANCELED, and leaves the order untouched`() {
        seedPackStaging()
        val (pidA, suA) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val (pidB, _) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val orderId = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        val pickA = picks.single { it.sourceStockUnitId == suA }
        assertThat(reservedAmountOf(suA)).isEqualTo(50.0)

        val canceled = lifecycleService.cancelPick(pickOrder.id!!, pickA.id!!, "ff", asManager = false)
        entityManager.clear()

        assertThat(canceled.state).isEqualTo(PickState.CANCELED.code)
        assertThat(reservedAmountOf(suA)).isEqualTo(0.0)
        // Order state untouched by a per-line cancel (myWMS-faithful, KDoc'd on cancelPick).
        val orderAfter = pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!
        assertThat(orderAfter.state).isEqualTo(PickState.RELEASED.code)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling one line then confirming the remaining line still completes the order`() {
        seedPackStaging()
        val (pidA, suA) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val (pidB, suB) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val orderId = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        val pickA = picks.single { it.sourceStockUnitId == suA }
        val pickB = picks.single { it.sourceStockUnitId == suB }

        lifecycleService.cancelPick(pickOrder.id!!, pickA.id!!, "ff", asManager = false)
        entityManager.clear()

        // Confirming the sole remaining OPEN pick must still drive the order to PICKED — the
        // completion predicate treats the canceled sibling as terminal (PickOrderService.confirmPick).
        pickOrderService.confirmPick(pickB.id!!, BigDecimal(50), targetUnitLoadId = null)
        entityManager.clear()

        val orderAfter = pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!
        assertThat(orderAfter.state).isEqualTo(PickState.PICKED.code)
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling every line per-line then canceling the order lands it on CANCELED, not PICKED`() {
        // Regression pin for task-1-review finding #1: keptPickedCount must count picks GENUINELY
        // PICKED, not "not currently open" (which wrongly included already-CANCELED picks). Nothing
        // here is ever genuinely picked, so the order must land on CANCELED(800), never PICKED(600).
        seedPackStaging()
        val (pidA, suA) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val (pidB, suB) = seedLine(stockAmount = 50.0, orderAmount = 50.0)
        val orderId = releaseOrderFor(listOf(pidA to 50.0, pidB to 50.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        picks.forEach { lifecycleService.cancelPick(pickOrder.id!!, it.id!!, "ff", asManager = false) }
        entityManager.clear()

        val result = lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false)
        entityManager.clear()

        assertThat(result.state).isEqualTo(PickState.CANCELED.code)
        assertThat(reservedAmountOf(suA)).isEqualTo(0.0)
        assertThat(reservedAmountOf(suB)).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(user = "carol", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `M6 -- a per-line cancel on an order claimed by a different operator is refused unless asManager`() {
        // Regression pin for final-review finding M6: cancelPick had NO ownership check at all,
        // unlike cancelOrder -- any fulfillment-write principal could per-line-cancel a pick of an
        // order claimed by someone else. Authorization must mirror cancelOrder's rule exactly.
        seedPackStaging()
        val (pid, _) = seedLine(stockAmount = 60.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        pickOrderService.claim(pickOrder.id!!, "bob") // claimed by "bob"; the request runs as "carol"
        entityManager.clear()

        // "carol" (no MANAGER) is a different operator than the claimer "bob" -> 409, matching
        // cancelOrder's own ownership-mismatch behavior.
        assertThatThrownBy { lifecycleService.cancelPick(pickOrder.id!!, pick.id!!, "carol", asManager = false) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)

        // Reservation and pick state are untouched by the refused attempt.
        assertThat(reservedAmountOf(pick.sourceStockUnitId)).isEqualTo(60.0)
        val stillOpen = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        assertThat(stillOpen.state).isNotEqualTo(PickState.CANCELED.code)

        // A MANAGER can cancel the same pick, same as cancelOrder's asManager override.
        val canceled = lifecycleService.cancelPick(pickOrder.id!!, pick.id!!, "carol", asManager = true)
        assertThat(canceled.state).isEqualTo(PickState.CANCELED.code)
        assertThat(reservedAmountOf(pick.sourceStockUnitId)).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `M6 -- a per-line cancel on an unclaimed order is allowed for any fulfillment-write operator`() {
        seedPackStaging()
        val (pid, su) = seedLine(stockAmount = 60.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        // Order is unclaimed (operatorId null) -- any operator, without asManager, may cancel it.
        val canceled = lifecycleService.cancelPick(pickOrder.id!!, pick.id!!, "ff", asManager = false)

        assertThat(canceled.state).isEqualTo(PickState.CANCELED.code)
        assertThat(reservedAmountOf(su)).isEqualTo(0.0)
    }

    /**
     * Rows :2030/:2061 (defect-burndown-6): the NON-REST cancel entry -- [DefaultPickCancelPort],
     * which is also what `WavePickService.cancelOpenForWave` reaches -- must release the
     * reservation under the WORK's own `clientId`, not the ambient [TenantContext] one. Both
     * callers arrive with an active but UNPRIMED request scope (ambient `clientId` defaults to
     * 0), so an ambient-scoped `StockPicker.releaseUnpickedReservation` resolves against client 0,
     * finds nothing, and strands the reservation on the real owner's stock forever.
     *
     * `forceFinish` itself is `internal` to fulfillment-core and so unreachable from this test
     * module; [DefaultPickCancelPort] is the thinnest public wrapper around exactly that body.
     */
    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the non-REST cancel cascade releases the reservation under the work's own clientId`() {
        seedPackStaging()
        val (pid, su) = seedLine(stockAmount = 100.0, orderAmount = 60.0)
        val orderId = releaseOrderFor(listOf(pid to 60.0))
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        assertThat(reservedAmountOf(su)).isEqualTo(60.0)

        // The cascade's real arrival condition: an unprimed ambient tenant (a non-owner), with the
        // owning clientId carried explicitly as an argument.
        tenantContext.clientId = 0L
        val canceled = pickCancelPort.cancelOpenWorkForDeliveryOrder(orderId, clientId = 1L)
        entityManager.clear()

        assertThat(canceled).isEqualTo(1)
        assertThat(pickRepository.findByPickOrderId(pickOrder.id!!).all { it.state == PickState.CANCELED.code }).isTrue
        assertThat(reservedAmountOf(su)).isEqualTo(0.0)
    }
}
