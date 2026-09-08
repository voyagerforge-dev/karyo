package com.karyo.fulfillment

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Row 16: `PickingOrderPrepareEvent` — a pure extension hook fired synchronously inside
 * `PickOrderService.releaseToPicking`, AFTER grouping, BEFORE any persistence. Proven here with
 * [TestPrepareEventObserver], the only observer in the whole test suite (v1.3 ships no built-in
 * one — see the event's KDoc). Every test MUST leave the observer disarmed afterward: it is an
 * `@ApplicationScoped` singleton shared with every other `@QuarkusTest` in this JVM, including the
 * regression-pinned `PickGenerationServiceTest` (which never touches it and must keep seeing "no
 * observer" behavior throughout).
 */
@QuarkusTest
class PickPrepareEventTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var observer: TestPrepareEventObserver

    @AfterEach
    fun disarmObserver() {
        observer.consumeIndex = null
        observer.consumeAll = false
    }

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
        val iu = createItemUnit("OU-$s"); val num = "P-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        val stockUnitId = createStock(ul, pid, num, stockAmount)
        return pid to stockUnitId
    }

    /**
     * Two lines, both fully reservable: line 0 = [pidA]/[suA], line 1 = [pidB]/[suB]. Relies on
     * `DeliveryOrder.lines`'s `@OrderBy("lineNumber ASC")` — line creation order == JSON body
     * order == `plannedPicks` index order (`flattenReservations` iterates `order.lines` in that
     * same order) — so `plannedPicks[0]` is always line A's slice.
     */
    private fun releaseTwoLineOrder(): Triple<Long, Long, Long> {
        val (pidA, suA) = seedLine(50.0)
        val (pidB, suB) = seedLine(50.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pidA,"amount":50.0},{"itemDataId":$pidB,"amount":50.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return Triple(orderId, suA, suB)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an observer that consumes index 0 excludes that pick from the generated order, keeping the rest`() {
        seedPackStaging()
        val (orderId, suA, suB) = releaseTwoLineOrder()
        tenantContext.clientId = 1L
        observer.consumeIndex = 0

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()

        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picks).hasSize(1)
        assertThat(picks.single().sourceStockUnitId).isEqualTo(suB)
        assertThat(picks.none { it.sourceStockUnitId == suA }).isTrue
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `with the observer disarmed, releaseToPicking still produces a pick for every planned slice`() {
        seedPackStaging()
        val (orderId, suA, suB) = releaseTwoLineOrder()
        tenantContext.clientId = 1L
        // Observer left disarmed (no consumeIndex, consumeAll=false) -- identical to "no observer".

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()

        val picks = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(picks).hasSize(2)
        assertThat(picks.map { it.sourceStockUnitId }).containsExactlyInAnyOrder(suA, suB)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an observer that consumes every planned pick is refused with AllPicksConsumedByExtension and creates no PickOrder`() {
        seedPackStaging()
        val (orderId, _, _) = releaseTwoLineOrder()
        tenantContext.clientId = 1L
        observer.consumeAll = true

        assertThatThrownBy { pickOrderService.releaseToPicking(orderId).single() }
            .isInstanceOf(FulfillmentException.AllPicksConsumedByExtension::class.java)

        // No PickOrder was created for this order, and the DeliveryOrder was never advanced past
        // its pre-release-to-picking state -- the throw happens BEFORE any persistence.
        assertThat(pickOrderRepository.findByDeliveryOrderId(orderId, 1L)).isNull()
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(300))
    }
}
