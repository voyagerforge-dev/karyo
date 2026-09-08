package com.karyo.demo.gen

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.service.Rng
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.layout.domain.model.Area
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.domain.model.Zone
import com.karyo.layout.repository.AreaRepository
import com.karyo.layout.repository.LocationTypeRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.repository.ZoneRepository
import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemDataNumber
import com.karyo.product.domain.model.ItemUnit
import com.karyo.product.repository.ItemDataNumberRepository
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Task 3: constructs the demo warehouse's CURRENT-STATE catalog — 4 zones/areas, one location
 * type, [DemoConfig.locationCount] storage locations (distinct ascending `order_index` = the
 * slotting proximity signal), one unit-load type, and [DemoConfig.skuCount] SKUs (`ItemData` +
 * `ItemUnit` + `ItemDataNumber`). Idempotent by name/number — re-running [generate] converges
 * on the same rows rather than duplicating them.
 *
 * `created`/`modified` are left at the [com.karyo.common.domain.BaseEntity] defaults
 * (`Instant.now()`): the catalog is current-state, not backdated — backdating is the history
 * generator's job in a later task.
 */
@ApplicationScoped
@Suppress("LongParameterList")
class CatalogGenerator(
    private val zoneRepository: ZoneRepository,
    private val areaRepository: AreaRepository,
    private val locationTypeRepository: LocationTypeRepository,
    private val storageLocationRepository: StorageLocationRepository,
    private val itemUnitRepository: ItemUnitRepository,
    private val itemDataRepository: ItemDataRepository,
    private val itemDataNumberRepository: ItemDataNumberRepository,
    private val unitLoadTypeRepository: UnitLoadTypeRepository,
    private val config: DemoConfig,
) {
    // Deterministic per generator instance (seeded from config.seed) — used for the plausible-
    // but-arbitrary SKU weight/dimension variety below. Never consulted for idempotency
    // decisions (those are always by-name/number lookups), so re-running `generate` still
    // converges regardless of how far the sequence has advanced.
    private val rng = Rng(config.seed)

    @Transactional
    fun generate(clientId: Long): CatalogRefs {
        val zones = ensureZones()
        val locations = ensureLocations(clientId, zones, config.locationCount)
        val skus = ensureSkus(clientId, config.skuCount)
        val unitLoadTypeId = ensureUnitLoadType()
        return CatalogRefs(
            skus = skus,
            locations = locations,
            unitLoadTypeId = unitLoadTypeId,
            zoneIds = zones.map { requireNotNull(it.id) },
        )
    }

    // --- zones -----------------------------------------------------------

    private fun ensureZones(): List<Zone> = ZONE_SPECS.map { ensureZone(it) }

    private fun ensureZone(spec: ZoneSpec): Zone =
        zoneRepository.findByName(spec.zoneName)
            ?: Zone().apply { name = spec.zoneName }.also { zoneRepository.persist(it) }

    private fun ensureArea(spec: ZoneSpec): Area =
        areaRepository.findByName(spec.areaName)
            ?: Area().apply { name = spec.areaName; usages = spec.usage }.also { areaRepository.persist(it) }

    // --- locations ---------------------------------------------------------

    private fun ensureLocations(clientId: Long, zones: List<Zone>, count: Int): List<LocationRef> {
        val locationType = ensureLocationType()
        val areasBySpec = ZONE_SPECS.associateWith { ensureArea(it) }
        val zonesByName = zones.associateBy { it.name }

        return (1..count).map { index ->
            val spec = ZONE_SPECS[(index - 1) % ZONE_SPECS.size]
            val zone = zonesByName.getValue(spec.zoneName)
            val area = areasBySpec.getValue(spec)
            ensureLocation(clientId, index, count, zone, area, locationType)
        }
    }

    private fun ensureLocation(
        clientId: Long,
        index: Int,
        total: Int,
        zone: Zone,
        area: Area,
        locationType: LocationType,
    ): LocationRef {
        val name = "LOC-%02d".format(index)
        val location = storageLocationRepository.findByName(name, clientId)
            ?: StorageLocation().apply {
                this.clientId = clientId
                this.name = name
                this.locationType = locationType
                this.area = area
                this.zone = zone
                this.orderIndex = index - 1
            }.also { storageLocationRepository.persist(it) }

        // Phase B (B9/B10/B12): deterministic, believable metadata derived from index/zone —
        // set on both fresh and idempotent-lookup rows so a re-run without --reset-db stays
        // in sync with this mapping.
        val kind = kindFor(index, total)
        location.kind = kind
        location.capacity = capacityFor(kind)
        location.temperatureZone = ZONE_TEMPERATURE[zone.name] ?: "AMBIENT"
        location.handlingClass = handlingClassFor(index)

        return LocationRef(
            id = requireNotNull(location.id),
            name = location.name,
            zoneId = requireNotNull(zone.id),
            orderIndex = location.orderIndex,
        )
    }

    /**
     * Deterministic slotting spread across the 40-location demo catalog: earliest `orderIndex`
     * (proximity-first) is pick face, then reserve, then a small staging band, with the tail as
     * bulk overflow. Proportional to [total] so it still degrades sensibly if `locationCount` is
     * ever changed.
     */
    private fun kindFor(index: Int, total: Int): String {
        val frac = index.toDouble() / total
        return when {
            frac <= PICK_FACE_FRAC -> "PICK_FACE"
            frac <= RESERVE_FRAC -> "RESERVE"
            frac <= STAGING_FRAC -> "STAGING"
            else -> "BULK"
        }
    }

    private fun capacityFor(kind: String): Int = when (kind) {
        "PICK_FACE" -> PICK_FACE_CAPACITY
        "STAGING" -> STAGING_CAPACITY
        "BULK" -> BULK_CAPACITY
        else -> RESERVE_CAPACITY
    }

    /** Mostly STANDARD; a deterministic minority of special-handling bins by index. */
    private fun handlingClassFor(index: Int): String = when {
        index % HAZMAT_EVERY == 0 -> "HAZMAT"
        index % FRAGILE_EVERY == 0 -> "FRAGILE"
        index % HIGH_VALUE_EVERY == 0 -> "HIGH_VALUE"
        else -> "STANDARD"
    }

    private fun ensureLocationType(): LocationType =
        locationTypeRepository.findByName(LOCATION_TYPE_NAME) ?: LocationType().apply {
            name = LOCATION_TYPE_NAME
            height = BigDecimal("2.000")
            width = BigDecimal("1.200")
            depth = BigDecimal("1.000")
            liftingCapacity = BigDecimal("500.000")
        }.also { locationTypeRepository.persist(it) }

    // --- SKUs ----------------------------------------------------------------

    private fun ensureSkus(clientId: Long, count: Int): List<SkuRef> {
        val itemUnit = ensureItemUnit()
        return (1..count).map { index -> ensureSku(clientId, index, itemUnit) }
    }

    private fun ensureItemUnit(): ItemUnit =
        itemUnitRepository.findByName(ITEM_UNIT_NAME)
            ?: ItemUnit().apply { name = ITEM_UNIT_NAME }.also { itemUnitRepository.persist(it) }

    private fun ensureSku(clientId: Long, index: Int, itemUnit: ItemUnit): SkuRef {
        val number = "DEMO-SKU-%02d".format(index)
        val itemData = itemDataRepository.findByNumber(number, clientId)
            ?: newItemData(clientId, index, number, itemUnit).also { itemDataRepository.persist(it) }
        ensureItemDataNumber(itemData, number)
        return SkuRef(itemDataId = requireNotNull(itemData.id), number = number)
    }

    private fun newItemData(clientId: Long, index: Int, number: String, itemUnit: ItemUnit): ItemData =
        ItemData().apply {
            this.clientId = clientId
            this.number = number
            this.name = "Demo Widget %02d".format(index)
            this.itemUnit = itemUnit
            // Plausible per-SKU variety (0.2kg-5kg), deterministic via the seeded Rng.
            this.weight = BigDecimal.valueOf(MIN_WEIGHT_KG + rng.nextDouble() * WEIGHT_RANGE_KG)
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP)
            this.height = BigDecimal("0.200")
            this.width = BigDecimal("0.150")
            this.depth = BigDecimal("0.150")
            // Phase B (B18): deterministic category assignment (no RNG) so volume-by-category
            // aggregation has real, stable groupings to report on.
            this.tradeGroup = CATEGORIES[(index - 1) % CATEGORIES.size]
        }

    private fun ensureItemDataNumber(itemData: ItemData, number: String) {
        val exists = itemDataNumberRepository.findByNumber(number).any { it.itemData.id == itemData.id }
        if (exists) return
        val entry = ItemDataNumber().apply {
            this.itemData = itemData
            this.number = number
            this.numberType = "INTERNAL"
            this.index = 0
        }
        itemDataNumberRepository.persist(entry)
    }

    // --- unit load type ------------------------------------------------------

    private fun ensureUnitLoadType(): Long {
        val existing = unitLoadTypeRepository.findByName(UNIT_LOAD_TYPE_NAME)
        if (existing != null) return requireNotNull(existing.id)
        val type = UnitLoadType().apply {
            name = UNIT_LOAD_TYPE_NAME
            height = BigDecimal("1.800")
            width = BigDecimal("1.200")
            depth = BigDecimal("1.000")
            liftingCapacity = BigDecimal("1000.000")
            usages = "STORAGE,PICKING"
        }
        unitLoadTypeRepository.persist(type)
        return requireNotNull(type.id)
    }

    private data class ZoneSpec(val zoneName: String, val areaName: String, val usage: String)

    companion object {
        private const val LOCATION_TYPE_NAME = "Demo Standard Bin"
        private const val ITEM_UNIT_NAME = "PCS"
        private const val UNIT_LOAD_TYPE_NAME = "Demo Pallet"
        private const val MIN_WEIGHT_KG = 0.2
        private const val WEIGHT_RANGE_KG = 4.8
        private const val WEIGHT_SCALE = 3

        // kindFor() thresholds — cumulative fraction of the catalog assigned to each kind,
        // ordered by ascending orderIndex (proximity-first): 25% pick face, next 37.5% reserve,
        // next 17.5% staging, remaining 20% bulk.
        private const val PICK_FACE_FRAC = 0.25
        private const val RESERVE_FRAC = 0.625
        private const val STAGING_FRAC = 0.80

        // capacityFor() — believable UL/pallet-slot counts by kind.
        private const val PICK_FACE_CAPACITY = 2
        private const val RESERVE_CAPACITY = 6
        private const val STAGING_CAPACITY = 4
        private const val BULK_CAPACITY = 12

        // handlingClassFor() — deterministic minority of special-handling bins.
        private const val HAZMAT_EVERY = 7
        private const val FRAGILE_EVERY = 11
        private const val HIGH_VALUE_EVERY = 13

        private val ZONE_SPECS = listOf(
            ZoneSpec("Receiving Dock", "Receiving", "GOODS_IN"),
            ZoneSpec("Main Storage", "Storage", "STORAGE"),
            ZoneSpec("Picking Zone", "Picking", "PICKING"),
            ZoneSpec("Shipping Dock", "Shipping", "GOODS_OUT,SHIP_STAGING"),
        )

        // Two of the four zones designated non-ambient (believable: chilled picking for
        // perishables, frozen staging ahead of a reefer dispatch); the rest default AMBIENT.
        private val ZONE_TEMPERATURE = mapOf(
            "Picking Zone" to "CHILLED",
            "Shipping Dock" to "FROZEN",
        )

        // Phase B (B18): fixed category list cycled by SKU index — deterministic, no RNG.
        private val CATEGORIES = listOf(
            "Electronics", "Apparel", "Home & Garden", "Grocery", "Tools", "Health & Beauty",
        )
    }
}
