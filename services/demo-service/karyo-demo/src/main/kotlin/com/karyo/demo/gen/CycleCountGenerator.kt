package com.karyo.demo.gen

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.service.DemoClock
import com.karyo.demo.service.Rng
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.stocktaking.domain.model.CountLine
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.repository.CountLineRepository
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountLineState
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.stocktaking.vo.CountType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Task 6 (part 1): backdated cycle-count history — one closed [CountSession] holding
 * [ORDER_COUNT] finished [CountOrder]s, each counting the one SKU [InventoryGenerator] placed at
 * that location (this demo warehouse places exactly one stock unit per occupied location, so one
 * order = one line). Every other order's line gets a deliberately-different `counted_amount`
 * (variance): this is both the cycle-count-variance monitor's input
 * ([com.karyo.monitors.detector.CountVarianceReader] reads `count_lines` directly) and the
 * `kpi_accuracy_daily` reporting view's input (`accurate_lines` = `counted_amount = planned_amount`,
 * bucketed by `count_lines.created` — hence the backdating below).
 *
 * `planned_amount` here is a plausible standalone figure (not read back from the actual
 * [StockUnit] on-hand) — the count-order workflow doesn't require it to reconcile with live stock,
 * only that counted vs. planned differ on the variance lines.
 *
 * Not idempotent by design (like [InventoryGenerator]/[HistoryGenerator]): meant to run once per
 * demo seed; re-seeding is expected to `reset` first (Task 7).
 */
@ApplicationScoped
class CycleCountGenerator(
    private val countSessionRepository: CountSessionRepository,
    private val countOrderRepository: CountOrderRepository,
    private val countLineRepository: CountLineRepository,
    private val journalRepository: InventoryJournalRepository,
    private val storageLocationRepository: StorageLocationRepository,
    private val config: DemoConfig,
) {
    // Deterministic per generator instance (seeded from config.seed).
    private val rng = Rng(config.seed)

    private var orderSeq = 0

    @Transactional
    fun generate(clientId: Long, catalog: CatalogRefs, inventory: InventoryRefs): Int {
        val clock = DemoClock(Instant.now(), config.historyDays)
        val session = buildSession(clientId, clock)
        countSessionRepository.persist(session)
        val sessionId = requireNotNull(session.id)

        val locationsById = catalog.locations.associateBy { it.id }
        val chosenStock = inventory.stock.take(ORDER_COUNT)

        var lineCount = 0
        chosenStock.forEachIndexed { i, stockRef ->
            val ts = clock.daysAgo(daysAgoFor(i))
            val order = buildOrder(clientId, sessionId, locationsById, stockRef, ts)
            countOrderRepository.persist(order)
            val orderId = requireNotNull(order.id)
            stampLastCounted(order.locationId, ts)

            val line = buildLine(clientId, orderId, stockRef, hasVariance = i % VARIANCE_EVERY == 0, ts = ts)
            countLineRepository.persist(line)
            lineCount++

            val variance = requireNotNull(line.countedAmount) { "countedAmount always set by buildLine" }.subtract(line.plannedAmount)
            if (variance.signum() != 0) {
                journalRepository.persist(
                    InventoryJournal().apply {
                        this.clientId = clientId
                        this.recordType = JournalRecordType.COUNTED.code
                        this.productNumber = line.itemDataNumber
                        this.amount = variance.abs()
                        this.stockUnitAmount = line.countedAmount
                        if (variance.signum() > 0) {
                            this.toStorageLocation = order.locationName
                        } else {
                            this.fromStorageLocation = order.locationName
                        }
                        this.correlationId = order.orderNumber
                        this.created = ts
                        this.modified = ts
                    },
                )
            }
        }
        return lineCount
    }

    private fun buildSession(clientId: Long, clock: DemoClock): CountSession {
        val started = clock.daysAgo(SESSION_MAX_DAYS_AGO.toLong())
        return CountSession().apply {
            this.clientId = clientId
            this.sessionNumber = "DEMO-CS-00001"
            this.type = CountType.CYCLE.name
            this.state = CountSessionState.CLOSED.code
            this.started = started
            this.ended = clock.daysAgo(1)
            this.created = started
            this.modified = clock.daysAgo(1)
        }
    }

    private fun buildOrder(
        clientId: Long,
        sessionId: Long,
        locationsById: Map<Long, LocationRef>,
        stockRef: StockRef,
        ts: Instant,
    ): CountOrder {
        val location = requireNotNull(locationsById[stockRef.locationId]) { "location ${stockRef.locationId} not found" }
        orderSeq++
        return CountOrder().apply {
            this.clientId = clientId
            this.sessionId = sessionId
            this.orderNumber = "DEMO-CO-%05d".format(orderSeq)
            this.locationId = location.id
            this.locationName = location.name
            this.state = CountOrderState.FINISHED.code
            this.blindCount = true
            this.started = ts
            this.finished = ts
            this.created = ts
            this.modified = ts
        }
    }

    private fun buildLine(clientId: Long, orderId: Long, stockRef: StockRef, hasVariance: Boolean, ts: Instant): CountLine {
        val planned = randomAmount()
        val counted = if (hasVariance) planned.add(varianceDelta()) else planned
        return CountLine().apply {
            this.clientId = clientId
            this.countOrderId = orderId
            this.stockUnitId = stockRef.stockUnitId
            this.itemDataId = stockRef.itemDataId
            this.itemDataNumber = stockRef.number
            this.plannedAmount = planned
            this.countedAmount = counted
            this.state = CountLineState.FINISHED.code
            this.created = ts
            this.modified = ts
        }
    }

    /**
     * B10: stamp the counted location's `lastCountedAt` with this order's timestamp — only the
     * [ORDER_COUNT] locations counted here ever get a non-null value; every other seeded
     * location honestly stays null (rendered "—" by the frontend).
     */
    private fun stampLastCounted(locationId: Long, ts: Instant) {
        storageLocationRepository.findById(locationId)?.let { it.lastCountedAt = ts }
    }

    private fun daysAgoFor(index: Int): Long = (1 + index * DAY_SPREAD_STEP).toLong().coerceAtMost(config.historyDays.toLong())

    private fun randomAmount(): BigDecimal = BigDecimal(MIN_AMOUNT + rng.nextInt(AMOUNT_RANGE))

    private fun varianceDelta(): BigDecimal {
        val magnitude = MIN_VARIANCE + rng.nextInt(VARIANCE_RANGE)
        return if (rng.bool(HALF_CHANCE)) BigDecimal(magnitude) else BigDecimal(-magnitude)
    }

    companion object {
        private const val ORDER_COUNT = 8
        private const val VARIANCE_EVERY = 2
        private const val SESSION_MAX_DAYS_AGO = 20
        private const val DAY_SPREAD_STEP = 2
        private const val MIN_AMOUNT = 10
        private const val AMOUNT_RANGE = 90
        private const val MIN_VARIANCE = 1
        private const val VARIANCE_RANGE = 5
        private const val HALF_CHANCE = 0.5
    }
}
