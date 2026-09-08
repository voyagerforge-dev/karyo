package com.karyo.fulfillment.service

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.fulfillment.spi.WavePickRequest
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Task 4 (wave bulk fulfillment): [WavePickService] (the [BatchPickPort] implementation) turns
 * a wave's member orders' EXISTING reservations into PickOrders -- COMPLETE slices per-order,
 * PICK slices pooled into cross-order batch PickOrders by [com.karyo.fulfillment.spi
 * .PickZoneLookup] zone (the built-in [DefaultPickZoneLookup] maps everything to "UNZONED").
 *
 * Every test seeds two delivery orders via `POST .../release` only (never `releaseToPicking`) --
 * a wave's PickOrders come from [BatchPickPort.generateForWave] directly against the members'
 * reservations, never from the discrete release path.
 */
@QuarkusTest
class WavePickServiceIT {

    @Inject lateinit var batchPickPort: BatchPickPort
    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var waveActivityObserver: TestWaveActivityObserver
    @Inject lateinit var autoPackObserver: TestAutoPackObserver
    @Inject lateinit var entityManager: EntityManager

    @AfterEach
    fun resetObservers() {
        waveActivityObserver.events.clear()
        autoPackObserver.events.clear()
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
            .body("""{"name":"WPS-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"WPStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"WPS-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun createStrategy(name: String, createShippingOrder: Boolean): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","createShippingOrder":$createShippingOrder}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a product+stock unit of [stockAmount], a delivery order for [orderAmount] of it, releases it
     *  (reservations only -- NOT releaseToPicking). [orderAmount] == [stockAmount] -> a COMPLETE slice;
     *  [orderAmount] < [stockAmount] -> a PICK slice. */
    private fun seedReleasedOrder(tag: String, stockAmount: Double, orderAmount: Double, strategyId: Long? = null): Long {
        val s = System.nanoTime()
        // Item-unit name is capped at 20 chars server-side (CreateItemUnitRequest) -- kept
        // independent of [tag]'s length so an arbitrary caller-chosen tag never blows the limit.
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "WPS-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("WPS-$tag-UL-$s")
        createStock(ul, pid, num, stockAmount)
        val strategyField = strategyId?.let { ""","orderStrategyId":$it""" } ?: ""
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","lines":[{"itemDataId":$pid,"amount":$orderAmount}]$strategyField}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /** [complete, partial] delivery order ids -- complete's line fully consumes its stock unit
     *  (COMPLETE slice), partial's line takes only part of a DIFFERENT stock unit (PICK slice). */
    private fun seedCompleteAndPartialOrders(tag: String, strategyId: Long? = null): Pair<Long, Long> {
        val complete = seedReleasedOrder("${tag}A", 100.0, 100.0, strategyId)
        val partial = seedReleasedOrder("${tag}B", 100.0, 40.0, strategyId)
        return complete to partial
    }

    /** Captures the source stock unit id alongside the released order id, for reservation-release
     *  assertions (CRITICAL-2 short-batch-confirm test). Single line, [orderAmount] < [stockAmount]
     *  so the slice is always PICK-type. */
    private data class ReleasedPickLine(val orderId: Long, val stockUnitId: Long)

    private fun seedReleasedPickLine(tag: String, stockAmount: Double, orderAmount: Double): ReleasedPickLine {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "WPS-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("WPS-$tag-UL-$s")
        val stockUnitId = createStock(ul, pid, num, stockAmount)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":$orderAmount}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return ReleasedPickLine(orderId, stockUnitId)
    }

    /** One order, two lines: a full-draw (COMPLETE) line and a partial-draw (PICK) line -- the
     *  exact mixed shape CRITICAL-1(a) needs (one member order owning BOTH slice kinds at once,
     *  unlike [seedCompleteAndPartialOrders]'s two separate single-line orders). */
    private fun seedMixedOrder(tag: String, strategyId: Long? = null): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val numFull = "WPS-${tag}F-SKU-$s"
        val pidFull = createProduct(numFull, iu)
        val ulFull = createUnitLoad("WPS-${tag}F-UL-$s")
        createStock(ulFull, pidFull, numFull, 100.0)
        val numPart = "WPS-${tag}P-SKU-$s"
        val pidPart = createProduct(numPart, iu)
        val ulPart = createUnitLoad("WPS-${tag}P-UL-$s")
        createStock(ulPart, pidPart, numPart, 100.0)
        val strategyField = strategyId?.let { ""","orderStrategyId":$it""" } ?: ""
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","lines":[""" +
                    """{"itemDataId":$pidFull,"amount":100.0},""" +
                    """{"itemDataId":$pidPart,"amount":40.0}]$strategyField}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    private fun orderState(orderId: Long): Int =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

    private fun reservedAmountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("reservedAmount")

    /** A real, POST-able storage location id (IMPORTANT-4's `defaultDestinationLocationId` is
     *  422-validated against a real location -- an arbitrary Long is refused at write time). */
    private fun createDestinationLocation(): Long {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"WPS-DEST-AREA-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON)
            .body("""{"name":"WPS-DEST-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        return given().contentType(ContentType.JSON)
            .body("""{"name":"WPS-DEST-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `HYBRID mode creates one per-order COMPLETE PickOrder and one UNZONED batch PickOrder`() {
        seedPackStaging()
        val (completeOrderId, partialOrderId) = seedCompleteAndPartialOrders("HYB")
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "HYBRID", listOf(completeOrderId, partialOrderId)),
        )

        assertThat(result.pickOrderIds).hasSize(1)
        assertThat(result.batchPickOrderIds).hasSize(1)
        assertThat(result.droppedEachesLines).isEmpty()

        val completePo = pickOrderService.getPickOrder(result.pickOrderIds.single())
        assertThat(completePo.waveId).isEqualTo(waveId)
        assertThat(completePo.deliveryOrderId).isEqualTo(completeOrderId)
        assertThat(completePo.batchZone).isNull()

        val batchPo = pickOrderService.getPickOrder(result.batchPickOrderIds.single())
        assertThat(batchPo.waveId).isEqualTo(waveId)
        assertThat(batchPo.deliveryOrderId).isNull()
        assertThat(batchPo.deliveryOrderNumber).isNull()
        assertThat(batchPo.batchZone).isEqualTo("UNZONED")
        val batchPicks = pickOrderService.picksOf(batchPo.id!!)
        assertThat(batchPicks).hasSize(1)
        assertThat(batchPicks.single().deliveryOrderLineId).isNotNull()
    }

    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `COMPLETE_ONLY mode drops PICK slices as leftovers and creates no batch order`() {
        seedPackStaging()
        val (completeOrderId, partialOrderId) = seedCompleteAndPartialOrders("CO")
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "COMPLETE_ONLY", listOf(completeOrderId, partialOrderId)),
        )

        assertThat(result.pickOrderIds).hasSize(1)
        assertThat(result.batchPickOrderIds).isEmpty()
        assertThat(result.droppedEachesLines).hasSize(1)
        assertThat(result.droppedEachesLines.single().amount).isEqualByComparingTo("40.0")

        val completePo = pickOrderService.getPickOrder(result.pickOrderIds.single())
        assertThat(completePo.deliveryOrderId).isEqualTo(completeOrderId)
    }

    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PICK_ONLY mode sends every slice through the batch path`() {
        seedPackStaging()
        val (completeOrderId, partialOrderId) = seedCompleteAndPartialOrders("PO")
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "PICK_ONLY", listOf(completeOrderId, partialOrderId)),
        )

        assertThat(result.pickOrderIds).isEmpty()
        assertThat(result.batchPickOrderIds).hasSize(1)
        assertThat(result.droppedEachesLines).isEmpty()

        val batchPo = pickOrderService.getPickOrder(result.batchPickOrderIds.single())
        assertThat(batchPo.deliveryOrderId).isNull()
        val batchPicks = pickOrderService.picksOf(batchPo.id!!)
        assertThat(batchPicks).hasSize(2)
    }

    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `waveStats reports live counts across per-order and batch PickOrders`() {
        seedPackStaging()
        val (completeOrderId, partialOrderId) = seedCompleteAndPartialOrders("STAT")
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        batchPickPort.generateForWave(WavePickRequest(waveId, 1L, "HYBRID", listOf(completeOrderId, partialOrderId)))

        val stats = batchPickPort.waveStats(waveId, 1L)
        assertThat(stats.totalPickOrders).isEqualTo(2)
        assertThat(stats.openPickOrders).isEqualTo(2)
        assertThat(stats.totalPicks).isEqualTo(2)
        assertThat(stats.pickedPicks).isEqualTo(0)
    }

    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `confirming a wave pick fires WavePickActivityEvent and waveAllTerminal only flips once every wave pick is terminal`() {
        seedPackStaging()
        val (completeOrderId, partialOrderId) = seedCompleteAndPartialOrders("EVT")
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "HYBRID", listOf(completeOrderId, partialOrderId)),
        )
        val completePickId = pickOrderService.picksOf(result.pickOrderIds.single()).single().id!!
        val batchPickId = pickOrderService.picksOf(result.batchPickOrderIds.single()).single().id!!

        pickOrderService.confirmPick(completePickId, BigDecimal("100.0"), null)

        assertThat(waveActivityObserver.events).hasSize(1)
        val firstEvent = waveActivityObserver.events.single()
        assertThat(firstEvent.waveId).isEqualTo(waveId)
        assertThat(firstEvent.pickOrderId).isEqualTo(result.pickOrderIds.single())
        assertThat(firstEvent.clientId).isEqualTo(1L)
        // The batch order's pick is still open -- the wave is not yet all-terminal.
        assertThat(firstEvent.waveAllTerminal).isFalse()

        pickOrderService.confirmPick(batchPickId, BigDecimal("40.0"), null)

        assertThat(waveActivityObserver.events).hasSize(2)
        assertThat(waveActivityObserver.events.last().waveAllTerminal).isTrue()

        // Same JPA staleness gotcha TypeOrderSplitTest's KDoc documents for direct (non-REST)
        // service calls sharing one persistence context: Pick 7/8 were loaded into the L1 cache
        // by picksOf() BEFORE either confirmPick call, so a query issued from OUTSIDE an active
        // transaction (like this standalone allTerminal call) needs an explicit clear() first, or
        // it can resolve those ids back to their pre-confirm managed state.
        entityManager.clear()
        assertThat(batchPickPort.allTerminal(waveId, 1L)).isTrue()
    }

    // Task 4 review fix (register: 2 Critical + 3 Important + 1 constraint). This test replaces
    // the original "completing a batch pick order never fires the auto-pack event" test: that
    // assumption was ITSELF the CRITICAL-1(b) bug (a PICK_ONLY/all-batch member order's own
    // completion was invisible to `PickOrderService.confirmPick`, so its auto-pack correctly
    // never fired -- for the WRONG reason, because the order could never be detected as complete
    // at all). Post-fix, a batch pick's auto-pack fires per MEMBER ORDER (via its line), the
    // instant THAT order's own lines are all done -- never for the shared batch CONTAINER as a
    // whole, and never prematurely for a sibling order whose lines still share that container.
    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a batch pick's auto-pack fires only for the member order it completes, not a sibling sharing the same batch container`() {
        seedPackStaging()
        val strategyId = createStrategy("WPS-AUTOPACK-${System.nanoTime()}", createShippingOrder = true)
        val orderB = seedReleasedOrder("APB", 100.0, 40.0, strategyId)
        val orderD = seedReleasedOrder("APD", 100.0, 40.0, strategyId)
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "HYBRID", listOf(orderB, orderD)),
        )
        assertThat(result.pickOrderIds).isEmpty()
        assertThat(result.batchPickOrderIds).hasSize(1)
        val batchPicks = pickOrderService.picksOf(result.batchPickOrderIds.single())
        assertThat(batchPicks).hasSize(2)
        val pickB = batchPicks.single { it.itemDataNumber.contains("APB") }
        val pickD = batchPicks.single { it.itemDataNumber.contains("APD") }

        pickOrderService.confirmPick(pickB.id!!, BigDecimal("40.0"), null)

        // Order B's only line is now fully picked -- ITS auto-pack fires. Order D's line is a
        // DIFFERENT order sharing the same batch PickOrder container and is still open, so it
        // must not fire yet.
        assertThat(autoPackObserver.events).hasSize(1)
        assertThat(autoPackObserver.events.single().deliveryOrderId).isEqualTo(orderB)

        pickOrderService.confirmPick(pickD.id!!, BigDecimal("40.0"), null)

        assertThat(autoPackObserver.events).hasSize(2)
        assertThat(autoPackObserver.events.map { it.deliveryOrderId }).containsExactlyInAnyOrder(orderB, orderD)
    }

    // Task 4 review fix, CRITICAL-1(a): before the fix, this order's COMPLETE PickOrder finishing
    // alone tripped `allSiblingsComplete` (PickOrderRepository.findAllByDeliveryOrderId, which
    // cannot see the cross-order batch PickOrder holding the SAME order's PICK-slice line) and
    // wrongly marked the order PICKED while its batch pick was still open.
    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a HYBRID member order owning both a COMPLETE slice and a batch PICK slice is not PICKED until the batch pick also confirms`() {
        seedPackStaging()
        val orderId = seedMixedOrder("MIX")
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "HYBRID", listOf(orderId)),
        )
        assertThat(result.pickOrderIds).hasSize(1)
        assertThat(result.batchPickOrderIds).hasSize(1)

        val completePickId = pickOrderService.picksOf(result.pickOrderIds.single()).single().id!!
        pickOrderService.confirmPick(completePickId, BigDecimal("100.0"), null)

        // The order's COMPLETE-slice PickOrder is done, but its batch-routed PICK slice is still
        // open -- the order itself must NOT have advanced to PICKED(600) yet.
        assertThat(orderState(orderId)).isNotEqualTo(600)

        val batchPickId = pickOrderService.picksOf(result.batchPickOrderIds.single()).single().id!!
        pickOrderService.confirmPick(batchPickId, BigDecimal("40.0"), null)

        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))
    }

    // Task 4 review fix, CRITICAL-1(b): before the fix, a member order whose ENTIRE demand routed
    // to the batch path (PICK_ONLY, or an all-PICK order under any mode) never had a per-order
    // PickOrder for `allSiblingsComplete` to key off at all -- it stayed STARTED forever, breaking
    // the downstream ship chain.
    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a PICK_ONLY member order reaches PICKED via the batch confirm alone`() {
        seedPackStaging()
        val orderId = seedReleasedOrder("PICKONLY", 100.0, 40.0)
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "PICK_ONLY", listOf(orderId)),
        )
        assertThat(result.pickOrderIds).isEmpty()
        assertThat(result.batchPickOrderIds).hasSize(1)
        assertThat(orderState(orderId)).isNotEqualTo(600)

        val pickId = pickOrderService.picksOf(result.batchPickOrderIds.single()).single().id!!
        pickOrderService.confirmPick(pickId, BigDecimal("40.0"), null)

        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))
    }

    // Task 4 review fix, CRITICAL-2: before the fix, a short confirm on a batch pick reached
    // `handleShortfall`'s `pickOrder.deliveryOrderId ?: error(...)` invariant (always null for a
    // batch PickOrder) and threw a 500. v1 batch short-confirm is deliberately release-only, the
    // same shape as an EXTINGUISH short confirm: pick what's there, release the unpicked
    // remainder's reservation, no follow-up re-selection or substitution, no exception.
    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a short confirm on a batch pick releases the remainder reservation and mints no follow-up picks`() {
        seedPackStaging()
        val seeded = seedReleasedPickLine("SHORT", 100.0, 40.0)
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "PICK_ONLY", listOf(seeded.orderId)),
        )
        val pick = pickOrderService.picksOf(result.batchPickOrderIds.single()).single()
        assertThat(reservedAmountOf(seeded.stockUnitId)).isEqualTo(40.0)

        // Short confirm: 25 of the planned 40 -- must NOT throw.
        val confirmed = pickOrderService.confirmPick(pick.id!!, BigDecimal("25.0"), null)
        assertThat(confirmed.pickedAmount).isEqualByComparingTo("25.0")

        // The unpicked remainder (15) was released, not left dangling or re-reserved elsewhere.
        assertThat(reservedAmountOf(seeded.stockUnitId)).isEqualTo(0.0)

        // No follow-up Pick was minted for the shortfall (v1 batch short-confirm is release-only).
        val allPicksOfOrder = pickOrderService.picksOf(result.batchPickOrderIds.single())
        assertThat(allPicksOfOrder).hasSize(1)
        assertThat(allPicksOfOrder.single().followUpForPickId).isNull()
    }

    // Task 4 review fix, IMPORTANT-3: a caller-stated `WavePickRequest.clientId` that does not
    // match the order's real owner must throw, never silently skip (which could mask a wrong-
    // tenant call as "wave had nothing to generate") nor silently stamp PickOrders under the
    // wrong client.
    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `generateForWave throws on a clientId mismatch and creates no PickOrders`() {
        seedPackStaging()
        val orderId = seedReleasedOrder("MISMATCH", 100.0, 100.0)
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        assertThatThrownBy {
            batchPickPort.generateForWave(WavePickRequest(waveId, 999L, "HYBRID", listOf(orderId)))
        }.isInstanceOf(FulfillmentException.ValidationFailed::class.java)

        assertThat(batchPickPort.waveStats(waveId, 1L).totalPickOrders).isEqualTo(0)
    }

    // Task 4 review fix, IMPORTANT-4: a wave COMPLETE PickOrder must resolve the same
    // `order.destinationLocationId ?: strategy.defaultDestinationLocationId` fallback the
    // discrete `releaseToPicking` path already applies, not just the order's own (possibly unset)
    // destination.
    @Test
    @TestSecurity(
        user = "wps",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a wave COMPLETE PickOrder falls back to the strategy default destination when the order has none of its own`() {
        seedPackStaging()
        val destinationId = createDestinationLocation()
        val strategyId = given().contentType(ContentType.JSON)
            .body("""{"name":"WPS-DEST-${System.nanoTime()}","defaultDestinationLocationId":$destinationId}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        val orderId = seedReleasedOrder("DEST", 100.0, 100.0, strategyId)
        tenantContext.clientId = 1L
        val waveId = System.nanoTime()

        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, 1L, "HYBRID", listOf(orderId)),
        )

        val po = pickOrderService.getPickOrder(result.pickOrderIds.single())
        assertThat(po.destinationLocationId).isEqualTo(destinationId)
    }
}
