package com.karyo.orders.streaming

import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.spi.OrderReleasePort
import com.karyo.orders.spi.StreamBucket
import com.karyo.orders.spi.StreamReleaseResult
import com.karyo.orders.spi.StreamScope
import com.karyo.orders.spi.StreamingReleasePort
import com.karyo.orders.vo.OrderState
import com.karyo.orders.vo.ReleaseMode
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Task 2 (order streaming, orders FOSS side): [StreamingReleasePort] / its
 * [com.karyo.orders.messaging.DefaultStreamingReleasePort] impl -- the whole orchestration seam
 * the paid streaming scheduler drives. Covers the effective-mode candidate predicate (STREAM
 * strategy vs. per-order override, wave and stall exclusions, prio DESC / created ASC ordering),
 * the two release hops with their state re-check, the three escalation stamps and their
 * idempotence, the retry-endpoint stall reset, the bucket lists and counts, and the unscoped
 * tenant-loop query.
 *
 * Fixture helpers lifted from `StreamingWaveExclusionIT` (itself lifted from `WaveSchedulerIT`),
 * extended with a `prio` on order creation and a second stock unit for the retry case.
 *
 * Every test uses a distinct client_id so parallel/repeated runs never collide.
 */
@QuarkusTest
class StreamingReleasePortIT {

    @Inject
    lateinit var port: StreamingReleasePort

    /**
     * Needed for the wave-exclusion leg of the candidate matrix: `waveId` has no REST writer, and
     * `assignToWave` is exactly how the wave module stamps it. (The brief said this injection was
     * not needed; the wave-exclusion assertion it also asks for cannot be written without it.)
     */
    @Inject
    lateinit var orderReleasePort: OrderReleasePort

    @Inject
    lateinit var orderRepository: DeliveryOrderRepository

    /** Task 5 (D9 (d)): the ambient-vs-explicit-clientId two-sided regression guard. */
    @Inject
    lateinit var tenantContext: TenantContext

