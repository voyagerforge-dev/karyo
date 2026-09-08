package com.karyo.orders.service

import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.domain.model.GoodsReceiptLine
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.spi.CrossDockOrdersPort
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Integration test (REAL beans) for [CrossDockOrdersPort] -- the orders-side read/write seam the
 * paid cross-docking engine (Advanced Fulfillment) consumes. `reserveSlice`/`releaseSlice` mirror
 * [OrderService.reserveLine]'s bookkeeping (an [com.karyo.orders.domain.model.OrderLineReservation]
 * row per slice plus the line's `reservedAmount` running total) and the same
 * [com.karyo.inventory.api.spi.StockReserver] collaborator for the stock-side `reservedAmount`, so
 * this test verifies the inventory-side reservation too (not just the orders-side row), the same
 * way [DefaultStockReserverTest] does for `reserveOnStockUnit`.
 */
@QuarkusTest
class CrossDockOrdersPortTest {

    @Inject
    lateinit var port: CrossDockOrdersPort

    @Inject
    lateinit var asnRepository: AsnRepository

    @Inject
    lateinit var goodsReceiptRepository: GoodsReceiptRepository

    @Inject
    lateinit var deliveryOrderRepository: DeliveryOrderRepository

    @Inject
    lateinit var tenantContext: TenantContext

    // ── REST seed helpers (module convention, see DefaultStockReserverTest) ────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"CrossDock Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun stockReservedAmount(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getDouble("reservedAmount")

    // ── Direct entity seed helpers (module convention, see DefaultGoodsReceiptLookupTest) ──

