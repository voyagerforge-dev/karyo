package com.karyo.fulfillment

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.ExtinguishService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.ws.rs.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Row 20 (V605): [ExtinguishService] — stock-clearance picks with NO backing DeliveryOrder.
 * Real beans/DB (no mocks), same REST-seeding idiom as [PickTopUpServiceTest]/[PickCancelServiceTest].
 *
 * [TestMethodOrder] is only load-bearing for the cross-tenant pair at the end (mirrors
 * [PickOrderExportTest]'s "seed under client 1, verify absence under client 2" idiom — a genuine
 * two-tenant proof needs two methods sharing the same DB, since `@TestSecurity`/`@OidcSecurity`
 * fix one identity per method). Every method carries an explicit [Order] so the whole class stays
 * deterministic.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExtinguishServiceTest {

    @Inject lateinit var extinguishService: ExtinguishService
    @Inject lateinit var pickOrderService: PickOrderService
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
    private fun createStockWithLotAndBestBefore(
        ulId: Long, itemDataId: Long, number: String, amount: Double, lotNumber: String, bestBefore: java.time.LocalDate,
    ): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,
                    |"lotNumber":"$lotNumber","bestBefore":"$bestBefore","state":300}""".trimMargin(),
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun reservedAmountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("reservedAmount")
    private fun availableAmountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("availableAmount")
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

    /** One product + one fully-available stock unit of [amount], on its own unit load. */
    private fun seedStockUnit(amount: Double): Pair<Long, Long> {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "P-EXT-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-EXT-$s")
        val suId = createStock(ul, pid, num, amount)
        return pid to suId
    }

    // ── stockUnitIds variant ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87101"), Claim(key = "tenant_code", value = "ACME")])
    fun `extinguish by stockUnitIds creates one pick per unit for the FULL available amount, reservation-backed`() {
        seedPackStaging()
        val (_, suA) = seedStockUnit(40.0)
        val (_, suB) = seedStockUnit(25.0)
        tenantContext.clientId = 87101L

        val order = extinguishService.extinguish(stockUnitIds = listOf(suA, suB), unitLoadId = null)
        entityManager.clear()

        assertThat(order.pickOrderNumber).startsWith("EXT-")
        assertThat(order.deliveryOrderId).isNull()
        assertThat(order.deliveryOrderNumber).isNull()
        assertThat(order.state).isEqualTo(PickState.RELEASED.code)

        val picks = pickRepository.findByPickOrderId(order.id!!)
        assertThat(picks).hasSize(2)
        picks.forEach {
            assertThat(it.deliveryOrderLineId).isNull()
            assertThat(it.pickingType).isEqualTo(PickingType.EXTINGUISH.name)
            assertThat(it.state).isEqualTo(PickState.RELEASED.code)
        }
        assertThat(picks.single { it.sourceStockUnitId == suA }.plannedAmount).isEqualByComparingTo(BigDecimal("40.0000"))
        assertThat(picks.single { it.sourceStockUnitId == suB }.plannedAmount).isEqualByComparingTo(BigDecimal("25.0000"))

        // Reservation-backed: the FULL available amount was reserved, not left free for selection.
        assertThat(reservedAmountOf(suA)).isEqualTo(40.0)
        assertThat(reservedAmountOf(suB)).isEqualTo(25.0)
        assertThat(availableAmountOf(suA)).isEqualTo(0.0)
        assertThat(availableAmountOf(suB)).isEqualTo(0.0)
    }

    // ── unitLoadId variant ────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87102"), Claim(key = "tenant_code", value = "ACME")])
    fun `extinguish by unitLoadId picks every stock unit on that unit load`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s")
        val ul = createUnitLoad("UL-EXT-UL-$s")
        val pidA = createProduct("P-EXT-UL-A-$s", iu)
        val pidB = createProduct("P-EXT-UL-B-$s", iu)
        val suA = createStock(ul, pidA, "P-EXT-UL-A-$s", 10.0)
        val suB = createStock(ul, pidB, "P-EXT-UL-B-$s", 15.0)
        tenantContext.clientId = 87102L

        val order = extinguishService.extinguish(stockUnitIds = null, unitLoadId = ul)
        entityManager.clear()

        val picks = pickRepository.findByPickOrderId(order.id!!)
        assertThat(picks.map { it.sourceStockUnitId }).containsExactlyInAnyOrder(suA, suB)
        assertThat(reservedAmountOf(suA)).isEqualTo(10.0)
        assertThat(reservedAmountOf(suB)).isEqualTo(15.0)
    }

    // ── merge into an existing open EXT order ────────────────────────────────────────────

    @Test
    @Order(3)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87103"), Claim(key = "tenant_code", value = "ACME")])
    fun `a second extinguish call merges into the still-open EXT order instead of minting a new one`() {
        seedPackStaging()
        val (_, suA) = seedStockUnit(20.0)
        val (_, suB) = seedStockUnit(30.0)
        tenantContext.clientId = 87103L

        val first = extinguishService.extinguish(stockUnitIds = listOf(suA), unitLoadId = null)
        val second = extinguishService.extinguish(stockUnitIds = listOf(suB), unitLoadId = null)
        entityManager.clear()

        assertThat(second.id).isEqualTo(first.id)
        assertThat(pickOrderRepository.findByClient(87103L).count { it.pickOrderNumber.startsWith("EXT-") }).isEqualTo(1)
        val picks = pickRepository.findByPickOrderId(first.id!!)
        assertThat(picks.map { it.sourceStockUnitId }).containsExactlyInAnyOrder(suA, suB)
    }

    // ── confirm end-to-end, no delivery-order touched ────────────────────────────────────

    @Test
    @Order(4)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87104"), Claim(key = "tenant_code", value = "ACME")])
    fun `confirming an EXT pick end-to-end completes the order and never touches an unrelated delivery order`() {
        seedPackStaging()
        // An unrelated, ordinary delivery order + pick order in the SAME tenant, to prove
        // extinguish confirm never reaches its state.
        val (unrelatedPid, _) = seedStockUnit(60.0)
        val unrelatedOrderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$unrelatedPid,"amount":60.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$unrelatedOrderId/release").then().statusCode(200)
        val unrelatedStateBefore = given().`when`().get("/api/v1/delivery-orders/$unrelatedOrderId")
            .then().statusCode(200).extract().jsonPath().getInt("state")

        val s = System.nanoTime()
        val iuExt = createItemUnit("OU-$s"); val numExt = "P-EXT-CONF-$s"
        val pidExt = createProduct(numExt, iuExt); val ulExt = createUnitLoad("UL-EXT-CONF-$s")
        val lot = "LOT-EXT-$s"; val bb = java.time.LocalDate.of(2027, 6, 1)
        val suExt = createStockWithLotAndBestBefore(ulExt, pidExt, numExt, 15.0, lot, bb)
        tenantContext.clientId = 87104L

        val order = extinguishService.extinguish(stockUnitIds = listOf(suExt), unitLoadId = null)
        val pick = pickRepository.findByPickOrderId(order.id!!).single()

        val confirmed = pickOrderService.confirmPick(pick.id!!, pick.plannedAmount, targetUnitLoadId = null)
        entityManager.clear()

        assertThat(confirmed.state).isEqualTo(PickState.PICKED.code)
        // Row 18 actuals capture applies identically to an EXTINGUISH pick — no delivery-order
        // dependency in captureActuals, only the source stock unit.
        assertThat(confirmed.pickedLotNumber).isEqualTo(lot)
        assertThat(confirmed.pickedBestBefore).isEqualTo(bb)
        val orderAfter = pickOrderRepository.findByIdAndClient(order.id!!, 87104L)!!
        assertThat(orderAfter.state).isEqualTo(PickState.PICKED.code)
        assertThat(orderAfter.deliveryOrderId).isNull()

        // The unrelated delivery order is completely unaffected by the extinguish confirm.
        given().`when`().get("/api/v1/delivery-orders/$unrelatedOrderId").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(unrelatedStateBefore))
    }

    // ── zero-available skip / all-skipped 409 ────────────────────────────────────────────

    @Test
    @Order(5)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87105"), Claim(key = "tenant_code", value = "ACME")])
    fun `a zero-available stock unit is skipped, the rest still extinguish`() {
        seedPackStaging()
        val (_, suZero) = seedStockUnit(0.0)
        val (_, suOk) = seedStockUnit(10.0)
        tenantContext.clientId = 87105L

        val order = extinguishService.extinguish(stockUnitIds = listOf(suZero, suOk), unitLoadId = null)
        entityManager.clear()

        val picks = pickRepository.findByPickOrderId(order.id!!)
        assertThat(picks).hasSize(1)
        assertThat(picks.single().sourceStockUnitId).isEqualTo(suOk)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87106"), Claim(key = "tenant_code", value = "ACME")])
    fun `every candidate having zero available amount is refused with ValidationFailed`() {
        seedPackStaging()
        val (_, suZero) = seedStockUnit(0.0)
        tenantContext.clientId = 87106L

        assertThatThrownBy { extinguishService.extinguish(stockUnitIds = listOf(suZero), unitLoadId = null) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    // ── both-params / neither → 400 ───────────────────────────────────────────────────────

    @Test
    @Order(7)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87107"), Claim(key = "tenant_code", value = "ACME")])
    fun `neither selector supplied is a 400`() {
        tenantContext.clientId = 87107L
        assertThatThrownBy { extinguishService.extinguish(stockUnitIds = null, unitLoadId = null) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87108"), Claim(key = "tenant_code", value = "ACME")])
    fun `both selectors supplied is a 400`() {
        seedPackStaging()
        val (_, su) = seedStockUnit(10.0)
        tenantContext.clientId = 87108L
        assertThatThrownBy { extinguishService.extinguish(stockUnitIds = listOf(su), unitLoadId = 999L) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87109"), Claim(key = "tenant_code", value = "ACME")])
    fun `an empty stockUnitIds list is treated as no selector and is a 400`() {
        tenantContext.clientId = 87109L
        assertThatThrownBy { extinguishService.extinguish(stockUnitIds = emptyList(), unitLoadId = null) }
            .isInstanceOf(BadRequestException::class.java)
    }

    // ── cross-tenant refusal (genuine two-tenant proof — seed under client 1, verify under client 2) ──

    @Test
    @Order(10)
    @TestSecurity(user = "ff1", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87110"), Claim(key = "tenant_code", value = "ACME")])
    fun `seed a client-1-only stock unit for the cross-tenant leak check`() {
        val (_, su) = seedStockUnit(10.0)
        crossTenantStockUnitId = su
    }

    @Test
    @Order(11)
    @TestSecurity(user = "ff2", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87111"), Claim(key = "tenant_code", value = "GLOBEX")])
    fun `client 1's stock unit is invisible to client 2 -- extinguish refuses with NotFound, never leaked`() {
        tenantContext.clientId = 87111L
        assertThatThrownBy {
            extinguishService.extinguish(stockUnitIds = listOf(crossTenantStockUnitId), unitLoadId = null)
        }.isInstanceOf(FulfillmentException.NotFound::class.java)
    }

    // ── M8: EXT order attribution follows the STOCK's owner, not the caller ──────────────────

    @Test
    @Order(12)
    @TestSecurity(user = "ops1", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87201"), Claim(key = "tenant_code", value = "ACME")])
    fun `M8 -- an OPS principal extinguishing another owner's stock attributes the EXT order to the STOCK's owner, not the caller`() {
        // Regression pin for final-review finding M8: ExtinguishService previously stamped the EXT
        // order/outbox with tenantContext.clientId (the CALLER) -- a wide-readScope (OPS) principal
        // extinguishing another owner's stock would mis-attribute the order to itself.
        seedPackStaging()
        val (_, su) = seedStockUnit(20.0) // seeded under client 87201 (this method's JWT identity)

        // Simulate an OPS principal whose OWN clientId differs from the stock's owner (87201) --
        // a wide readScope lets it see/extinguish another owner's stock.
        tenantContext.clientId = 999999L
        tenantContext.principalKind = PrincipalKind.OPS

        val order = extinguishService.extinguish(stockUnitIds = listOf(su), unitLoadId = null)
        entityManager.clear()

        // Attributed to the STOCK's owner (87201), never the caller's own clientId (999999).
        assertThat(order.clientId).isEqualTo(87201L)
        assertThat(pickOrderRepository.findByIdAndClient(order.id!!, 87201L)).isNotNull
        assertThat(pickOrderRepository.findByIdAndClient(order.id!!, 999999L)).isNull()
        val pick = pickRepository.findByPickOrderId(order.id!!).single()
        assertThat(pick.clientId).isEqualTo(87201L)
    }

    @Test
    @Order(13)
    @TestSecurity(user = "owner-a", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87202"), Claim(key = "tenant_code", value = "ACME")])
    fun `M8 -- seed an owner-A stock unit for the mixed-owner refusal check`() {
        // PACK_STAGING is seeded here too (even though the expected refusal happens before any
        // staging lookup) so the pin can only pass via the owner-mismatch check itself, never by
        // accidentally tripping over a missing-staging 409 for whichever owner a broken
        // implementation happened to pick.
        seedPackStaging()
        val (_, su) = seedStockUnit(10.0)
        mixedOwnerStockA = su
    }

    @Test
    @Order(14)
    @TestSecurity(user = "owner-b", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87203"), Claim(key = "tenant_code", value = "GLOBEX")])
    fun `M8 -- seed an owner-B stock unit for the mixed-owner refusal check`() {
        seedPackStaging()
        val (_, su) = seedStockUnit(10.0)
        mixedOwnerStockB = su
    }

    @Test
    @Order(15)
    @TestSecurity(user = "ops2", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87204"), Claim(key = "tenant_code", value = "ACME")])
    fun `M8 -- an extinguish call spanning two different owners' stock units is refused, never picks a winner`() {
        // An OPS principal can SEE both owner-A's and owner-B's stock (wide readScope), but a
        // single extinguish call must not silently attribute the EXT order to just one of them.
        tenantContext.clientId = 87204L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThatThrownBy {
            extinguishService.extinguish(stockUnitIds = listOf(mixedOwnerStockA, mixedOwnerStockB), unitLoadId = null)
        }.isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    // ── M2: merge into an open EXT order refuses an unhonorable pick-bin override ────────────

    @Test
    @Order(16)
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "87205"), Claim(key = "tenant_code", value = "ACME")])
    fun `merge into open EXT order refuses a targetUnitLoadTypeId override`() {
        // Regression pin for final-review finding M2: a targetUnitLoadTypeId supplied on a call
        // that merges into an already-open EXT order was silently dropped -- the merge path
        // reuses the EXISTING order's pick bin, so the override could never actually be honored.
        seedPackStaging()
        val (_, su1) = seedStockUnit(20.0)
        val (_, su2) = seedStockUnit(30.0)
        val someOtherTypeId = given().contentType(ContentType.JSON)
            .body("""{"name":"Custom Pick Bin-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/unit-load-types").then().statusCode(201).extract().jsonPath().getLong("id")
        tenantContext.clientId = 87205L

        // first call mints the EXT order (no override)
        extinguishService.extinguish(stockUnitIds = listOf(su1), unitLoadId = null)
        // second call for the same owner supplies an override that cannot be honored
        val ex = assertThrows<FulfillmentException.ValidationFailed> {
            extinguishService.extinguish(
                stockUnitIds = listOf(su2), unitLoadId = null,
                targetUnitLoadTypeId = someOtherTypeId,
            )
        }
        assertThat(ex.message).contains("open EXT order")
        // and the refusal rolled back: su2 is still fully available (no half-taken reservation)
        assertThat(reservedAmountOf(su2)).isEqualTo(0.0)
        assertThat(availableAmountOf(su2)).isEqualTo(30.0)
    }

    private companion object {
        var crossTenantStockUnitId: Long = -1L
        var mixedOwnerStockA: Long = -1L
        var mixedOwnerStockB: Long = -1L
    }
}
