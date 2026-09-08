package com.karyo.demo.gen

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.service.Rng
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.vo.OrderState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Task 6 (part 2): the deliberate monitor conditions [CatalogGenerator]/[InventoryGenerator]/
 * [HistoryGenerator]/[CycleCountGenerator] don't otherwise produce:
 *
 *  - **stuck-order**: [STUCK_ORDER_COUNT] `DeliveryOrder`s left in a non-terminal state
 *    (`PROCESSABLE`=300, comfortably inside the `state > 0 AND state < 700` band
 *    [com.karyo.monitors.detector.StuckOrderReader] reads) with `modified` backdated well past its
 *    24h staleness window. Deliberately distinct from [HistoryGenerator]'s orders, which are all
 *    terminal (`FINISHED`) so they never trip this monitor.
 *  - **shrinkage**: a few `InventoryJournal` rows with `record_type = CHANGED` and a NEGATIVE
 *    `amount` on 1-2 SKUs, `created` backdated within
 *    [com.karyo.monitors.detector.ShrinkageDetector]'s 24h lookback window. Per
 *    [com.karyo.monitors.detector.ShrinkageReader]'s KDoc, `amount` is a *signed delta* (not a
 *    magnitude) and `SUM(amount)` per SKU over the window must be negative — a single negative
 *    row per SKU already satisfies that.
 *
 * Returns the monitor keys these seeds are intended to trip, e.g. `["stuck-order", "shrinkage"]`
 * — used by the seed summary/report, not by the monitors engine itself (which reads the tables
 * independently and doesn't care who wrote them).
 *
 * Not idempotent by design (like the other generators): meant to run once per demo seed;
 * re-seeding is expected to `reset` first (Task 7).
 */
@ApplicationScoped
class MonitorSeedGenerator(
    private val deliveryOrderRepository: DeliveryOrderRepository,
    private val inventoryJournalRepository: InventoryJournalRepository,
    private val config: DemoConfig,
) {
    // Deterministic per generator instance (seeded from config.seed).
    private val rng = Rng(config.seed)

    private var orderSeq = 0

    @Transactional
    fun generate(clientId: Long, catalog: CatalogRefs, inventory: InventoryRefs): List<String> {
        seedStuckOrders(clientId, inventory)
        seedShrinkage(clientId, catalog)
        return listOf(STUCK_ORDER_KEY, SHRINKAGE_KEY)
    }

    // --- stuck order ----------------------------------------------------------

    private fun seedStuckOrders(clientId: Long, inventory: InventoryRefs) {
        val orders = (1..STUCK_ORDER_COUNT).map { buildStuckOrder(clientId, inventory) }
        deliveryOrderRepository.persist(orders) // cascades DeliveryOrderLine (ALL)
    }

    private fun buildStuckOrder(clientId: Long, inventory: InventoryRefs): DeliveryOrder {
        val stockRef = rng.pick(inventory.stock)
        val staleSince = Instant.now().minus(Duration.ofHours(STUCK_ORDER_HOURS_AGO))
        orderSeq++
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = "DEMO-STUCK-%03d".format(orderSeq)
            this.customerName = rng.pick(CUSTOMER_NAMES)
            this.state = OrderState.PROCESSABLE.code
            this.created = staleSince
            this.modified = staleSince
            this.started = staleSince
            // finished stays null — this order never completed, which is the point.
        }
        order.lines.add(
            DeliveryOrderLine().apply {
                this.deliveryOrder = order
                this.lineNumber = 1
                this.itemDataId = stockRef.itemDataId
                this.itemDataNumber = stockRef.number
                this.amount = randomAmount()
                this.reservedAmount = BigDecimal.ZERO
                this.state = OrderState.PROCESSABLE.code
                // Deterministic plausible per-SKU price -- same formula as HistoryGenerator.
                this.unitPrice = BigDecimal.valueOf(5L + (stockRef.itemDataId % 90)).add(BigDecimal("0.99"))
            },
        )
        return order
    }

    private fun randomAmount(): BigDecimal = BigDecimal(MIN_LINE_AMOUNT + rng.nextInt(LINE_AMOUNT_RANGE))

    // --- shrinkage --------------------------------------------------------------

    private fun seedShrinkage(clientId: Long, catalog: CatalogRefs) {
        val skus = catalog.skus.take(SHRINKAGE_SKU_COUNT)
        val journals = skus.flatMap { sku -> (1..SHRINKAGE_ROWS_PER_SKU).map { buildShrinkageJournal(clientId, sku) } }
        inventoryJournalRepository.persist(journals)
    }

    private fun buildShrinkageJournal(clientId: Long, sku: SkuRef): InventoryJournal {
        val hoursAgo = 1 + rng.nextInt(SHRINKAGE_WINDOW_HOURS - 1)
        val ts = Instant.now().minus(Duration.ofHours(hoursAgo.toLong()))
        val lossAmount = MIN_SHRINKAGE_LOSS + rng.nextInt(SHRINKAGE_LOSS_RANGE)
        return InventoryJournal().apply {
            this.clientId = clientId
            this.recordType = JournalRecordType.CHANGED.code
            this.productNumber = sku.number
            this.productName = sku.number
            this.amount = BigDecimal(-lossAmount)
            this.activityCode = "DEMO_SHRINKAGE"
            this.operatorName = "demo-seed"
            this.created = ts
            this.modified = ts
        }
    }

    companion object {
        const val STUCK_ORDER_KEY = "stuck-order"
        const val SHRINKAGE_KEY = "shrinkage"

        private const val STUCK_ORDER_COUNT = 2
        private const val STUCK_ORDER_HOURS_AGO = 48L
        private const val MIN_LINE_AMOUNT = 1
        private const val LINE_AMOUNT_RANGE = 20

        private const val SHRINKAGE_SKU_COUNT = 2
        private const val SHRINKAGE_ROWS_PER_SKU = 2
        private const val SHRINKAGE_WINDOW_HOURS = 24
        private const val MIN_SHRINKAGE_LOSS = 5
        private const val SHRINKAGE_LOSS_RANGE = 15

        private val CUSTOMER_NAMES = listOf("Acme Distribution", "Blue Harbor Retail", "Cascade Foods")
    }
}
