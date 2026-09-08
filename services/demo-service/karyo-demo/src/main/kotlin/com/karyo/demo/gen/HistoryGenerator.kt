package com.karyo.demo.gen

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.service.DemoClock
import com.karyo.demo.service.Rng
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.domain.model.GoodsReceiptAsn
import com.karyo.orders.domain.model.GoodsReceiptLine
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.repository.GoodsReceiptAsnRepository
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Task 5: the core backdated-history generator. Loops [DemoConfig.historyDays] simulated days
 * and, for each, builds a weekday-weighted + mildly-trending count of complete order chains
 * (`DeliveryOrder` + lines -> `PickOrder` + `Pick`s -> `Shipment`, `DeliveryOrder` end-state
 * FINISHED — terminal, so historical orders never trip the stuck-order monitor) with
 * every timestamp backdated to that day via [DemoClock.dayInstant]. Roughly one day in three
 * additionally gets an `Asn` -> `GoodsReceipt` -> `GoodsReceiptLine`s chain (throughput-received
 * KPI input). A fixed-size batch of open `TransportOrder` (PUTAWAY) rows is seeded at the end,
 * backdated within the last few days, to cross the putaway-backlog monitor's threshold.
 *
 * Entity graphs are built via direct construction (not domain-service state machines) so every
 * timestamp can be set explicitly before persist — the analytics this feeds (KPIs, monitors,
 * forecasting/slotting) all read `created`/`modified`/`shipped_at` over trailing windows, so the
 * whole point of this generator is that those columns are NOT "now".
 *
 * Not idempotent by design (like [InventoryGenerator]): meant to run once per demo seed;
 * re-seeding is expected to `reset` first (Task 7). Per-run entity-number counters therefore
 * live as instance state reset implicitly by re-injection, not by any persisted sequence.
 */
