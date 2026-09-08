package com.karyo.demo.gen

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.service.Rng
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.UnitLoadWeightCalculator
import com.karyo.layout.domain.model.FixAssignment
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageLocationRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Task 4: places CURRENT-STATE on-hand stock (one [UnitLoad] + one [StockUnit] per occupied
 * location, state `ON_STOCK`=300) across [CatalogRefs.locations] — this is what the occupancy
 * heatmap and utilization KPI read. A handful of the freshly-placed stock units additionally get
 * a lot number + a `best_before` within 7 days of today (expiry-risk monitor input). A handful of
 * SKUs get [FixAssignment] rows (min/max/desired); one of those (`belowMinSku`) gets its `min`
 * deliberately set above its actual on-hand total (bin-below-reorder monitor input).
 *
 * `created`/`modified` are left at the [com.karyo.common.domain.BaseEntity] defaults
 * (`Instant.now()`) — current-state, not backdated (backdating is the history generator's job).
 *
 * Each fix-assignment is co-located with that SKU's own placed stock (same `location_id`) so
 * the per-location on-hand [com.karyo.layout.service.FixAssignmentService.enrichStockAmount]
 * reads back is the SKU's *real* on-hand, not an unrelated (possibly empty) bin — `min_amount`
 * is then set relative to that real on-hand, so exactly one fix-assignment (`belowMinSku`) is
 * genuinely below its min and the other four are genuinely healthy.
 *
 * Not idempotent by design (unlike [CatalogGenerator]): this is meant to run once per demo seed,
 * so no by-name/number lookups are done here before persisting. Reset is now guaranteed by the
 * endpoint — [com.karyo.demo.api.v1.DemoResource.seed] (Task 9, defect-burndown) always calls
 * `DemoDataService.reset()` before `seed()`, so a caller hitting `POST /api/v1/demo/seed` twice
 * in a row never sees this generator collide with its own previous run's rows.
 */
@ApplicationScoped
@Suppress("LongParameterList")
class InventoryGenerator(
    private val unitLoadRepository: UnitLoadRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val unitLoadTypeRepository: UnitLoadTypeRepository,
    private val storageLocationRepository: StorageLocationRepository,
    private val fixAssignmentRepository: FixAssignmentRepository,
    private val journalRepository: InventoryJournalRepository,
    private val weightCalculator: UnitLoadWeightCalculator,
    private val config: DemoConfig,
) {
    // Deterministic per generator instance (seeded from config.seed) — used only for the
    // plausible-but-arbitrary on-hand amount variety below.
    private val rng = Rng(config.seed)

    // Backdated placement timestamp for the initial-placement journal rows below (window start).
    private val placedAt: Instant = Instant.now().minus(Duration.ofDays(config.historyDays.toLong()))

    @Transactional
    fun generate(clientId: Long, catalog: CatalogRefs): InventoryRefs {
        val unitLoadType = requireNotNull(unitLoadTypeRepository.findById(catalog.unitLoadTypeId)) {
            "unit load type ${catalog.unitLoadTypeId} not found"
        }
        val placement = placeStock(clientId, catalog, unitLoadType)
        // Row :1449 (defect-burndown-5): nothing previously computed a weight for demo-seeded
        // unit loads (the generator persists stock directly, never through StockService's
        // mutation points UnitLoadWeightCalculator is normally wired off), so every seeded
        // pallet read weight=null -- the Locations page's lifting-capacity figures and gross-
        // weight-by-location reads were always blank for the demo warehouse. One batch call
        // over everything placed this run (bounded, not per-UL) closes that gap.
        weightCalculator.recalculateAll(placement.unitLoads)
        val expiringSkus = markNearExpiry(placement.stockUnits)
        val belowMinSku = seedFixAssignments(clientId, catalog, placement.refs, placement.stockUnits)
        return InventoryRefs(stock = placement.refs, expiringSkus = expiringSkus, belowMinSku = belowMinSku)
    }

    // --- stock placement -----------------------------------------------------

    private data class Placement(
        val refs: List<StockRef>,
        val stockUnits: List<StockUnit>,
        val unitLoads: List<UnitLoad>,
    )

    private fun placeStock(clientId: Long, catalog: CatalogRefs, unitLoadType: UnitLoadType): Placement {
        val refs = mutableListOf<StockRef>()
        val units = mutableListOf<StockUnit>()
        val unitLoads = mutableListOf<UnitLoad>()

        catalog.locations.forEachIndexed { index, location ->
            if (isEmptyLocation(index)) return@forEachIndexed
            val sku = catalog.skus[index % catalog.skus.size]
            val (unitLoad, stockUnit) = placeOne(clientId, location, unitLoadType, sku, index)

            units += stockUnit
            unitLoads += unitLoad
            refs += StockRef(
                stockUnitId = requireNotNull(stockUnit.id),
                itemDataId = sku.itemDataId,
                number = sku.number,
                locationId = location.id,
                unitLoadId = requireNotNull(unitLoad.id),
            )
        }
        return Placement(refs, units, unitLoads)
    }

    private fun isEmptyLocation(index: Int): Boolean = index % EMPTY_LOCATION_EVERY == EMPTY_LOCATION_EVERY - 1

    private fun placeOne(
        clientId: Long,
        location: LocationRef,
        unitLoadType: UnitLoadType,
        sku: SkuRef,
        index: Int,
    ): Pair<UnitLoad, StockUnit> {
        val unitLoad = newUnitLoad(clientId, location, unitLoadType, index)
        unitLoadRepository.persist(unitLoad)
        val stockUnit = newStockUnit(clientId, sku, unitLoad, randomAmount())
        stockUnitRepository.persist(stockUnit)
        // initial placement is a real receipt into this bin — record it (backdated to window start)
        journalRepository.persist(
            InventoryJournal().apply {
                this.clientId = clientId
                this.recordType = JournalRecordType.CREATED.code
                this.productNumber = sku.number
                this.lotNumber = stockUnit.lotNumber
                this.amount = stockUnit.amount
                this.stockUnitAmount = stockUnit.amount
                this.toStorageLocation = location.name
                this.correlationId = unitLoad.labelId
                this.created = placedAt
                this.modified = placedAt
            },
        )
        markOccupied(location.id)
        return unitLoad to stockUnit
    }

    /**
     * Reflect the placed unit load in the location's `allocation`, mirroring the
     * live [com.karyo.layout.messaging.UnitLoadTransferredObserver] (+100% per UL,
     * capped at 100). The demo persists stock directly and never fires
     * UnitLoadTransferredEvent, so without this the seeded locations keep
     * allocation=0 and the Locations occupancy ring reads "Empty / 0%" over
     * populated bins.
     */
    private fun markOccupied(locationId: Long) {
        val location = storageLocationRepository.findById(locationId) ?: return
        location.allocation = (location.allocation + FULL_ALLOCATION).coerceAtMost(FULL_ALLOCATION)
    }

    private fun newUnitLoad(clientId: Long, location: LocationRef, unitLoadType: UnitLoadType, index: Int): UnitLoad =
        UnitLoad().apply {
            this.clientId = clientId
            this.labelId = "DEMO-UL-%03d".format(index)
            this.unitLoadType = unitLoadType
            this.storageLocationId = location.id
            this.storageLocationName = location.name
            this.state = StockState.ON_STOCK.code
        }

    private fun newStockUnit(clientId: Long, sku: SkuRef, unitLoad: UnitLoad, amount: BigDecimal): StockUnit =
        StockUnit().apply {
            this.clientId = clientId
            this.itemDataId = sku.itemDataId
            this.itemDataNumber = sku.number
            this.amount = amount
            this.reservedAmount = BigDecimal.ZERO
            this.state = StockState.ON_STOCK.code
            this.unitLoad = unitLoad
        }

    private fun randomAmount(): BigDecimal = BigDecimal(MIN_AMOUNT + rng.nextInt(AMOUNT_RANGE))

    // --- near-expiry lots ------------------------------------------------------

    private fun markNearExpiry(stockUnits: List<StockUnit>): List<String> {
        val chosen = stockUnits.take(EXPIRING_COUNT)
        val today = LocalDate.now()
        chosen.forEachIndexed { i, unit ->
            unit.lotNumber = "LOT-%02d".format(i + 1)
            unit.bestBefore = today.plusDays(i.toLong())
        }
        return chosen.map { it.itemDataNumber }.distinct()
    }

    // --- fix assignments ---------------------------------------------------

    private fun seedFixAssignments(
        clientId: Long,
        catalog: CatalogRefs,
        stockRefs: List<StockRef>,
        stockUnits: List<StockUnit>,
    ): String {
        val onHandByUnitId = stockUnits.associate { requireNotNull(it.id) to it.amount }
        val fixSkus = catalog.skus.take(FIX_ASSIGNMENT_COUNT)
        val belowMinSku = fixSkus.first()
        fixSkus.forEach { sku ->
            val (locationId, onHandThere) = coLocatedStock(sku, stockRefs, onHandByUnitId)
            val location = requireNotNull(storageLocationRepository.findById(locationId)) {
                "location $locationId not found"
            }
            val isBelowMin = sku.number == belowMinSku.number
            val min = fixAssignmentMin(isBelowMin, onHandThere)
            fixAssignmentRepository.persist(newFixAssignment(clientId, location, sku, min))
        }
        return belowMinSku.number
    }

    /**
     * Finds where this SKU's own stock was actually placed (the first location it was placed at,
     * by catalog order) and its on-hand total *at that one location* — the value the real
     * bin-below-reorder detector reads via `FixAssignmentService.enrichStockAmount(itemDataId,
     * locationId)`, which is per-location, not a sum across every location holding the SKU.
     */
    private fun coLocatedStock(
        sku: SkuRef,
        stockRefs: List<StockRef>,
        onHandByUnitId: Map<Long, BigDecimal>,
    ): Pair<Long, BigDecimal> {
        val refsForSku = stockRefs.filter { it.itemDataId == sku.itemDataId }
        require(refsForSku.isNotEmpty()) { "no placed stock found for sku ${sku.number}" }
        val locationId = refsForSku.first().locationId
        val onHandThere = refsForSku.filter { it.locationId == locationId }
            .sumOf { onHandByUnitId.getValue(it.stockUnitId) }
        return locationId to onHandThere
    }

    private fun fixAssignmentMin(isBelowMin: Boolean, onHandThere: BigDecimal): BigDecimal =
        if (isBelowMin) {
            onHandThere.add(BELOW_MIN_MARGIN)
        } else {
            maxOf(onHandThere.subtract(HEALTHY_MARGIN), BigDecimal.ONE)
        }

    private fun newFixAssignment(
        clientId: Long,
        location: StorageLocation,
        sku: SkuRef,
        min: BigDecimal,
    ): FixAssignment =
        FixAssignment().apply {
            this.clientId = clientId
            this.location = location
            this.itemDataId = sku.itemDataId
            this.itemDataNumber = sku.number
            this.minAmount = min
            this.maxAmount = min.add(DEFAULT_MAX_HEADROOM)
            this.desiredAmount = min.add(DEFAULT_DESIRED_HEADROOM)
        }

    companion object {
        /** Allocation % applied per placed UL — matches UnitLoadTransferredObserver.ALLOCATION_PER_UL. */
        private val FULL_ALLOCATION = BigDecimal("100")
        private const val EMPTY_LOCATION_EVERY = 5
        private const val MIN_AMOUNT = 10
        private const val AMOUNT_RANGE = 190
        private const val EXPIRING_COUNT = 4
        private const val FIX_ASSIGNMENT_COUNT = 5
        private val BELOW_MIN_MARGIN = BigDecimal("100")
        private val HEALTHY_MARGIN = BigDecimal("5")
        private val DEFAULT_MAX_HEADROOM = BigDecimal("80")
        private val DEFAULT_DESIRED_HEADROOM = BigDecimal("40")
    }
}
