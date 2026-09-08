package com.karyo.fulfillment

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.ConsolidatedOrderGuard
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.PackoutStrategyResolver
import com.karyo.fulfillment.spi.PackoutContext
import com.karyo.fulfillment.spi.PackoutResult
import com.karyo.fulfillment.spi.PlannedShippingUnit
import com.karyo.fulfillment.spi.PlannedShippingUnitLine
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.orders.spi.OrderStrategyLookup
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class PackingServiceTest {

    @Inject lateinit var packingService: PackingService

    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository

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
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pack a picked order into one shipping unit and advance the order to PACKED`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()

        // Release to picking + confirm all picks via REST (drives order -> PICKED 600)
        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        po.getList<Map<String, Any>>("[0].picks").forEach { pick ->
            val amt = (pick["plannedAmount"] as Number).toDouble()
            given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
        }
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))

        // Prime tenant for direct service calls, then open + pack
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        assertThat(shipment.state).isEqualTo(ShipmentState.PACKING.code)
        val packed = packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()

        assertThat(packed.state).isEqualTo(ShipmentState.PACKED.code)
        val units = shippingUnitRepository.findByShipmentId(shipment.id!!)
        assertThat(units).hasSize(1)
        assertThat(units.first().positionIndex).isEqualTo(1)
        assertThat(units.first().shippingUnitNumber).isEqualTo("${shipment.shipmentNumber}-SU1")
        val lines = shippingUnitRepository.findLinesByUnitId(units.first().id!!)
        assertThat(lines).isNotEmpty
        assertThat(lines.first().sourcePickId).isNotNull

        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(650))
    }

    /**
     * P1 numbering-collision fix (packing-facts.md): before the fix, `pack()` derived
     * `positionIndex`/`shippingUnitNumber` from the per-call loop index alone, so a second
     * `pack()` call on an incomplete packout (`PackoutResult.complete == false`) regenerated
     * `-SU1` again and hit the `UNIQUE(client_id, shipping_unit_number)` constraint. Unit-level
     * (mocked collaborators, no CDI/DB) because it drives a fake strategy that returns
     * `complete=false`, not reachable through the built-in OneToOne strategy, which always
     * completes in one call.
     *
     * Extended for Task 3 (multi-packet infrastructure): the first call's result is
     * `complete=false` (units persisted, container stock NOT flipped, shipment stays PACKING);
     * the second call's result is `complete=true` (numbering continues from 2, then container
     * stock + shipment/order state flip). `verify(exactly = 1)` on `stockPicker.packContainer`
     * and `orderProgressionPort.markPacked` pins that the flip happens exactly once across BOTH
     * calls, not once per call.
     */
    @Test
    fun `second pack call on an incomplete packout continues numbering instead of colliding`() {
        val fx = buildNumberingFixture()

        val afterFirstCall = fx.service.pack(1L, BigDecimal("2.5"), "CARTON")
        assertThat(afterFirstCall.state).isEqualTo(ShipmentState.PACKING.code)
        verify(exactly = 0) { fx.stockPicker.packContainer(any(), any()) }

        val afterSecondCall = fx.service.pack(1L, BigDecimal("2.5"), "CARTON")
        assertThat(afterSecondCall.state).isEqualTo(ShipmentState.PACKED.code)

        assertThat(fx.persistedUnits).hasSize(2)
        assertThat(fx.persistedUnits[0].positionIndex).isEqualTo(1)
        assertThat(fx.persistedUnits[0].shippingUnitNumber).isEqualTo("SHP-1-SU1")
        assertThat(fx.persistedUnits[1].positionIndex).isEqualTo(2)
        assertThat(fx.persistedUnits[1].shippingUnitNumber).isEqualTo("SHP-1-SU2")
        verify(exactly = 1) { fx.stockPicker.packContainer(50L, 1L) }
        verify(exactly = 1) { fx.orderProgressionPort.markPacked(10L, 1L) }
    }

    /** Everything the numbering test above needs: the wired [PackingService] plus handles onto
     *  the collaborators/state it asserts against. Split out purely to keep the @Test function
     *  itself under the LongMethod ceiling -- same pattern as [LedgerWitnessFixture] below. */
    private class NumberingFixture(
        val service: PackingService,
        val stockPicker: StockPicker,
        val orderProgressionPort: OrderProgressionPort,
        val persistedUnits: MutableList<ShippingUnit>,
    )

    private fun buildNumberingFixture(): NumberingFixture {
        val shipmentRepository = mockk<ShipmentRepository>()
        val shippingUnitRepository = mockk<ShippingUnitRepository>()
        val pickOrderRepository = mockk<PickOrderRepository>()
        val pickRepository = mockk<PickRepository>()
        val packoutResolver = mockk<PackoutStrategyResolver>()
        val orderStrategyLookup = mockk<OrderStrategyLookup>()
        val orderProgressionPort = mockk<OrderProgressionPort>(relaxed = true)
        val deliveryOrderLookup = mockk<DeliveryOrderLookup>()
        val stockPicker = mockk<StockPicker>(relaxed = true)
        val outboxService = mockk<OutboxService>(relaxed = true)
        val tenantContext = mockk<TenantContext>()
        val sequenceNumberService = mockk<SequenceNumberService>()

        val shipment = Shipment().apply {
            id = 1L; clientId = 1L; shipmentNumber = "SHP-1"; deliveryOrderId = 10L
            deliveryOrderNumber = "DO-1"; state = ShipmentState.PACKING.code
        }
        val pickOrder = PickOrder().apply {
            id = 5L; deliveryOrderId = 10L; deliveryOrderNumber = "DO-1"
            targetUnitLoadId = 50L; state = PickState.PICKED.code
        }

        every { tenantContext.clientId } returns 1L
        every { shipmentRepository.findByIdAndClient(1L, 1L) } returns shipment
        // Row 8 review fix: pack() now reads ALL sibling pick orders (createTypeOrders can
        // mint more than one), not an arbitrary findByDeliveryOrderId() row.
        every { pickOrderRepository.findAllByDeliveryOrderId(10L, 1L) } returns listOf(pickOrder)
        every { pickRepository.findByPickOrderId(5L) } returns emptyList()
        every { orderStrategyLookup.findPickingStrategy(10L) } returns null

        val persistedUnits = mutableListOf<ShippingUnit>()
        every { shippingUnitRepository.findByShipmentId(1L) } answers { persistedUnits.toList() }
        every { shippingUnitRepository.persist(any<ShippingUnit>()) } answers {
            val u = firstArg<ShippingUnit>()
            u.id = persistedUnits.size + 1L
            persistedUnits.add(u)
        }
        every { shippingUnitRepository.persistLine(any()) } just Runs
        // Task 6 (row :1354): packSiblingContainers now reads the consumption ledger once per
        // call -- this test's PickOrder has no picks (pickRepository.findByPickOrderId returns
        // emptyList()), so the ledger is irrelevant to it, but the mock must still answer.
        every { shippingUnitRepository.consumedAmountsByPick(1L) } returns emptyMap()

        val plannedUnit1 = PlannedShippingUnit(
            type = "CARTON", weight = BigDecimal("1.0"), unitLoadId = null, lines = emptyList(),
        )
        val plannedUnit2 = PlannedShippingUnit(
            type = "CARTON", weight = BigDecimal("1.0"), unitLoadId = null, lines = emptyList(),
        )
        every { packoutResolver.resolve(any()) } returnsMany listOf(
            PackoutResult(shippingUnits = listOf(plannedUnit1), complete = false),
            PackoutResult(shippingUnits = listOf(plannedUnit2), complete = true),
        )
        every { orderProgressionPort.markPacked(10L, 1L) } just Runs
        every { stockPicker.packContainer(50L, 1L) } returns 1

        val service = PackingService(
            shipmentRepository, shippingUnitRepository, pickOrderRepository, pickRepository,
            packoutResolver, orderStrategyLookup, orderProgressionPort, deliveryOrderLookup,
            stockPicker, outboxService, tenantContext, sequenceNumberService, mockk<ConsolidatedOrderGuard>(),
        )
        return NumberingFixture(service, stockPicker, orderProgressionPort, persistedUnits)
    }

    /**
     * Task 6 witness (a) (row :1354, adjudication A3): the core duplication bug. `Pick.state`
     * tops out at PICKED -- packing never advances it -- so [PickRepository.findByPickOrderId]
     * returns the SAME fully-picked pick on every call, forever. Before the ledger fix, selection
     * was "every still-PICKED pick," so calling `pack()` again on a shipment back in PACKING (the
     * legitimate case is after `ShippingLifecycleService.removeUnit`/`.removeLine`, or a future
     * incremental strategy) re-submitted the pick at its FULL amount, duplicating the
     * `ShippingUnitLine` and double-flipping container stock.
     *
     * Mocked (same style as the numbering test above) so the ledger itself is driven by real
     * arithmetic: `consumedAmountsByPick` is answered from the SAME `persistedLines` list that
     * `persistLine` appends to, so the mock behaves exactly like the real grouped query would.
     * `shipment.state` is forced back to PACKING directly between calls -- not via a real
     * `removeUnit`/`removeLine` call (that is witness (b)/(c)'s job) -- to isolate exactly the
     * SELECTION arithmetic this task changes from the state-transition machinery around it.
     */
    @Test
    fun `second pack call after full consumption is a no-op -- no duplicate lines, no double stock flip`() {
        val fx = buildLedgerWitnessFixture()

        val first = fx.service.pack(1L, BigDecimal("2.5"), "CARTON")
        assertThat(first.state).isEqualTo(ShipmentState.PACKED.code)
        assertThat(fx.persistedUnits).hasSize(1)
        assertThat(fx.persistedLines).hasSize(1)
        assertThat(fx.persistedLines.single().amount).isEqualByComparingTo("60")
        verify(exactly = 1) { fx.stockPicker.packContainer(50L, 1L) }

        // Simulate a caller invoking pack() again while the shipment is back in PACKING (the
        // real-world trigger is removeUnit/removeLine regressing it) -- Pick.state never advances
        // past PICKED, so pickRepository still hands back the SAME fully-picked pick unchanged.
        fx.shipment.state = ShipmentState.PACKING.code

        val second = fx.service.pack(1L, BigDecimal("2.5"), "CARTON")

        assertThat(second.state).isEqualTo(ShipmentState.PACKED.code)
        assertThat(fx.persistedUnits).hasSize(1)
        assertThat(fx.persistedLines).hasSize(1)
        verify(exactly = 1) { fx.stockPicker.packContainer(50L, 1L) }
        // The second call resolves nothing at all -- packSiblingContainers skips a container
        // whose remaining work is empty rather than calling the strategy with an empty pick list.
        verify(exactly = 1) { fx.packoutResolver.resolve(any()) }
    }

    /** Everything the witness test above needs: the wired [PackingService] plus handles onto
     *  the collaborators/state it asserts against. Split out purely to keep the @Test function
     *  itself short -- see the witness test's own KDoc for what this fixture proves. */
    private class LedgerWitnessFixture(
        val service: PackingService,
        val shipment: Shipment,
        val stockPicker: StockPicker,
        val packoutResolver: PackoutStrategyResolver,
        val persistedUnits: MutableList<ShippingUnit>,
        val persistedLines: MutableList<ShippingUnitLine>,
    )

    private fun buildLedgerWitnessFixture(): LedgerWitnessFixture {
        val shipmentRepository = mockk<ShipmentRepository>()
        val shippingUnitRepository = mockk<ShippingUnitRepository>()
        val pickOrderRepository = mockk<PickOrderRepository>()
        val pickRepository = mockk<PickRepository>()
        val packoutResolver = mockk<PackoutStrategyResolver>()
        val orderStrategyLookup = mockk<OrderStrategyLookup>()
        val orderProgressionPort = mockk<OrderProgressionPort>(relaxed = true)
        val deliveryOrderLookup = mockk<DeliveryOrderLookup>()
        val stockPicker = mockk<StockPicker>(relaxed = true)
        val outboxService = mockk<OutboxService>(relaxed = true)
        val tenantContext = mockk<TenantContext>()
        val sequenceNumberService = mockk<SequenceNumberService>()

        val shipment = Shipment().apply {
            id = 1L; clientId = 1L; shipmentNumber = "SHP-1"; deliveryOrderId = 10L
            deliveryOrderNumber = "DO-1"; state = ShipmentState.PACKING.code
        }
        val pickOrder = PickOrder().apply {
            id = 5L; deliveryOrderId = 10L; deliveryOrderNumber = "DO-1"
            targetUnitLoadId = 50L; state = PickState.PICKED.code
        }
        val pick = Pick().apply {
            id = 7L; itemDataId = 200L; itemDataNumber = "SKU-X"
            pickedAmount = BigDecimal("60"); state = PickState.PICKED.code; targetStockUnitId = 300L
        }

        every { tenantContext.clientId } returns 1L
        every { shipmentRepository.findByIdAndClient(1L, 1L) } returns shipment
        every { pickOrderRepository.findAllByDeliveryOrderId(10L, 1L) } returns listOf(pickOrder)
        every { pickRepository.findByPickOrderId(5L) } returns listOf(pick)
        every { orderStrategyLookup.findPickingStrategy(10L) } returns null
        every { orderProgressionPort.markPacked(10L, 1L) } just Runs
        every { stockPicker.packContainer(50L, 1L) } returns 1

        val persistedUnits = mutableListOf<ShippingUnit>()
        val persistedLines = mutableListOf<ShippingUnitLine>()
        every { shippingUnitRepository.findByShipmentId(1L) } answers { persistedUnits.toList() }
        every { shippingUnitRepository.persist(any<ShippingUnit>()) } answers {
            val u = firstArg<ShippingUnit>()
            u.id = persistedUnits.size + 1L
            persistedUnits.add(u)
        }
        every { shippingUnitRepository.persistLine(any()) } answers { persistedLines.add(firstArg()) }
        // The ledger mock is answered from the SAME persistedLines list persistLine appends to,
        // so it behaves exactly like the real grouped query would.
        every { shippingUnitRepository.consumedAmountsByPick(1L) } answers {
            persistedLines.filter { it.sourcePickId != null }
                .groupBy { it.sourcePickId!! }
                .mapValues { (_, lines) -> lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount } }
        }
        // Mirrors OneToOnePackout.pack: one unit per container, one line per handed-in pick, at
        // the pick's (already-remaining) amount -- exercising the same shape the real strategy
        // would, without depending on the CDI-resolved bean.
        every { packoutResolver.resolve(any()) } answers {
            val ctx = firstArg<PackoutContext>()
            PackoutResult(
                shippingUnits = listOf(
                    PlannedShippingUnit(
                        type = ctx.type,
                        weight = ctx.weight,
                        unitLoadId = ctx.pickContainerUnitLoadId,
                        lines = ctx.picks.map {
                            PlannedShippingUnitLine(it.itemDataId, it.itemDataNumber, it.pickedAmount, it.pickId, it.lotNumber)
                        },
                    ),
                ),
                complete = true,
            )
        }

        val service = PackingService(
            shipmentRepository, shippingUnitRepository, pickOrderRepository, pickRepository,
            packoutResolver, orderStrategyLookup, orderProgressionPort, deliveryOrderLookup,
            stockPicker, outboxService, tenantContext, sequenceNumberService, mockk<ConsolidatedOrderGuard>(),
        )
        return LedgerWitnessFixture(service, shipment, stockPicker, packoutResolver, persistedUnits, persistedLines)
    }
}
