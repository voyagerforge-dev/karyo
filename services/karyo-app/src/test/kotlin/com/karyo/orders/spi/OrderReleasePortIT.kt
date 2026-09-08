package com.karyo.orders.spi

import com.karyo.inventory.api.dto.CreateStockUnitRequest
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.service.StockService
import com.karyo.inventory.service.UnitLoadService
import com.karyo.orders.dto.CreateDeliveryOrderLineRequest
import com.karyo.orders.dto.CreateDeliveryOrderRequest
import com.karyo.orders.repository.DeliveryOrderLineRepository
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.service.OrderService
import com.karyo.product.dto.CreateItemUnitRequest
import com.karyo.product.dto.CreateProductRequest
import com.karyo.product.service.ItemUnitService
import com.karyo.product.service.ProductService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Integration test (REAL beans) for the [OrderReleasePort] wave-side orchestration seam:
 * eligible listing, wave assignment/membership, release-for-wave (delegates to
 * OrderService.release), line-scoped reservation release (SKIP/HOLD_ORDER path), the
 * `waveConfig` JSON parse, and the cross-tenant safety of [OrderReleasePort.releaseLineReservations]
 * (Task 3 review CRITICAL-1).
 *
 * Fixture helpers lifted from DeliveryOrderLookupTest.
 */
@QuarkusTest
class OrderReleasePortIT {

    @Inject
    lateinit var port: OrderReleasePort

    // ── Direct-bean fixture helpers for the cross-tenant test (bypass REST/JWT so a SECOND
    // tenant's data can be seeded without a second @TestSecurity context) ──

    @Inject
    lateinit var itemUnitService: ItemUnitService

    @Inject
    lateinit var productService: ProductService

    @Inject
    lateinit var unitLoadService: UnitLoadService

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var orderService: OrderService

    @Inject
    lateinit var lineRepository: DeliveryOrderLineRepository

    @Inject
    lateinit var reservationRepository: OrderLineReservationRepository

    /**
     * The injected AMBIENT [TenantContext] bean -- distinct from the throwaway [TenantContext]
     * instances [seedReservedLineForClient] constructs to pass into `UnitLoadService.create`/
     * `StockService.createStock`. Needed because `OrderService.create`/`release` still read a
     * FEW ambient-only SPIs internally (`ProductLookup.findById`/`findNamesByIds`, used to
     * validate/enrich order lines) that were not in scope for the Task 3 review's StockReserver
     * fix -- this is a TEST-only prime/restore, legitimate here because it mimics exactly what
     * `TenantFilter` does for a real request under the foreign tenant; production code makes no
     * such assumption anywhere in this module (see the class KDoc on [DefaultOrderReleasePort]).
     */
    @Inject
    lateinit var ambientTenantContext: TenantContext

