package com.karyo.fulfillment

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickLifecycleService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import jakarta.persistence.EntityManager
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.time.LocalDate

@QuarkusTest
class PickConfirmServiceTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var lifecycleService: PickLifecycleService
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var pickOrderRepository: PickOrderRepository
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
    private fun createStockWithLotAndBestBefore(
        ulId: Long, itemDataId: Long, number: String, amount: Double, lotNumber: String, bestBefore: LocalDate,
    ): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,
                    |"lotNumber":"$lotNumber","bestBefore":"$bestBefore","state":300}""".trimMargin(),
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
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
    private fun seedAndReleaseOrderFor60(): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "ORD-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":60.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `confirming all picks completes the PickOrder and advances the order to PICKED`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picks).isNotEmpty

        picks.forEach { p -> pickOrderService.confirmPick(p.id!!, p.plannedAmount, targetUnitLoadId = null) }

        // confirmPick committed in its own tx; clear the test's persistence context so the
        // assertion reads the freshly-committed rows rather than the L1-cached RELEASED picks.
        entityManager.clear()
        assertThat(pickRepository.findByPickOrderId(pickOrder.id!!).all { it.state == PickState.PICKED.code }).isTrue
        // D4: the fully-confirmed line reports its derived pickedAmount (= the confirmed 60), no substitutions.
        val order = given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .body("state", `is`(600)).extract().jsonPath()
        assertThat(order.getDouble("lines[0].pickedAmount")).isEqualTo(60.0)
        assertThat(order.getDouble("lines[0].substitutedAmount")).isEqualTo(0.0)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `confirm captures the source stock unit's picked lot and best-before as actuals`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "ORD-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        val lot = "LOT-$s"; val bb = LocalDate.of(2027, 3, 15)
        createStockWithLotAndBestBefore(ul, pid, num, 60.0, lot, bb)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":60.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        // Planned lotNumber (from the reservation) is already the SAME lot here, but pickedLotNumber/
        // pickedBestBefore are a SEPARATE pair of columns, unset until confirm.
        assertThat(pick.pickedLotNumber).isNull()
        assertThat(pick.pickedBestBefore).isNull()

        pickOrderService.confirmPick(pick.id!!, pick.plannedAmount, targetUnitLoadId = null)

        entityManager.clear()
        val confirmed = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        assertThat(confirmed.pickedLotNumber).isEqualTo(lot)
        assertThat(confirmed.pickedBestBefore).isEqualTo(bb)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `C1 -- confirming a per-line-canceled pick is refused, with no stock movement or reservation theft`() {
        // Regression pin for final-review finding C1: confirmPick's terminal guard only checked
        // PICKED(600), so a CANCELED(800) pick (per-line cancelPick or a force-finish cancelOrder)
        // sailed through, moving stock via pickStock with NO backing reservation.
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        val su = pick.sourceStockUnitId

        lifecycleService.cancelPick(pickOrder.id!!, pick.id!!, "ff", asManager = false)
        entityManager.clear()
        val reservedAfterCancel = given().`when`().get("/api/v1/stock-units/$su")
            .then().statusCode(200).extract().jsonPath().getDouble("reservedAmount")
        assertThat(reservedAfterCancel).isEqualTo(0.0)
        val orderStateBefore = given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200).extract().jsonPath().getInt("state")

        assertThatThrownBy { pickOrderService.confirmPick(pick.id!!, pick.plannedAmount, targetUnitLoadId = null) }
            .isInstanceOf(FulfillmentException.InvalidPickConfirmation::class.java)
        entityManager.clear()

        // No stock movement: the pick stays CANCELED with no target stock unit ever assigned.
        val afterAttempt = pickRepository.findByPickOrderId(pickOrder.id!!).single()
        assertThat(afterAttempt.state).isEqualTo(PickState.CANCELED.code)
        assertThat(afterAttempt.targetStockUnitId).isNull()
        // No reservation theft: still zero, never re-consumed from whatever reserved it next.
        val reservedAfter = given().`when`().get("/api/v1/stock-units/$su")
            .then().statusCode(200).extract().jsonPath().getDouble("reservedAmount")
        assertThat(reservedAfter).isEqualTo(0.0)
        // Delivery order state untouched by the refused confirm.
        val orderStateAfter = given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200).extract().jsonPath().getInt("state")
        assertThat(orderStateAfter).isEqualTo(orderStateBefore)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `C1 -- confirming a pick on an order-level-canceled PickOrder is refused, order stays CANCELED`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val pick = pickRepository.findByPickOrderId(pickOrder.id!!).single()

        lifecycleService.cancelOrder(pickOrder.id!!, "ff", asManager = false)
        entityManager.clear()

        assertThatThrownBy { pickOrderService.confirmPick(pick.id!!, pick.plannedAmount, targetUnitLoadId = null) }
            .isInstanceOf(FulfillmentException.InvalidPickConfirmation::class.java)
        entityManager.clear()

        // The order must NOT be resurrected CANCELED -> PICKED by the refused confirm.
        val orderAfter = pickOrderRepository.findByIdAndClient(pickOrder.id!!, 1L)!!
        assertThat(orderAfter.state).isEqualTo(PickState.CANCELED.code)
    }
}