@ApplicationScoped
@Suppress("LongParameterList")
class HistoryGenerator(
    private val deliveryOrderRepository: DeliveryOrderRepository,
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val shipmentRepository: ShipmentRepository,
    private val asnRepository: AsnRepository,
    private val goodsReceiptRepository: GoodsReceiptRepository,
    private val goodsReceiptAsnRepository: GoodsReceiptAsnRepository,
    private val transportOrderRepository: TransportOrderRepository,
    private val journalRepository: InventoryJournalRepository,
    private val config: DemoConfig,
) {
    // Deterministic per generator instance (seeded from config.seed).
    private val rng = Rng(config.seed)

    // Per-run unique-number counters (see class KDoc — not idempotent, reset expected first).
    private var doSeq = 0
    private var poSeq = 0
    private var shpSeq = 0
    private var asnSeq = 0
    private var grSeq = 0
    private var putSeq = 0

    @Transactional
    fun generate(clientId: Long, catalog: CatalogRefs, inventory: InventoryRefs): HistoryCounts {
        val clock = DemoClock(Instant.now(), config.historyDays)
        val stockRefsBySku = inventory.stock.groupBy { it.number }
        val weighted = weightedSkus(catalog).filter { stockRefsBySku.containsKey(it.first) }

        var orders = 0
        var picks = 0
        var shipments = 0
        var goodsReceipts = 0

        for (day in 0 until clock.days) {
            val dayResult = processDay(clientId, day, clock, catalog, weighted, stockRefsBySku)
            orders += dayResult.orders
            picks += dayResult.picks
            shipments += dayResult.shipments
            if (rng.bool(GOODS_RECEIPT_DAY_PROBABILITY)) {
                generateGoodsReceipt(clientId, day, clock, catalog, stockRefsBySku)
                goodsReceipts++
            }
        }

        val putawayBacklog = generatePutawayBacklog(clientId, clock, catalog, inventory)
        generateCrossDockDemo(clientId, clock, catalog)
        return HistoryCounts(orders, picks, shipments, goodsReceipts, putawayBacklog)
    }

    /**
     * Ceiling for journal `created`/`modified` timestamps derived from [DemoClock.today]. A plain
     * `clock.today` isn't quite enough: `@TestTransaction`-wrapped tests run the whole seed inside
     * one Postgres transaction, and `now()` there is frozen at transaction START — but `clock.today`
     * (`Instant.now()` captured when [HistoryGenerator.generate] began) is itself a little *after*
     * that, once catalog/inventory generation has already run in the same transaction. The margin
     * absorbs that gap so journal rows never read as future-dated relative to either wall clock.
     */
    private fun journalCeiling(clock: DemoClock): Instant = clock.today.minus(JOURNAL_SAFETY_MARGIN)

    // --- daily order -> pick -> ship chain -----------------------------------

    private data class DayResult(val orders: Int, val picks: Int, val shipments: Int)

    private fun processDay(
        clientId: Long,
        day: Int,
        clock: DemoClock,
        catalog: CatalogRefs,
        weighted: List<Pair<String, Double>>,
        stockRefsBySku: Map<String, List<StockRef>>,
    ): DayResult {
        val orderCount = ordersForDay(day, clock)
        if (orderCount == 0 || weighted.isEmpty()) return DayResult(0, 0, 0)

        val plans = (1..orderCount).map { buildOrderPlan(weighted, stockRefsBySku) }
        val orders = plans.map { buildDeliveryOrder(clientId, day, clock, it) }
        deliveryOrderRepository.persist(orders) // cascades DeliveryOrderLine (ALL)

        val pickOrders = orders.map { buildPickOrder(clientId, day, clock, it) }
        pickOrderRepository.persist(pickOrders)

        val picks = orders.indices.flatMap { i ->
            buildPicks(clientId, day, clock, orders[i], pickOrders[i], plans[i])
        }
        pickRepository.persist(picks)

        // each pick removes stock FROM its source bin — record it (backdated to the pick time).
        // Coerced to journalCeiling: on the most-recent simulated day, dayInstant(day, secondsIntoDay)
        // can land a few hours AFTER the anchor (the moment the seed run started) — journal rows
        // are the audit ledger and must never read as future-dated, unlike the picks themselves.
        val pickJournals = orders.indices.flatMap { i ->
            val plan = plans[i]
            val order = orders[i]
            val ts = clock.dayInstant(day, PICK_SECONDS_INTO_DAY).coerceAtMost(journalCeiling(clock))
            plan.lines.map { lp ->
                val locName = catalog.locations.first { it.id == lp.stockRef.locationId }.name
                InventoryJournal().apply {
                    this.clientId = clientId
                    this.recordType = JournalRecordType.PICKED.code
                    this.productNumber = lp.stockRef.number
                    this.amount = lp.amount
                    this.stockUnitAmount = lp.amount
                    this.fromStorageLocation = locName
                    this.correlationId = order.orderNumber
                    this.created = ts
                    this.modified = ts
                }
            }
        }
        journalRepository.persist(pickJournals)

        val shipments = orders.map { buildShipment(clientId, day, clock, it) }
        shipmentRepository.persist(shipments)

        return DayResult(orders.size, picks.size, shipments.size)
    }

    /** weekday-weighted (weekend ~0.4x) + mildly-trending (grows across the window) daily volume. */
    private fun ordersForDay(day: Int, clock: DemoClock): Int {
        val date = clock.dayInstant(day).atZone(ZoneOffset.UTC).toLocalDate()
        val weekdayFactor = if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            WEEKEND_FACTOR
        } else {
            1.0
        }
        val trendFactor = TREND_BASE + TREND_SLOPE * day / clock.days
        return rng.poisson(config.ordersPerDay * weekdayFactor * trendFactor)
    }

    private fun weightedSkus(catalog: CatalogRefs): List<Pair<String, Double>> {
        val movers = VelocitySkew.classify(catalog.skus.map { it.number })
        return catalog.skus.map { it.number to VelocitySkew.dailyPickProbability(movers.getValue(it.number)) }
    }

    /** Roulette-wheel pick over [weighted] (sku, weight) pairs — A movers dominate. */
    private fun pickWeightedSku(weighted: List<Pair<String, Double>>): String {
        val total = weighted.sumOf { it.second }
        var roll = rng.nextDouble() * total
        for ((sku, weight) in weighted) {
            roll -= weight
            if (roll <= 0.0) return sku
        }
        return weighted.last().first
    }

    private data class LinePlan(val stockRef: StockRef, val amount: BigDecimal)
    private data class OrderPlan(val orderNumber: String, val customerName: String, val lines: List<LinePlan>)

    private fun buildOrderPlan(
        weighted: List<Pair<String, Double>>,
        stockRefsBySku: Map<String, List<StockRef>>,
    ): OrderPlan {
        val lineCount = 1 + rng.nextInt(MAX_LINES_PER_ORDER)
        val lines = (1..lineCount).map {
            val sku = pickWeightedSku(weighted)
            val ref = rng.pick(stockRefsBySku.getValue(sku))
            LinePlan(ref, BigDecimal(MIN_LINE_AMOUNT + rng.nextInt(LINE_AMOUNT_RANGE)))
        }
        doSeq++
        return OrderPlan("DEMO-DO-%05d".format(doSeq), rng.pick(CUSTOMER_NAMES), lines)
    }

    private fun buildDeliveryOrder(clientId: Long, day: Int, clock: DemoClock, plan: OrderPlan): DeliveryOrder {
        val ts = clock.dayInstant(day, ORDER_SECONDS_INTO_DAY)
        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = plan.orderNumber
            this.customerName = plan.customerName
            this.state = OrderState.FINISHED.code
            this.created = ts
            this.modified = ts
            this.started = ts
            this.finished = ts
        }
        plan.lines.forEachIndexed { i, lp ->
            order.lines.add(
                DeliveryOrderLine().apply {
                    this.deliveryOrder = order
                    this.lineNumber = i + 1
                    this.itemDataId = lp.stockRef.itemDataId
                    this.itemDataNumber = lp.stockRef.number
                    this.amount = lp.amount
                    this.reservedAmount = lp.amount
                    this.state = OrderState.SHIPPED.code
                    // Deterministic plausible per-SKU price ($5.99-$94.99, stable, no RNG) --
                    // real seeded data for the B7 order-value tile, not a fabricated random.
                    this.unitPrice = java.math.BigDecimal.valueOf(5L + (lp.stockRef.itemDataId % 90))
                        .add(java.math.BigDecimal("0.99"))
                },
            )
        }
        return order
    }

    private fun buildPickOrder(clientId: Long, day: Int, clock: DemoClock, order: DeliveryOrder): PickOrder {
        val ts = clock.dayInstant(day, PICK_SECONDS_INTO_DAY)
        poSeq++
        return PickOrder().apply {
            this.clientId = clientId
            this.pickOrderNumber = "DEMO-PO-%05d".format(poSeq)
            this.deliveryOrderId = requireNotNull(order.id)
            this.deliveryOrderNumber = order.orderNumber
            this.state = PickState.PICKED.code
            this.started = ts
            this.finished = ts
            this.created = ts
            this.modified = ts
        }
    }

    private fun buildPicks(
        clientId: Long,
        day: Int,
        clock: DemoClock,
        order: DeliveryOrder,
        pickOrder: PickOrder,
        plan: OrderPlan,
    ): List<Pick> {
        val ts = clock.dayInstant(day, PICK_SECONDS_INTO_DAY)
        return plan.lines.indices.map { i ->
            val line = order.lines[i]
            val lp = plan.lines[i]
            Pick().apply {
                this.clientId = clientId
                this.pickOrderId = requireNotNull(pickOrder.id)
                this.deliveryOrderLineId = requireNotNull(line.id)
                this.itemDataId = lp.stockRef.itemDataId
                this.itemDataNumber = lp.stockRef.number
                this.sourceStockUnitId = lp.stockRef.stockUnitId
                this.plannedAmount = lp.amount
                this.pickedAmount = lp.amount
                this.state = PickState.PICKED.code
                this.created = ts
                this.modified = ts
            }
        }
    }

    private fun buildShipment(clientId: Long, day: Int, clock: DemoClock, order: DeliveryOrder): Shipment {
        val ts = clock.dayInstant(day, SHIP_SECONDS_INTO_DAY)
        shpSeq++
        return Shipment().apply {
            this.clientId = clientId
            this.shipmentNumber = "DEMO-SHP-%05d".format(shpSeq)
            this.deliveryOrderId = requireNotNull(order.id)
            this.deliveryOrderNumber = order.orderNumber
            this.state = ShipmentState.SHIPPED.code
            this.started = ts
            this.finished = ts
            this.shippedAt = ts
            // Carrier/service/tracking — a real manifest assigns these via the
            // CarrierAdapter SPI; the demo seeds plausible values so the Orders
            // FULFILL slot (B6) shows real carrier data instead of "—".
            this.carrierName = rng.pick(CARRIER_NAMES)
            this.carrierService = rng.pick(CARRIER_SERVICES)
            this.trackingNumber = "1Z%03d%08d".format(rng.nextInt(1000), shpSeq)
            this.created = ts
            this.modified = ts
        }
    }

    // --- goods receipts (~1 in 3 days) ---------------------------------------

    private fun generateGoodsReceipt(
        clientId: Long,
        day: Int,
        clock: DemoClock,
        catalog: CatalogRefs,
        stockRefsBySku: Map<String, List<StockRef>>,
    ) {
        val ts = clock.dayInstant(day, RECEIPT_SECONDS_INTO_DAY)
        val asn = buildAsn(clientId, ts, catalog)
        asnRepository.persist(asn) // cascades AsnLine (ALL) — line ids available after this call

        val receipt = buildGoodsReceipt(clientId, ts, asn, catalog, stockRefsBySku)
        goodsReceiptRepository.persist(receipt) // cascades GoodsReceiptLine (ALL)
        goodsReceiptAsnRepository.persist(GoodsReceiptAsn(requireNotNull(receipt.id), requireNotNull(asn.id)))

        // each receipt line is stock arriving INTO a bin — record it (backdated to the receipt
        // time). Coerced to journalCeiling for the same reason as the pick journals above: the
        // journal ledger must never read as future-dated.
        val journalTs = ts.coerceAtMost(journalCeiling(clock))
        journalRepository.persist(
            receipt.lines.map { line ->
                InventoryJournal().apply {
                    this.clientId = clientId
                    this.recordType = JournalRecordType.CREATED.code
                    this.productNumber = line.itemDataNumber
                    this.amount = line.amount
                    this.stockUnitAmount = line.amount
                    this.toStorageLocation = line.locationName
                    this.correlationId = asn.asnNumber
                    this.created = journalTs
                    this.modified = journalTs
                }
            },
        )
    }

    private fun buildAsn(clientId: Long, ts: Instant, catalog: CatalogRefs): Asn {
        asnSeq++
        val asn = Asn().apply {
            this.clientId = clientId
            this.asnNumber = "DEMO-ASN-%05d".format(asnSeq)
            this.carrierName = rng.pick(CARRIER_NAMES)
            this.supplierName = rng.pick(SUPPLIER_NAMES)
            this.expectedDate = LocalDate.ofInstant(ts, ZoneOffset.UTC)
            this.state = OrderState.FINISHED.code
            this.created = ts
            this.modified = ts
        }
        for (i in 1..RECEIPT_LINE_COUNT) {
            val sku = rng.pick(catalog.skus)
            val amount = BigDecimal(MIN_LINE_AMOUNT + rng.nextInt(LINE_AMOUNT_RANGE))
            asn.lines.add(
                AsnLine().apply {
                    this.asn = asn
                    this.lineNumber = i
                    this.itemDataId = sku.itemDataId
                    this.itemDataNumber = sku.number
                    this.expectedAmount = amount
                    this.receivedAmount = amount
                    this.state = OrderState.FINISHED.code
                },
            )
        }
        return asn
    }

    private fun buildGoodsReceipt(
        clientId: Long,
        ts: Instant,
        asn: Asn,
        catalog: CatalogRefs,
        stockRefsBySku: Map<String, List<StockRef>>,
    ): GoodsReceipt {
        grSeq++
        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = "DEMO-GR-%05d".format(grSeq)
            this.carrierName = asn.carrierName
            this.state = OrderState.FINISHED.code
            this.created = ts
            this.modified = ts
        }
        asn.lines.forEach { asnLine ->
            receipt.lines.add(receiptLineFor(receipt, asnLine, ts, catalog, stockRefsBySku))
        }
        return receipt
    }

    private fun receiptLineFor(
        receipt: GoodsReceipt,
        asnLine: AsnLine,
        ts: Instant,
        catalog: CatalogRefs,
        stockRefsBySku: Map<String, List<StockRef>>,
    ): GoodsReceiptLine {
        val stockRef = stockRefsBySku[asnLine.itemDataNumber]?.let { rng.pick(it) }
            ?: stockRefsBySku.values.first().first()
        val location = catalog.locations.first { it.id == stockRef.locationId }
        return GoodsReceiptLine().apply {
            this.goodsReceipt = receipt
            this.asnLineId = asnLine.id
            this.itemDataId = asnLine.itemDataId
            this.itemDataNumber = asnLine.itemDataNumber
            this.amount = asnLine.receivedAmount
            this.locationId = location.id
            this.locationName = location.name
            this.unitLoadLabel = "DEMO-UL-%03d".format(stockRef.unitLoadId)
            this.stockUnitId = stockRef.stockUnitId
            this.unitLoadId = stockRef.unitLoadId
            this.created = ts
            this.modified = ts
        }
    }

    // --- cross-docking demo hook (Advanced Fulfillment pack) -----------------

    /**
     * Task 8 (cross-docking sprint): one open [DeliveryOrder] plus one still-RELEASED [Asn]
     * line pre-targeted at it via [AsnLine.crossDockDeliveryOrderId], both for the same SKU.
     * Deliberately NOT run through [com.karyo.orders.service.GoodsReceiptService] -- unlike
     * every other row this generator seeds, this pair is meant to be received LIVE through the
     * normal Receiving screen/API, so a demo instance with `advanced-fulfillment` licensed and
     * both `karyo.crossdock.*` toggles enabled can actually exercise
     * [com.karyo.crossdock.service.CrossDockInterceptor]'s pre-distributed rung end to end
     * (receive this exact ASN line at any dock location -> a `CrossDockOrder` MATCHED row +
     * a CROSS_DOCK transport straight to a `CROSS_DOCK_STAGING` location, bypassing putaway).
     * Unlicensed (the out-of-the-box default), receiving this same ASN line is a complete
     * no-op for cross-docking -- ordinary auto-putaway runs exactly as it would for any other
     * line, so the link is harmless on a stock demo instance.
     *
     * Uses "now" (not backdated like the rest of this generator) so both rows read as live,
     * current, actionable data rather than history.
     */
    private fun generateCrossDockDemo(clientId: Long, clock: DemoClock, catalog: CatalogRefs) {
        val sku = rng.pick(catalog.skus)
        val ts = clock.today
        val amount = BigDecimal(MIN_LINE_AMOUNT + rng.nextInt(LINE_AMOUNT_RANGE))

        val order = DeliveryOrder().apply {
            this.clientId = clientId
            this.orderNumber = "DEMO-XD-DO-00001"
            this.customerName = rng.pick(CUSTOMER_NAMES)
            this.state = OrderState.RELEASED.code
            this.created = ts
            this.modified = ts
        }
        order.lines.add(
            DeliveryOrderLine().apply {
                this.deliveryOrder = order
                this.lineNumber = 1
                this.itemDataId = sku.itemDataId
                this.itemDataNumber = sku.number
                this.amount = amount
                this.state = OrderState.RELEASED.code
                this.unitPrice = BigDecimal.valueOf(5L + (sku.itemDataId % 90)).add(BigDecimal("0.99"))
            },
        )
        deliveryOrderRepository.persist(order) // cascades DeliveryOrderLine (ALL); id available after

        val asn = Asn().apply {
            this.clientId = clientId
            this.asnNumber = "DEMO-XD-ASN-00001"
            this.carrierName = rng.pick(CARRIER_NAMES)
            this.supplierName = rng.pick(SUPPLIER_NAMES)
            this.expectedDate = LocalDate.ofInstant(ts, ZoneOffset.UTC)
            this.state = OrderState.RELEASED.code
            this.created = ts
            this.modified = ts
        }
        asn.lines.add(
            AsnLine().apply {
                this.asn = asn
                this.lineNumber = 1
                this.itemDataId = sku.itemDataId
                this.itemDataNumber = sku.number
                this.expectedAmount = amount
                this.state = OrderState.RELEASED.code
                this.crossDockDeliveryOrderId = requireNotNull(order.id)
            },
        )
        asnRepository.persist(asn) // cascades AsnLine (ALL)
    }

    // --- putaway backlog (open PUTAWAY transport orders) ---------------------

    private fun generatePutawayBacklog(
        clientId: Long,
        clock: DemoClock,
        catalog: CatalogRefs,
        inventory: InventoryRefs,
    ): Int {
        val orders = (1..PUTAWAY_BACKLOG_COUNT).map { buildPutawayOrder(clientId, clock, catalog, inventory) }
        transportOrderRepository.persist(orders)
        return orders.size
    }

    private fun buildPutawayOrder(
        clientId: Long,
        clock: DemoClock,
        catalog: CatalogRefs,
        inventory: InventoryRefs,
    ): TransportOrder {
        putSeq++
        val stockRef = rng.pick(inventory.stock)
        val location = catalog.locations.first { it.id == stockRef.locationId }
        val ts = clock.daysAgo((1 + rng.nextInt(PUTAWAY_BACKLOG_MAX_DAYS_AGO)).toLong())
        return TransportOrder().apply {
            this.clientId = clientId
            this.orderNumber = "DEMO-PUT-%05d".format(putSeq)
            this.transportType = TransportType.PUTAWAY
            this.unitLoadId = stockRef.unitLoadId
            this.unitLoadLabel = "DEMO-UL-%03d".format(stockRef.unitLoadId)
            this.sourceLocationId = location.id
            this.sourceLocationName = location.name
            this.state = OrderState.RELEASED.code
            this.created = ts
            this.modified = ts
            // finished stays null — this IS the open backlog the monitor reads.
        }
    }

    companion object {
        private const val MAX_LINES_PER_ORDER = 3
        private const val MIN_LINE_AMOUNT = 1
        private const val LINE_AMOUNT_RANGE = 20
        private const val WEEKEND_FACTOR = 0.4
        private const val TREND_BASE = 0.8
        private const val TREND_SLOPE = 0.4
        private const val GOODS_RECEIPT_DAY_PROBABILITY = 1.0 / 3.0
        private const val RECEIPT_LINE_COUNT = 2
        private const val PUTAWAY_BACKLOG_COUNT = 20
        private const val PUTAWAY_BACKLOG_MAX_DAYS_AGO = 5

        private const val RECEIPT_SECONDS_INTO_DAY = 7L * 3600
        private const val ORDER_SECONDS_INTO_DAY = 8L * 3600
        private const val PICK_SECONDS_INTO_DAY = 10L * 3600
        private const val SHIP_SECONDS_INTO_DAY = 16L * 3600

        /** See [journalCeiling] — absorbs same-transaction JVM/Postgres clock skew. */
        private val JOURNAL_SAFETY_MARGIN: Duration = Duration.ofMinutes(5)

        private val CUSTOMER_NAMES = listOf(
            "Acme Distribution", "Blue Harbor Retail", "Cascade Foods", "Dockside Traders",
            "Evergreen Supply Co", "Frontier Logistics", "Golden Gate Wholesale", "Harbor Point Goods",
        )
        private val CARRIER_NAMES = listOf("FastFreight", "Continental Carriers", "Blue Line Shipping")
        private val CARRIER_SERVICES = listOf("Ground", "Express", "2-Day Air", "Freight LTL")
        private val SUPPLIER_NAMES = listOf(
            "Acme Distribution", "Northwind Traders", "Globex Supply Co", "Meridian Wholesale",
        )
    }
}