    // ── REST seeding helpers (lifted from DeliveryOrderLookupTest) ──────────

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Wave Release Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double): Long =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createOrder(itemDataId: Long, amount: Double): ValidatableResponse =
        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"Wave Release Test Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)

    private fun releaseOrder(orderId: Long) {
        given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
    }

    private fun seedProductWithStock(suffix: Long, stockAmount: Double): Long {
        val itemUnitId = createItemUnit("WR-${suffix.toString().takeLast(10)}")
        val number = "WR-SKU-$suffix"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-WR-$suffix")
        createStock(unitLoadId, productId, number, stockAmount)
        return productId
    }

    /** Seeds a product with [stockAmount] stock, creates a CREATED order for [orderAmount]. Returns order id. */
    private fun seedCreatedOrder(orderAmount: Double, stockAmount: Double = 100.0): Long {
        val suffix = System.nanoTime()
        val productId = seedProductWithStock(suffix, stockAmount)
        return createOrder(productId, orderAmount).extract().jsonPath().getLong("id")
    }

    /**
     * Seeds a REAL fully-reserved order for an arbitrary [clientId] via direct-bean calls
     * (product/unit-load/stock/order creation + release all take an explicit clientId/TenantContext
     * argument in this codebase, so no REST/JWT round-trip is needed to seed a SECOND tenant's
     * data alongside the test method's own @OidcSecurity(client_id=1) identity). Returns the
     * created line id, which carries a genuine [com.karyo.orders.domain.model.OrderLineReservation]
     * row and a non-zero reservedAmount -- exactly the state the CRITICAL-1 regression test needs
     * to prove untouched.
     */
    private fun seedReservedLineForClient(clientId: Long, orderAmount: Double, stockAmount: Double): Long {
        val suffix = System.nanoTime()
        val tenant = TenantContext().apply {
            this.clientId = clientId
            this.principalKind = PrincipalKind.OWNER
        }
        val itemUnit = itemUnitService.createUnit(CreateItemUnitRequest(name = "XT-${suffix.toString().takeLast(10)}"))
        val product = productService.createProduct(
            CreateProductRequest(number = "XT-SKU-$suffix", name = "Cross Tenant Test Product", itemUnitId = itemUnit.id),
            clientId,
        )
        val unitLoad = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = clientId,
                labelId = "UL-XT-$suffix",
                unitLoadTypeId = 1,
                storageLocationId = 100,
                storageLocationName = "A-01-01",
            ),
            tenant,
        )
        stockService.createStock(
            CreateStockUnitRequest(
                itemDataId = product.id,
                itemDataNumber = product.number,
                amount = BigDecimal.valueOf(stockAmount),
                unitLoadId = unitLoad.id!!,
                state = 300,
            ),
            tenant,
        )
        // OrderService.create/release still read a FEW ambient-only SPIs internally
        // (ProductLookup.findById/findNamesByIds) -- prime the ambient TenantContext to the
        // foreign clientId for just these two calls, then restore the test's own identity so
        // nothing leaks into the assertions (or any other test) that follow. See the field KDoc
        // on [ambientTenantContext].
        val restoreClientId = ambientTenantContext.clientId
        val restorePrincipalKind = ambientTenantContext.principalKind
        val lineId: Long
        try {
            ambientTenantContext.clientId = clientId
            ambientTenantContext.principalKind = PrincipalKind.OWNER
            val order = orderService.create(
                CreateDeliveryOrderRequest(
                    customerName = "Cross Tenant Test Customer",
                    lines = listOf(CreateDeliveryOrderLineRequest(itemDataId = product.id, amount = BigDecimal.valueOf(orderAmount))),
                ),
                clientId,
            )
            lineId = order.lines.first().id
            orderService.release(order.id, clientId)
        } finally {
            ambientTenantContext.clientId = restoreClientId
            ambientTenantContext.principalKind = restorePrincipalKind
        }
        return lineId
    }

    private val clientId = 1L

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findWaveEligible excludes waved and non-CREATED orders`() {
        val eligibleId = seedCreatedOrder(orderAmount = 10.0)
        val releasedId = seedCreatedOrder(orderAmount = 10.0)
        releaseOrder(releasedId)
        val wavedId = seedCreatedOrder(orderAmount = 10.0)
        port.assignToWave(listOf(wavedId), waveId = 777L, clientId = clientId)

        val eligible = port.findWaveEligible(strategyId = null, clientId = clientId, limit = 500)
        val eligibleIds = eligible.map { it.orderId }

        assertThat(eligibleIds).contains(eligibleId)
        assertThat(eligibleIds).doesNotContain(releasedId, wavedId)
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `assignToWave refuses a RELEASED order`() {
        val releasedId = seedCreatedOrder(orderAmount = 10.0)
        releaseOrder(releasedId)

        assertThatThrownBy { port.assignToWave(listOf(releasedId), waveId = 1L, clientId = clientId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `assignToWave and memberViews round-trip`() {
        val orderId = seedCreatedOrder(orderAmount = 10.0)

        port.assignToWave(listOf(orderId), waveId = 42L, clientId = clientId)

        assertThat(port.waveIdOf(orderId, clientId)).isEqualTo(42L)
        val members = port.memberViews(42L, clientId)
        assertThat(members.map { it.orderId }).contains(orderId)
        assertThat(members.first { it.orderId == orderId }.lineCount).isEqualTo(1)
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releaseForWave reserves fully covered order`() {
        val orderId = seedCreatedOrder(orderAmount = 60.0, stockAmount = 100.0)

        val outcome = port.releaseForWave(orderId, clientId)

        assertThat(outcome.orderId).isEqualTo(orderId)
        assertThat(outcome.shortages).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releaseForWave reports a shortage snapshot`() {
        val orderId = seedCreatedOrder(orderAmount = 60.0, stockAmount = 40.0)

        val outcome = port.releaseForWave(orderId, clientId)

        assertThat(outcome.shortages).hasSize(1)
        val shortage = outcome.shortages.first()
        assertThat(shortage.orderId).isEqualTo(orderId)
        assertThat(shortage.requested).isEqualByComparingTo("60.0")
        assertThat(shortage.reserved).isEqualByComparingTo("40.0")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releaseLineReservations zeroes reservedAmount and frees stock for a second reservation`() {
        val suffix = System.nanoTime()
        val productId = seedProductWithStock(suffix, stockAmount = 100.0)
        val orderResponse = createOrder(productId, amount = 60.0)
        val orderId = orderResponse.extract().jsonPath().getLong("id")
        val lineId = orderResponse.extract().jsonPath().getLong("lines[0].id")
        releaseOrder(orderId)

        // Sanity: fully reserved before release.
        val beforeReserved = given()
            .`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("lines[0].reservedAmount")
        assertThat(beforeReserved).isEqualTo(60.0)

        port.releaseLineReservations(listOf(lineId), clientId)

        val afterReserved = given()
            .`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("lines[0].reservedAmount")
        assertThat(afterReserved).isEqualTo(0.0)

        // The freed 60 (plus untouched 40) should now cover a fresh order for 100.
        val secondOrderId = createOrder(productId, amount = 100.0).extract().jsonPath().getLong("id")
        releaseOrder(secondOrderId)
        val secondReserved = given()
            .`when`().get("/api/v1/delivery-orders/$secondOrderId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("lines[0].reservedAmount")
        assertThat(secondReserved).isEqualTo(100.0)
    }

    // ── Task 3 review fixes ─────────────────────────────────────────────

    /**
     * CRITICAL-1 regression: a foreign lineId must never cause
     * [OrderReleasePort.releaseLineReservations] to delete another tenant's
     * `order_line_reservations` row or zero its `reservedAmount`. The test method's own identity
     * is client 1 (ACME); [seedReservedLineForClient] seeds a genuinely reserved line for client 2
     * (GLOBEX, pre-seeded per `clients` table doc) via direct-bean calls, then calls the port AS
     * client 1 but naming client 2's lineId.
     */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `releaseLineReservations never touches a foreign tenant's reservation`() {
        val foreignClientId = 2L
        val foreignLineId = seedReservedLineForClient(foreignClientId, orderAmount = 25.0, stockAmount = 100.0)

        // Sanity: the foreign line really is reserved before the cross-tenant call.
        val foreignLineBefore = lineRepository.findByIdAndClient(foreignLineId, foreignClientId)
        assertThat(foreignLineBefore).isNotNull
        assertThat(foreignLineBefore!!.reservedAmount).isEqualByComparingTo("25.0")
        val reservationRowsBefore = reservationRepository.findByLineId(foreignLineId)
        assertThat(reservationRowsBefore).isNotEmpty

        // Client 1 calls releaseLineReservations naming client 2's lineId.
        port.releaseLineReservations(listOf(foreignLineId), clientId)

        // The foreign tenant's row and reservedAmount must be completely untouched.
        val foreignLineAfter = lineRepository.findByIdAndClient(foreignLineId, foreignClientId)
        assertThat(foreignLineAfter).isNotNull
        assertThat(foreignLineAfter!!.reservedAmount).isEqualByComparingTo("25.0")
        val reservationRowsAfter = reservationRepository.findByLineId(foreignLineId)
        assertThat(reservationRowsAfter).hasSameSizeAs(reservationRowsBefore)
        assertThat(reservationRowsAfter.sumOf { it.amount }).isEqualByComparingTo("25.0")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `waveConfig with a null strategyId returns the documented defaults`() {
        val config = port.waveConfig(null)

        assertThat(config.waveAutoRelease).isFalse()
        assertThat(config.waveMaxOrders).isEqualTo(200)
        assertThat(config.wavePickMode).isEqualTo("HYBRID")
        assertThat(config.waveShortageAction).isEqualTo("SKIP")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `waveConfig fills absent keys from defaults when the strategy JSON is partial`() {
        val strategyId = given()
            .contentType(ContentType.JSON)
            .body(
                """{"name":"WaveConfigTest-${System.nanoTime()}","extensionProperties":{"waveAutoRelease":true,"waveMaxOrders":50}}"""
            )
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        val config = port.waveConfig(strategyId)

        // Present keys honored.
        assertThat(config.waveAutoRelease).isTrue()
        assertThat(config.waveMaxOrders).isEqualTo(50)
        // Absent keys fall back to defaults.
        assertThat(config.wavePickMode).isEqualTo("HYBRID")
        assertThat(config.waveShortageAction).isEqualTo("SKIP")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `assignToWave refuses an already-waved order`() {
        val orderId = seedCreatedOrder(orderAmount = 10.0)
        port.assignToWave(listOf(orderId), waveId = 1L, clientId = clientId)

        assertThatThrownBy { port.assignToWave(listOf(orderId), waveId = 2L, clientId = clientId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `clearWave is idempotent -- a second call is a no-op`() {
        val orderId = seedCreatedOrder(orderAmount = 10.0)
        port.assignToWave(listOf(orderId), waveId = 5L, clientId = clientId)
        assertThat(port.waveIdOf(orderId, clientId)).isEqualTo(5L)

        port.clearWave(listOf(orderId), clientId)
        assertThat(port.waveIdOf(orderId, clientId)).isNull()

        // Second call: already cleared, must not throw and must remain null.
        port.clearWave(listOf(orderId), clientId)
        assertThat(port.waveIdOf(orderId, clientId)).isNull()
    }

    // ── Selection-rules sprint, Task 1 ──────────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `waveConfig parses selection keys with defaults`() {
        val strategyId = given()
            .contentType(ContentType.JSON)
            .body(
                """{"name":"WaveSelectionConfigTest-${System.nanoTime()}","extensionProperties":""" +
                    """{"waveSelectionStrategy":"rule-based","waveSelectionRuleId":7,"waveMinPrio":60}}"""
            )
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        val cfg = port.waveConfig(strategyId)

        assertThat(cfg.waveSelectionStrategy).isEqualTo("rule-based")
        assertThat(cfg.waveSelectionRuleId).isEqualTo(7L)
        assertThat(cfg.waveMinPrio).isEqualTo(60)
        assertThat(cfg.waveDueWithinDays).isNull()
        assertThat(cfg.waveIncludeUndated).isTrue()
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `WaveOrderView carries deliveryDate country externalNumber`() {
        val suffix = System.nanoTime()
        val productId = seedProductWithStock(suffix, stockAmount = 100.0)
        val orderId = given()
            .contentType(ContentType.JSON)
            .body(
                """{"customerName":"Wave Candidate Test Customer","deliveryDate":"2026-09-01",""" +
                    """"country":"DE","externalNumber":"EXT-$suffix",""" +
                    """"lines":[{"itemDataId":$productId,"amount":10.0}]}"""
            )
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        val eligible = port.findWaveEligible(strategyId = null, clientId = clientId, limit = 500)
        val view = eligible.first { it.orderId == orderId }

        assertThat(view.deliveryDate).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(view.country).isEqualTo("DE")
        assertThat(view.externalNumber).isEqualTo("EXT-$suffix")
    }
}