    /**
     * D5 (defect-burndown-7): a pick-push failure after a successful reserve leaves the order
     * PROCESSABLE + stream-stamped, with no `streamStalledAt`. No REST route can drive an order
     * into that exact shape (the push hop is scheduler-only), so this persists the entity directly
     * -- the `StreamingResourceTest.persistStalledOrder` shape.
     */
    @Transactional
    fun persistPushFailedOrder(clientId: Long, orderNumber: String, strategyId: Long?): Long {
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            this.state = OrderState.PROCESSABLE.code
            this.prio = 50
            this.customerName = "c"
            this.zipCode = "1"
            this.city = "x"
            this.orderStrategyId = strategyId
            this.streamFirstAttemptAt = Instant.now()
        }
        orderRepository.persist(order)
        return order.id!!
    }

    /**
     * D8: a RELEASED, stamped, un-stalled order with [pendingLineCount] `PENDING` lines directly
     * persisted (bypassing the reservation path -- no REST route parks an order mid-shortfall
     * with a chosen line count). `releaseModeOverride = "STREAM"` makes it retryable under a
     * non-stream scope, per Task 2's mode-narrowed `retryableWhere`.
     */
    @Transactional
    fun persistRetryableOrder(clientId: Long, orderNumber: String, strategyId: Long, pendingLineCount: Int): Long {
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            this.state = OrderState.RELEASED.code
            this.prio = 50
            this.customerName = "c"
            this.zipCode = "1"
            this.city = "x"
            this.orderStrategyId = strategyId
            this.releaseModeOverride = "STREAM"
            this.streamFirstAttemptAt = Instant.now()
        }
        repeat(pendingLineCount) { i ->
            val line = DeliveryOrderLine().apply {
                this.deliveryOrder = order
                this.lineNumber = i
                this.itemDataId = 1L
                this.itemDataNumber = "SKU-$orderNumber-$i"
                this.amount = java.math.BigDecimal.TEN
                this.state = OrderState.PENDING.code
            }
            order.lines.add(line)
        }
        orderRepository.persist(order)
        return order.id!!
    }

    /**
     * D9 (b) (defect-burndown-7): a bare RELEASED + stamped order with NO strategy and NO lines --
     * `clientIdsWithStreamingWork`'s RELEASED arm (`clientIdsSql`) needs nothing but
     * `state = RELEASED`, `stream_first_attempt_at IS NOT NULL`, `wave_id IS NULL` and
     * `stream_stalled_at IS NULL`, so this is deliberately the minimal shape that arm alone
     * surfaces -- no candidate-side fields (mode/strategy) come into play.
     */
    @Transactional
    fun persistBareReleasedOrder(clientId: Long, orderNumber: String): Long {
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            this.state = OrderState.RELEASED.code
            this.prio = 50
            this.customerName = "c"
            this.zipCode = "1"
            this.city = "x"
            this.orderStrategyId = null
            this.streamFirstAttemptAt = Instant.now()
        }
        orderRepository.persist(order)
        return order.id!!
    }

    /**
     * D9 (d) (defect-burndown-7): a plain CREATED candidate order for [clientId] on [strategyId],
     * persisted directly (not through REST, which `@OidcSecurity` pins to one client per test) so
     * the ambient-vs-explicit-clientId regression test can seed a second tenant's row.
     */
    @Transactional
    fun persistCandidateOrder(clientId: Long, orderNumber: String, strategyId: Long): Long {
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            this.state = OrderState.CREATED.code
            this.prio = 50
            this.customerName = "c"
            this.zipCode = "1"
            this.city = "x"
            this.orderStrategyId = strategyId
        }
        orderRepository.persist(order)
        return order.id!!
    }

    /**
     * D-final-1 (defect-burndown-7 whole-branch review): a CREATED order stamped + un-stalled --
     * the shape the Retry endpoint leaves behind when it un-stalls a hard-release-failure order
     * that never left CREATED. No REST route produces this directly (release always attempts the
     * CREATED -> RELEASED transition), so it is persisted directly.
     */
    @Transactional
    fun persistRetriedCreatedOrphan(clientId: Long, orderNumber: String, strategyId: Long?): Long {
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = orderNumber
            this.state = OrderState.CREATED.code
            this.prio = 50
            this.customerName = "c"
            this.zipCode = "1"
            this.city = "x"
            this.orderStrategyId = strategyId
            this.streamFirstAttemptAt = Instant.now()
        }
        orderRepository.persist(order)
        return order.id!!
    }

    // -- Fixture helpers (lifted from StreamingWaveExclusionIT) --------------

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
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Seeds a product with [stockAmount] of stock on its own unit load. Returns the product/item id. */
    private fun seedProductWithStock(tag: String, stockAmount: Double): Pair<Long, String> {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "SRP-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("SRP-$tag-UL-$s")
        createStock(ul, pid, num, stockAmount)
        return pid to num
    }

    /** A second stock unit on a fresh unit load, so a short line can be topped up mid-test. */
    private fun addStock(tag: String, itemDataId: Long, itemDataNumber: String, amount: Double) {
        val ul = createUnitLoad("SRP-$tag-UL2-${System.nanoTime()}")
        createStock(ul, itemDataId, itemDataNumber, amount)
    }

    private fun createStrategy(tag: String, extensionPropertiesJson: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"SRP-$tag-${System.nanoTime()}","extensionProperties":$extensionPropertiesJson}""")
            .`when`().post("/api/v1/order-strategies").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createOrder(
        itemDataId: Long,
        amount: Double,
        strategyId: Long,
        releaseModeOverride: String? = null,
        prio: Int = 50,
    ): Long {
        val overrideJson = releaseModeOverride?.let { ""","releaseModeOverride":"$it"""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Cust","zipCode":"1","city":"City","orderStrategyId":$strategyId,""" +
                    """"prio":$prio$overrideJson,"lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** No `orderStrategyId` field at all -- the order inherits the DEFAULT strategy (null column). */
    private fun createOrderNoStrategy(itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Cust","zipCode":"1","city":"City",""" +
                    """"lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    // -- Tests ---------------------------------------------------------------

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9511")])
    fun `candidate predicate -- effective STREAM only, un-waved, prio DESC then created ASC`() {
        val clientId = 9511L
        val streamStrategy = createStrategy("A-STREAM", """{"releaseMode":"STREAM"}""")
        val manualStrategy = createStrategy("A-MANUAL", """{"releaseMode":"MANUAL"}""")
        val (item, _) = seedProductWithStock("A", 1000.0)

        val inherited = createOrder(item, 1.0, streamStrategy)
        val highPrio = createOrder(item, 1.0, streamStrategy, prio = 90)
        val overriddenManual = createOrder(item, 1.0, streamStrategy, releaseModeOverride = "MANUAL")
        val overriddenStream = createOrder(item, 1.0, manualStrategy, releaseModeOverride = "STREAM")

        val streamScope = StreamScope(streamStrategy, isDefault = false, strategyIsStream = true)
        val manualScope = StreamScope(manualStrategy, isDefault = false, strategyIsStream = false)

        assertThat(port.findStreamCandidates(streamScope, clientId, 10).map { it.orderId })
            .containsExactly(highPrio, inherited)
        assertThat(port.findStreamCandidates(streamScope, clientId, 10).map { it.orderId })
            .doesNotContain(overriddenManual)
        assertThat(port.findStreamCandidates(manualScope, clientId, 10).map { it.orderId })
            .containsExactly(overriddenStream)

        val summary = port.streamCandidateSummary(streamScope, clientId)
        assertThat(summary.count).isEqualTo(2)
        assertThat(summary.oldestCreated).isEqualTo(port.findStreamOrder(inherited, clientId)!!.created)
        assertThat(port.streamOrderCounts(streamScope, clientId).eligible).isEqualTo(2)

        // A waved order is never a streaming candidate (spec ruling 5, the mirror side).
        orderReleasePort.assignToWave(listOf(inherited, highPrio), 4242L, clientId)
        assertThat(port.findStreamCandidates(streamScope, clientId, 10)).isEmpty()
        assertThat(port.streamCandidateSummary(streamScope, clientId).count).isZero()
        assertThat(port.streamCandidateSummary(streamScope, clientId).oldestCreated).isNull()
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9512")])
    fun `releaseForStreaming fully reserves a covered order, then skips it on the second pass`() {
        val clientId = 9512L
        val strategyId = createStrategy("B-STREAM", """{"releaseMode":"STREAM"}""")
        val (item, _) = seedProductWithStock("B", 100.0)
        val orderId = createOrder(item, 30.0, strategyId)

        val first = port.releaseForStreaming(orderId, clientId)
        assertThat(first.result).isEqualTo(StreamReleaseResult.PROCESSABLE)
        assertThat(first.pendingLineCount).isZero()

        val second = port.releaseForStreaming(orderId, clientId)
        assertThat(second.result).isEqualTo(StreamReleaseResult.SKIPPED)
        assertThat(second.pendingLineCount).isZero()
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9513")])
    fun `a short order stays retryable until stock arrives, then retryForStreaming clears it`() {
        val clientId = 9513L
        val strategyId = createStrategy("C-STREAM", """{"releaseMode":"STREAM"}""")
        val (item, itemNumber) = seedProductWithStock("C", 10.0)
        val orderId = createOrder(item, 30.0, strategyId)
        val scope = StreamScope(strategyId, isDefault = false, strategyIsStream = true)

        val released = port.releaseForStreaming(orderId, clientId)
        assertThat(released.result).isEqualTo(StreamReleaseResult.RELEASED_SHORT)
        assertThat(released.pendingLineCount).isEqualTo(1)

        port.markStreamAttempt(orderId, clientId, now())
        assertThat(port.findStreamRetryable(scope, clientId, 10).map { it.orderId }).containsExactly(orderId)
        val counts = port.streamOrderCounts(scope, clientId)
        assertThat(counts.eligible).isZero()
        assertThat(counts.waiting).isEqualTo(1)
        assertThat(counts.escalated).isZero()
        assertThat(counts.stalled).isZero()
        assertThat(port.findStreamOrders(clientId, StreamBucket.WAITING, 10).map { it.orderId }).containsExactly(orderId)

        port.markStreamEscalated(orderId, clientId, now())
        assertThat(port.streamOrderCounts(scope, clientId).waiting).isZero()
        assertThat(port.streamOrderCounts(scope, clientId).escalated).isEqualTo(1)
        assertThat(port.findStreamOrders(clientId, StreamBucket.ESCALATED, 10).map { it.orderId }).containsExactly(orderId)
        // Still retryable while escalated (tier 2 keeps retrying, spec ruling 8).
        assertThat(port.findStreamRetryable(scope, clientId, 10).map { it.orderId }).containsExactly(orderId)

        addStock("C", item, itemNumber, 30.0)
        val retried = port.retryForStreaming(orderId, clientId)
        assertThat(retried.result).isEqualTo(StreamReleaseResult.PROCESSABLE)
        assertThat(retried.pendingLineCount).isZero()

        assertThat(port.findStreamRetryable(scope, clientId, 10)).isEmpty()
        assertThat(port.retryForStreaming(orderId, clientId).result).isEqualTo(StreamReleaseResult.SKIPPED)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9514")])
    fun `the three stamps are write-once, a stalled order leaves the pool, and resetStreamStall un-stalls it`() {
        val clientId = 9514L
        val strategyId = createStrategy("D-STREAM", """{"releaseMode":"STREAM"}""")
        val (item, _) = seedProductWithStock("D", 100.0)
        val orderId = createOrder(item, 10.0, strategyId)
        val scope = StreamScope(strategyId, isDefault = false, strategyIsStream = true)

        val firstAttempt = now()
        port.markStreamAttempt(orderId, clientId, firstAttempt)
        port.markStreamAttempt(orderId, clientId, firstAttempt.plusSeconds(60))
        assertThat(port.findStreamOrder(orderId, clientId)!!.firstAttemptAt).isEqualTo(firstAttempt)

        val escalated = firstAttempt.plusSeconds(30)
        port.markStreamEscalated(orderId, clientId, escalated)
        port.markStreamEscalated(orderId, clientId, escalated.plusSeconds(60))
        assertThat(port.findStreamOrder(orderId, clientId)!!.escalatedAt).isEqualTo(escalated)

        val stalled = firstAttempt.plusSeconds(1800)
        port.markStreamStalled(orderId, clientId, stalled)
        port.markStreamStalled(orderId, clientId, stalled.plusSeconds(60))
        assertThat(port.findStreamOrder(orderId, clientId)!!.stalledAt).isEqualTo(stalled)

        assertThat(port.findStreamOrders(clientId, StreamBucket.STALLED, 10).map { it.orderId }).containsExactly(orderId)
        assertThat(port.streamOrderCounts(scope, clientId).stalled).isEqualTo(1)
        // A stalled order is no longer a candidate, even though it is still CREATED.
        assertThat(port.findStreamCandidates(scope, clientId, 10)).isEmpty()

        val resetAt = now()
        val reset = port.resetStreamStall(orderId, clientId, resetAt)
        assertThat(reset.stalledAt).isNull()
        assertThat(reset.escalatedAt).isNull()
        assertThat(reset.firstAttemptAt).isEqualTo(resetAt)
        assertThat(port.findStreamCandidates(scope, clientId, 10).map { it.orderId }).containsExactly(orderId)

        assertThatThrownBy { port.resetStreamStall(orderId, clientId, now()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { port.resetStreamStall(orderId, 9999L, now()) }
            .isInstanceOf(OrderException.NotFound::class.java)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9515")])
    fun `clientIdsWithStreamingWork sees a strategy-inherited candidate only when the strategy is named`() {
        val clientId = 9515L
        val streamStrategy = createStrategy("E-STREAM", """{"releaseMode":"STREAM"}""")
        val manualStrategy = createStrategy("E-MANUAL", """{"releaseMode":"MANUAL"}""")
        val (item, _) = seedProductWithStock("E", 100.0)
        createOrder(item, 1.0, streamStrategy)

        assertThat(port.clientIdsWithStreamingWork(setOf(streamStrategy), defaultIsStream = false)).contains(clientId)
        assertThat(port.clientIdsWithStreamingWork(emptySet(), defaultIsStream = false)).doesNotContain(clientId)

        createOrder(item, 1.0, manualStrategy, releaseModeOverride = "STREAM")
        assertThat(port.clientIdsWithStreamingWork(emptySet(), defaultIsStream = false)).contains(clientId)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9516")])
    fun `strategiesWithStreamingConfig parses and clamps the streaming knobs`() {
        val strategyId = createStrategy(
            "F-STREAM",
            """{"releaseMode":"STREAM","streamBatchSize":5000,"streamMaxWaitSeconds":30,"streamAbandonSeconds":5}""",
        )

        val view = port.strategiesWithStreamingConfig().single { it.strategyId == strategyId }
        assertThat(view.isDefault).isFalse()
        assertThat(view.name).startsWith("SRP-F-STREAM-")
        assertThat(view.config.releaseMode).isEqualTo(ReleaseMode.STREAM)
        assertThat(view.config.streamBatchSize).isEqualTo(1000)
        assertThat(view.config.streamMaxWaitSeconds).isEqualTo(30)
        assertThat(view.config.streamAbandonSeconds).isEqualTo(30)
        assertThat(view.config.streamTimingStrategy).isEqualTo("time-size")

        // The DEFAULT strategy is still reported, and is not STREAM.
        val defaultView = port.strategiesWithStreamingConfig().single { it.isDefault }
        assertThat(defaultView.name).isEqualTo("DEFAULT")
        assertThat(defaultView.config.releaseMode).isEqualTo(ReleaseMode.MANUAL)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9601")])
    fun `a stamped order on a mode-switched strategy is an orphan and no longer retryable`() {
        val clientId = 9601L
        val strategyId = createStrategy("G-STREAM", """{"releaseMode":"STREAM"}""")
        val (item, _) = seedProductWithStock("G", 10.0)
        val orderId = createOrder(item, 30.0, strategyId)

        val released = port.releaseForStreaming(orderId, clientId)
        assertThat(released.result).isEqualTo(StreamReleaseResult.RELEASED_SHORT)
        port.markStreamAttempt(orderId, clientId, now())

        // The strategy has switched off STREAM in the current scan (no per-order override for O).
        val switchedScope = StreamScope(strategyId, isDefault = false, strategyIsStream = false)

        assertThat(port.findStreamRetryable(switchedScope, clientId, 10)).isEmpty()
        assertThat(port.findStreamOrphans(listOf(switchedScope), clientId, 10).map { it.orderId })
            .containsExactly(orderId)

        // A second short order WITH a per-order STREAM override on the same (now non-STREAM)
        // strategy is unaffected by the mode switch: still retryable, never an orphan.
        val overrideOrderId = createOrder(item, 30.0, strategyId, releaseModeOverride = "STREAM")
        val overrideReleased = port.releaseForStreaming(overrideOrderId, clientId)
        assertThat(overrideReleased.result).isEqualTo(StreamReleaseResult.RELEASED_SHORT)
        port.markStreamAttempt(overrideOrderId, clientId, now())

        assertThat(port.findStreamRetryable(switchedScope, clientId, 10).map { it.orderId })
            .containsExactly(overrideOrderId)
        assertThat(port.findStreamOrphans(listOf(switchedScope), clientId, 10).map { it.orderId })
            .containsExactly(orderId)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9602")])
    fun `a stamped order whose strategy is absent from the current scopes is an orphan`() {
        val clientId = 9602L
        val strategyId = createStrategy("H-STREAM", """{"releaseMode":"STREAM"}""")
        val (item, _) = seedProductWithStock("H", 10.0)
        val orderId = createOrder(item, 30.0, strategyId)

        val released = port.releaseForStreaming(orderId, clientId)
        assertThat(released.result).isEqualTo(StreamReleaseResult.RELEASED_SHORT)
        port.markStreamAttempt(orderId, clientId, now())

        // Simulates the strategy row deleted from the scheduler's view.
        assertThat(port.findStreamOrphans(emptyList(), clientId, 10).map { it.orderId }).containsExactly(orderId)

        // Documents the wake-loop the sweep ends (D9(b) coverage).
        assertThat(port.clientIdsWithStreamingWork(emptySet(), defaultIsStream = false)).contains(clientId)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9608")])
    fun `a stamped null-orderStrategyId order is an orphan when the DEFAULT strategy is absent from the scan`() {
        val clientId = 9608L
        // A concrete, non-default STREAM strategy that IS in the current scan -- exercises the
        // `IN (ids)` branch of strategySetClause, not just its empty-list `(1 = 0)` shape.
        val otherStrategyId = createStrategy("I-STREAM", """{"releaseMode":"STREAM"}""")
        val (item, _) = seedProductWithStock("I", 10.0)
        val orderId = createOrderNoStrategy(item, 30.0)

        val released = port.releaseForStreaming(orderId, clientId)
        assertThat(released.result).isEqualTo(StreamReleaseResult.RELEASED_SHORT)
        port.markStreamAttempt(orderId, clientId, now())

        // The DEFAULT strategy is absent from currentScopes -- simulates it deleted or renamed.
        // Before the strategySetClause fix, `NULL in (otherStrategyId)` (SQL NULL, not FALSE) for
        // this null-orderStrategyId order poisoned `not (effectiveStream and existsClause)` to
        // NULL, which WHERE silently drops -- the row never came back as an orphan.
        val defaultAbsentScope = StreamScope(otherStrategyId, isDefault = false, strategyIsStream = true)
        assertThat(port.findStreamOrphans(listOf(defaultAbsentScope), clientId, 10).map { it.orderId })
            .containsExactly(orderId)

        // Inverse: the DEFAULT strategy IS present (and streaming) in the scan -- the null-strategy
        // order inherits it and is not an orphan. This does not touch the real DEFAULT strategy
        // row; findStreamOrphans takes scopes as plain parameters.
        val defaultId = port.strategiesWithStreamingConfig().single { it.isDefault }.strategyId
        val defaultPresentScope = StreamScope(defaultId, isDefault = true, strategyIsStream = true)
        assertThat(port.findStreamOrphans(listOf(defaultPresentScope), clientId, 10).map { it.orderId })
            .doesNotContain(orderId)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9581")])
    fun `PUSH_FAILED bucket surfaces a reserved-but-not-pushed order and excludes it from WAITING-ESCALATED-STALLED`() {
        val clientId = 9581L
        val strategyId = createStrategy("J-STREAM", """{"releaseMode":"STREAM"}""")
        val scope = StreamScope(strategyId, isDefault = false, strategyIsStream = true)
        val orderId = persistPushFailedOrder(clientId, "SRP-J-${System.nanoTime()}", strategyId)

        val counts = port.streamOrderCounts(scope, clientId)
        assertThat(counts.pushFailed).isEqualTo(1L)
        assertThat(counts.waiting).isZero()
        assertThat(counts.escalated).isZero()
        assertThat(counts.stalled).isZero()

        assertThat(port.findStreamOrders(clientId, StreamBucket.PUSH_FAILED, 10).map { it.orderId })
            .containsExactly(orderId)
        assertThat(port.findStreamOrders(clientId, StreamBucket.WAITING, 10)).isEmpty()
        assertThat(port.findStreamOrders(clientId, StreamBucket.ESCALATED, 10)).isEmpty()
        assertThat(port.findStreamOrders(clientId, StreamBucket.STALLED, 10)).isEmpty()
    }

    /**
     * D8 (defect-burndown-7): locks `pendingLineCount` for multiple orders with different line
     * counts on the same result page -- the regression guard for switching the list paths from a
     * per-row lazy `lines` select to one grouped count query. Passes against the lazy impl too;
     * it must stay green across the batching rewrite.
     */
    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9583")])
    fun `list paths carry the right per-order pendingLineCount for multiple orders on one page`() {
        val clientId = 9583L
        val strategyId = createStrategy("K-STREAM", """{"releaseMode":"MANUAL"}""")
        // strategyIsStream = false: retryability here comes entirely from the per-order
        // releaseModeOverride = "STREAM" stamp (Task 2's mode-narrowed retryableWhere).
        val scope = StreamScope(strategyId, isDefault = false, strategyIsStream = false)

        val orderA = persistRetryableOrder(clientId, "SRP-K-A-${System.nanoTime()}", strategyId, pendingLineCount = 2)
        val orderB = persistRetryableOrder(clientId, "SRP-K-B-${System.nanoTime()}", strategyId, pendingLineCount = 1)

        val retryable = port.findStreamRetryable(scope, clientId, 10).associateBy { it.orderId }
        assertThat(retryable.keys).containsExactlyInAnyOrder(orderA, orderB)
        assertThat(retryable[orderA]!!.pendingLineCount).isEqualTo(2)
        assertThat(retryable[orderB]!!.pendingLineCount).isEqualTo(1)

        val waiting = port.findStreamOrders(clientId, StreamBucket.WAITING, 10).associateBy { it.orderId }
        assertThat(waiting.keys).containsExactlyInAnyOrder(orderA, orderB)
        assertThat(waiting[orderA]!!.pendingLineCount).isEqualTo(2)
        assertThat(waiting[orderB]!!.pendingLineCount).isEqualTo(1)
    }

    /**
     * D9 (a) (defect-burndown-7): the DEFAULT strategy's own `extensionProperties` is a single
     * shared row (`OrderStrategy` is a `BaseEntity`, not tenant-scoped) -- setting it to STREAM
     * makes every concurrently-seeded null-`orderStrategyId` order across every client inherit
     * STREAM for the duration, so the original value is restored in a `finally` even if an
     * assertion fails. Locks `strategyClause`'s `isDefault` arm on [StreamingReleasePort] (a
     * null-`orderStrategyId` order IS a candidate under the default-STREAM scope) against its
     * exact mirror in `appendStreamExclusion`'s `defaultIsStream` branch on [OrderReleasePort]
     * (the same order is never wave-eligible).
     */
    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9603")])
    fun `DEFAULT strategy switched to STREAM makes a null-orderStrategyId order a candidate but never wave-eligible`() {
        val clientId = 9603L
        val defaultId = port.strategiesWithStreamingConfig().single { it.isDefault }.strategyId
        val originalExtensionProperties = given().`when`().get("/api/v1/order-strategies/$defaultId")
            .then().statusCode(200).extract().jsonPath().getMap<String, Any>("extensionProperties")
        try {
            given().contentType(ContentType.JSON)
                .body("""{"extensionProperties":{"releaseMode":"STREAM"}}""")
                .`when`().put("/api/v1/order-strategies/$defaultId").then().statusCode(200)

            val (item, _) = seedProductWithStock("L", 100.0)
            val orderId = createOrderNoStrategy(item, 1.0)

            val streamScope = StreamScope(defaultId, isDefault = true, strategyIsStream = true)
            assertThat(port.findStreamCandidates(streamScope, clientId, 10).map { it.orderId })
                .containsExactly(orderId)

            assertThat(orderReleasePort.findWaveEligible(null, clientId, 10)).isEmpty()
        } finally {
            given().contentType(ContentType.JSON)
                .body(mapOf("extensionProperties" to originalExtensionProperties))
                .`when`().put("/api/v1/order-strategies/$defaultId").then().statusCode(200)
        }
    }

    /**
     * D9 (b) (defect-burndown-7): [StreamingReleasePort.clientIdsWithStreamingWork]'s RELEASED
     * arm (`clientIdsSql`) has no mode/strategy predicate at all -- a bare stamped RELEASED order
     * surfaces its client even when the caller passes an empty stream-strategy set, proving the
     * RELEASED branch stands on its own and is not accidentally gated behind the CREATED branch's
     * strategy/override check.
     */
    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9604")])
    fun `clientIdsWithStreamingWork surfaces a bare RELEASED stamped order via the RELEASED arm alone`() {
        val clientId = 9604L
        persistBareReleasedOrder(clientId, "SRP-M-${System.nanoTime()}")

        assertThat(port.clientIdsWithStreamingWork(emptySet(), defaultIsStream = false)).contains(clientId)
    }

    /**
     * D9 (d) (defect-burndown-7): the regression shape for an accidental ambient read anywhere in
     * [StreamingReleasePort] -- every method takes an explicit `clientId` and must ignore the
     * ambient `TenantContext` entirely, even when it is primed to a DIFFERENT, real client for the
     * whole call. Client A's order must never leak into client B's read, and a write scoped to B
     * must never land on A.
     */
    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9606")])
    fun `findStreamCandidates and markStreamAttempt honor the explicit clientId, never the ambient one`() {
        val clientA = 9606L
        val clientB = 9607L
        val strategyId = createStrategy("N-STREAM", """{"releaseMode":"STREAM"}""")
        val scope = StreamScope(strategyId, isDefault = false, strategyIsStream = true)
        val (item, _) = seedProductWithStock("N", 100.0)

        // A's candidate, seeded normally via REST (@OidcSecurity pins the ambient client to A).
        val orderA = createOrder(item, 1.0, strategyId)

        // Ambient primed to A (the SchedulerIT two-sided-tenancy precedent, :637-675): B's row
        // cannot be REST-seeded under B in this test, so it is persisted directly instead.
        tenantContext.clientId = clientA
        val orderB = persistCandidateOrder(clientB, "SRP-N-B-${System.nanoTime()}", strategyId)

        // Ambient STILL primed to A for every call below.
        val forB = port.findStreamCandidates(scope, clientB, 10)
        assertThat(forB.map { it.orderId }).containsExactly(orderB)

        val stampTime = now()
        port.markStreamAttempt(orderB, clientB, stampTime)

        assertThat(port.findStreamOrder(orderB, clientB)!!.firstAttemptAt).isEqualTo(stampTime)
        assertThat(port.findStreamOrder(orderA, clientA)!!.firstAttemptAt).isNull()
    }

    /**
     * D-final-1 (defect-burndown-7 whole-branch review): before this fix, `orphanWhere`'s state
     * clause matched only `state = :released`, so a stamped, un-stalled CREATED order whose
     * strategy is no longer effectively STREAM matched no query at all: `retryableWhere` only ever
     * looks at RELEASED, and the orphan sweep itself was RELEASED-only, so the order silently
     * stopped being swept by anything. Locks the `state in (:created, :released)` fix: the same
     * stamped-but-off-STREAM CREATED order now surfaces as an orphan.
     */
    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9609")])
    fun `a retried CREATED orphan is swept again instead of escaping every query`() {
        val clientId = 9609L
        val strategyId = createStrategy("O-MANUAL", """{"releaseMode":"MANUAL"}""")
        val orderId = persistRetriedCreatedOrphan(clientId, "SRP-O-${System.nanoTime()}", strategyId)

        // The strategy is no longer STREAM (or absent) in the current scan, and the order carries
        // no per-order override -- effectively not-STREAM, exactly the "retried CREATED" shape.
        val notStreamScope = StreamScope(strategyId, isDefault = false, strategyIsStream = false)

        assertThat(port.findStreamRetryable(notStreamScope, clientId, 10)).isEmpty()
        assertThat(port.findStreamOrphans(listOf(notStreamScope), clientId, 10).map { it.orderId })
            .containsExactly(orderId)
    }
}