    /** One order with one open (never released) line -- state UNDEFINED(0), shortage = amount. */
    @Transactional
    fun persistOrderWithLine(
        clientId: Long,
        itemDataId: Long,
        amount: BigDecimal = BigDecimal.TEN,
        deliveryDate: LocalDate? = null,
    ): DeliveryOrder {
        val seq = System.nanoTime()
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = "DO-XDOCK-$seq"
            this.deliveryDate = deliveryDate
            this.state = OrderState.CREATED.code
        }
        order.lines.add(
            DeliveryOrderLine().apply {
                this.deliveryOrder = order
                this.lineNumber = 1
                this.itemDataId = itemDataId
                this.itemDataNumber = "SKU-XDOCK-$seq"
                this.amount = amount
                this.reservedAmount = BigDecimal.ZERO
                this.state = OrderState.UNDEFINED.code
            },
        )
        deliveryOrderRepository.persist(order)
        return order
    }

    @Transactional
    fun persistAsnWithCrossDockLine(clientId: Long, itemDataId: Long, crossDockTargetOrderId: Long?): AsnLine {
        val seq = System.nanoTime()
        val asn = Asn().apply {
            this.clientId = clientId
            this.asnNumber = "ASN-XDOCK-$seq"
            this.state = OrderState.RELEASED.code
        }
        asn.lines.add(
            AsnLine().apply {
                this.asn = asn
                this.lineNumber = 1
                this.itemDataId = itemDataId
                this.itemDataNumber = "SKU-XDOCK-$seq"
                this.expectedAmount = BigDecimal.TEN
                this.state = OrderState.CREATED.code
                this.crossDockDeliveryOrderId = crossDockTargetOrderId
            },
        )
        asnRepository.persist(asn) // cascades AsnLine (ALL) -- line id available after this call
        return asn.lines.first()
    }

    @Transactional
    fun persistReceiptLine(clientId: Long, itemDataId: Long, asnLineId: Long?): GoodsReceiptLine {
        val seq = System.nanoTime()
        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = "GR-XDOCK-$seq"
            this.state = OrderState.STARTED.code
        }
        receipt.lines.add(
            GoodsReceiptLine().apply {
                this.goodsReceipt = receipt
                this.asnLineId = asnLineId
                this.itemDataId = itemDataId
                this.itemDataNumber = "SKU-XDOCK-$seq"
                this.amount = BigDecimal.TEN
                this.locationId = 1L
                this.locationName = "DOCK-01"
                this.unitLoadLabel = "UL-XDOCK-$seq"
                this.stockUnitId = seq
                this.unitLoadId = 900L
            },
        )
        goodsReceiptRepository.persist(receipt)
        return receipt.lines.first()
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `crossDockTargetFor returns the ASN line link and null when absent`() {
        // seeded: receipt line A against ASN line with target order O; receipt line B blind
        val order = persistOrderWithLine(clientId = 1L, itemDataId = 42L)
        val asnLine = persistAsnWithCrossDockLine(clientId = 1L, itemDataId = 42L, crossDockTargetOrderId = order.id)
        val receiptLineA = persistReceiptLine(clientId = 1L, itemDataId = 42L, asnLineId = asnLine.id)
        val receiptLineB = persistReceiptLine(clientId = 1L, itemDataId = 42L, asnLineId = null)

        assertEquals(order.id, port.crossDockTargetFor(receiptLineA.id!!, 1L))
        assertNull(port.crossDockTargetFor(receiptLineB.id!!, 1L))
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `openLineCandidates returns open lines earliest ship-by first and respects tenant`() {
        // seeded two orders, different ship-by
        val itemDataId = System.nanoTime()
        val later = persistOrderWithLine(clientId = 1L, itemDataId = itemDataId, deliveryDate = LocalDate.of(2026, 9, 1))
        val earlier = persistOrderWithLine(clientId = 1L, itemDataId = itemDataId, deliveryDate = LocalDate.of(2026, 8, 20))
        // foreign tenant -- must never appear in the tenant-1 result.
        persistOrderWithLine(clientId = 999L, itemDataId = itemDataId, deliveryDate = LocalDate.of(2026, 8, 1))

        val candidates = port.openLineCandidates(itemDataId, 1L)

        assertEquals(2, candidates.size)
        assertEquals(earlier.lines.first().id, candidates[0].deliveryOrderLineId)
        assertEquals(later.lines.first().id, candidates[1].deliveryOrderLineId)
        assertEquals(earlier.id, candidates[0].deliveryOrderId)
        assertEquals(BigDecimal.TEN.stripTrailingZeros(), candidates[0].openAmount.stripTrailingZeros())
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reserveSlice persists a reservation, sliceExists sees it, releaseSlice removes it idempotently`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("XD-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("XD-SKU-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-XD-$suffix")
        val stockUnitId = createStock(ulId, productId, "XD-SKU-$suffix", 50.0)
        val order = persistOrderWithLine(clientId = 1L, itemDataId = productId, amount = BigDecimal.TEN)
        val lineId = order.lines.first().id!!
        tenantContext.clientId = 1L

        val ok = port.reserveSlice(lineId, stockUnitId, BigDecimal.ONE, 1L)
        assertTrue(ok)
        assertTrue(port.sliceExists(lineId, stockUnitId, 1L))
        assertEquals(1.0, stockReservedAmount(stockUnitId))

        port.releaseSlice(lineId, stockUnitId, 1L)
        port.releaseSlice(lineId, stockUnitId, 1L)

        assertFalse(port.sliceExists(lineId, stockUnitId, 1L))
        assertEquals(0.0, stockReservedAmount(stockUnitId))
    }

    /**
     * Task 2 review CRITICAL fix regression guard: reproduces the exact shape of a real
     * `@Scheduled` invocation (Task 6's expiry sweep) -- the request scope is active but
     * UNPRIMED, so ambient `TenantContext.clientId` defaults to 0 (see
     * `ReplenishmentScheduler.kt`'s doctrine comment and `ReplenishmentSchedulerIntegrationTest`
     * for the precedent this test mirrors). Deliberately does NOT set `tenantContext.clientId`
     * anywhere in this test method -- the REST seed calls run on the test server's own
     * thread/request scope and do not leak into this method's own `TenantContext`, exactly like
     * `ReplenishmentSchedulerIntegrationTest`'s seeding.
     *
     * Before the fix, `reserveSlice`/`releaseSlice` routed through `StockReserver`'s
     * AMBIENT-`TenantContext` overloads: `reserveOnStockUnit`/`release` without a `clientId`
     * parameter. On this unprimed thread, `StockService.findByIdForWrite` throws
     * `InventoryException.NotFound` on the scope check (ambient clientId 0 != the stock unit's
     * real owner 1), and `DefaultStockReserver.release`'s ambient path SWALLOWS that as a
     * defensive "already deleted" no-op -- so `releaseSlice` deleted its own
     * `OrderLineReservation` row and decremented `line.reservedAmount` while the stock unit's
     * `reservedAmount` was never touched: a permanent availability leak. The fix makes both
     * methods use the explicit-`clientId` `StockReserver` overloads instead, so the port's own
     * `clientId` parameter -- not the ambient context -- decides tenant scope end to end.
     */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reserveSlice and releaseSlice round-trip reservedAmount correctly with an unprimed ambient TenantContext`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("XD-SW-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("XD-SW-SKU-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-XD-SW-$suffix")
        val stockUnitId = createStock(ulId, productId, "XD-SW-SKU-$suffix", 50.0)
        val order = persistOrderWithLine(clientId = 1L, itemDataId = productId, amount = BigDecimal.TEN)
        val lineId = order.lines.first().id!!
        val originalReservedAmount = stockReservedAmount(stockUnitId)

        // No `tenantContext.clientId = 1L` here, deliberately -- see the KDoc above. The port
        // is called with the CORRECT explicit clientId (1) regardless.
        val ok = port.reserveSlice(lineId, stockUnitId, BigDecimal(2), 1L)
        assertTrue(ok)
        assertEquals(originalReservedAmount + 2.0, stockReservedAmount(stockUnitId))

        port.releaseSlice(lineId, stockUnitId, 1L)

        assertFalse(port.sliceExists(lineId, stockUnitId, 1L))
        // The round-trip: reservedAmount must return to exactly what it was before, not be
        // stranded above it (the leak this test guards against).
        assertEquals(originalReservedAmount, stockReservedAmount(stockUnitId))
    }
}
