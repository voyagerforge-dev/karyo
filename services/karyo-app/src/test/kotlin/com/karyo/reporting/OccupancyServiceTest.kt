package com.karyo.reporting

import com.karyo.reporting.service.OccupancyService
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
class OccupancyServiceTest {
    @Inject lateinit var service: OccupancyService
    @Inject lateinit var em: EntityManager

    /** Seed a zone + 4 locations (occupied / empty / locked-empty / locked-occupied) under client 0. */
    private fun seed() {
        val zoneId = (em.createNativeQuery(
            "INSERT INTO karyo.zones (version, created, modified, name) VALUES (0, now(), now(), 'OCC-Z1') RETURNING id"
        ).singleResult as Number).toLong()
        // location_types and areas have no client_id column — just ensure one exists
        val ltId = ensureLocationType()
        val areaId = ensureArea()
        // occupied: has a unit load
        val occLoc = insLoc(zoneId, ltId, areaId, "OCC-A", 0, lock = 0)
        insUnitLoad(occLoc, "OCC-A")
        // empty
        insLoc(zoneId, ltId, areaId, "OCC-B", 1, lock = 0)
        // locked but empty (no unit load)
        insLoc(zoneId, ltId, areaId, "OCC-C", 2, lock = 100)
        // locked AND occupied — this is the critical case: rollup counts it as occupied,
        // but display state must show "locked" (lock takes precedence over occupied).
        val lockedOccLoc = insLoc(zoneId, ltId, areaId, "OCC-D", 3, lock = 100)
        insUnitLoad(lockedOccLoc, "OCC-D")
    }

    @Test @TestTransaction
    fun `groups locations by zone with occupied empty locked states and a rollup`() {
        seed()
        val resp = service.build(0L)
        val z = resp.zones.first { it.zoneName == "OCC-Z1" }
        // 4 locations total (OCC-A occupied, OCC-B empty, OCC-C locked-empty, OCC-D locked-occupied)
        assertEquals(4, z.total)
        // occupied rollup uses the physical occupied boolean — both OCC-A and OCC-D have unit loads
        assertEquals(2, z.occupied)
        // display states: all 4 cover occupied / empty / locked (OCC-C and OCC-D both show "locked")
        assertEquals(setOf("occupied", "empty", "locked"), z.locations.map { it.state }.toSet())
        assertEquals("OCC-A", z.locations.first { it.state == "occupied" }.name)
        // OCC-C is locked-empty; confirm it shows "locked"
        val cellC = z.locations.first { it.name == "OCC-C" }
        assertEquals("locked", cellC.state)
        // OCC-D is locked AND occupied — display must be "locked", not "occupied"
        val cellD = z.locations.first { it.name == "OCC-D" }
        assertEquals("locked", cellD.state)
    }

    /**
     * Positive pin for V1005's `u.state <> 1000` predicate on `kpi_location_occupancy` (the
     * `OccupancyService` reads this view): a location whose only unit load has flipped terminal
     * ([com.karyo.inventory.api.vo.StockState.DELETABLE], code 1000 -- the count-flip in
     * `DefaultStockCountingPort.applyCount` and the hard-delete in `UnitLoadService.delete` both
     * produce this state) must not read as occupied. The existing seed()/test above only ever
     * seeds `state = 300` unit loads, so it never actually exercises this predicate -- it is a
     * regression guard for the surrounding view logic, not a check that the exclusion itself
     * works. Without the `<> 1000` clause a typo'd predicate (or its silent removal) would pass
     * every existing test here.
     */
    @Test @TestTransaction
    fun `a location whose only unit load is terminal (DELETABLE, 1000) is not occupied`() {
        val zoneId = (em.createNativeQuery(
            "INSERT INTO karyo.zones (version, created, modified, name) VALUES (0, now(), now(), 'OCC-Z2') RETURNING id"
        ).singleResult as Number).toLong()
        val ltId = ensureLocationType()
        val areaId = ensureArea()
        // terminal UL only -- must NOT count as occupied
        val terminalLoc = insLoc(zoneId, ltId, areaId, "OCC-E", 0, lock = 0)
        insUnitLoad(terminalLoc, "OCC-E", state = 1000)
        // adjacent location with a live UL -- occupied must stay true (guards against an
        // over-broad fix that stops counting occupancy altogether)
        val liveLoc = insLoc(zoneId, ltId, areaId, "OCC-F", 1, lock = 0)
        insUnitLoad(liveLoc, "OCC-F", state = 300)

        val resp = service.build(0L)
        val z = resp.zones.first { it.zoneName == "OCC-Z2" }
        assertEquals(2, z.total)
        assertEquals(1, z.occupied, "only the live-UL location counts as occupied")
        assertEquals("empty", z.locations.first { it.name == "OCC-E" }.state)
        assertEquals("occupied", z.locations.first { it.name == "OCC-F" }.state)
    }

    /**
     * SC21: storage_locations.capacity (nullable, Phase-B honest metadata, V309) drives slot-level
     * utilization. One location carries capacity=4 with 2 live unit loads; a second location has
     * no capacity set (null) but still carries 1 live unit load. Only the capacitied location may
     * contribute to capacitySlots/usedSlots — the null-capacity location's unit load must not leak
     * into usedSlots, otherwise utilization would be understated by a location that never opted in
     * to capacity tracking.
     */
    @Test @TestTransaction
    fun `totals aggregate capacity and used slots only over locations that carry a capacity`() {
        val zoneId = (em.createNativeQuery(
            "INSERT INTO karyo.zones (version, created, modified, name) VALUES (0, now(), now(), 'OCC-Z3') RETURNING id"
        ).singleResult as Number).toLong()
        val ltId = ensureLocationType()
        val areaId = ensureArea()
        // capacity=4, 2 live unit loads
        val capLoc = insLoc(zoneId, ltId, areaId, "OCC-CAP", 0, lock = 0, capacity = 4)
        insUnitLoad(capLoc, "OCC-CAP", labelSuffix = "-1")
        insUnitLoad(capLoc, "OCC-CAP", labelSuffix = "-2")
        // capacity=null, 1 live unit load -- must be excluded from capacitySlots/usedSlots
        val noCapLoc = insLoc(zoneId, ltId, areaId, "OCC-NOCAP", 1, lock = 0, capacity = null)
        insUnitLoad(noCapLoc, "OCC-NOCAP")

        val resp = service.build(0L)

        assertEquals(4, resp.totals.capacitySlots)
        assertEquals(2, resp.totals.usedSlots)
        assertEquals(1, resp.totals.locationsWithCapacity)
        assertEquals(0.5, resp.totals.utilization)

        val z = resp.zones.first { it.zoneName == "OCC-Z3" }
        assertEquals(4, z.capacitySlots)
        assertEquals(2, z.usedSlots)
        val capCell = z.locations.first { it.name == "OCC-CAP" }
        assertEquals(4, capCell.capacity)
        assertEquals(2, capCell.unitLoadCount)
        val noCapCell = z.locations.first { it.name == "OCC-NOCAP" }
        assertNull(noCapCell.capacity)
        assertEquals(1, noCapCell.unitLoadCount)
    }

    /** utilization is null (not zero) when no location in the tenant has a capacity set. */
    @Test @TestTransaction
    fun `utilization is null when no location has a capacity set`() {
        val zoneId = (em.createNativeQuery(
            "INSERT INTO karyo.zones (version, created, modified, name) VALUES (0, now(), now(), 'OCC-Z4') RETURNING id"
        ).singleResult as Number).toLong()
        val ltId = ensureLocationType()
        val areaId = ensureArea()
        insLoc(zoneId, ltId, areaId, "OCC-NC1", 0, lock = 0, capacity = null)

        val resp = service.build(0L)

        assertEquals(0, resp.totals.capacitySlots)
        assertEquals(0, resp.totals.usedSlots)
        assertEquals(0, resp.totals.locationsWithCapacity)
        assertNull(resp.totals.utilization)
    }

    // --- minimal seed helpers (corrected for actual schema — no client_id on zones/location_types/areas/unit_load_types) ---

    /** location_types has no client_id column — reuse any row or create a minimal one. */
    private fun ensureLocationType(): Long =
        (em.createNativeQuery("SELECT id FROM karyo.location_types LIMIT 1").resultList.firstOrNull() as Number?)?.toLong()
            ?: (em.createNativeQuery(
                "INSERT INTO karyo.location_types (version, created, modified, name) VALUES (0, now(), now(), 'OCC-LT') RETURNING id"
            ).singleResult as Number).toLong()

    /** areas has no client_id column — reuse any row or create a minimal one. */
    private fun ensureArea(): Long =
        (em.createNativeQuery("SELECT id FROM karyo.areas LIMIT 1").resultList.firstOrNull() as Number?)?.toLong()
            ?: (em.createNativeQuery(
                "INSERT INTO karyo.areas (version, created, modified, name) VALUES (0, now(), now(), 'OCC-AR') RETURNING id"
            ).singleResult as Number).toLong()

    private fun insLoc(
        zoneId: Long, ltId: Long, areaId: Long, name: String, idx: Int, lock: Int, capacity: Int? = null,
    ): Long =
        (em.createNativeQuery(
            "INSERT INTO karyo.storage_locations (version, created, modified, client_id, name, " +
            "location_type_id, area_id, zone_id, x_pos, y_pos, z_pos, allocation, order_index, lock_type, capacity) " +
            "VALUES (0, now(), now(), 0, ?1, ?2, ?3, ?4, 0, 0, 0, 0, ?5, ?6, ?7) RETURNING id"
        ).setParameter(1, name).setParameter(2, ltId).setParameter(3, areaId).setParameter(4, zoneId)
         .setParameter(5, idx).setParameter(6, lock).setParameter(7, capacity).singleResult as Number).toLong()

    private fun insUnitLoad(locId: Long, locName: String, state: Int = 300, labelSuffix: String = "") {
        // unit_loads.storage_location_name is NOT NULL — must be included; label_id is unique, so a
        // second unit load on the same location (e.g. multiple live ULs for capacity math) needs a
        // distinct suffix.
        em.createNativeQuery(
            "INSERT INTO karyo.unit_loads (version, created, modified, client_id, label_id, " +
            "storage_location_id, storage_location_name, unit_load_type_id, state) " +
            "VALUES (0, now(), now(), 0, ?1, ?2, ?3, ?4, ?5)"
        ).setParameter(1, "OCC-UL-$locId$labelSuffix").setParameter(2, locId).setParameter(3, locName)
         .setParameter(4, ensureUnitLoadType()).setParameter(5, state).executeUpdate()
    }

    /** unit_load_types has no client_id column — reuse any row or create a minimal one. */
    private fun ensureUnitLoadType(): Long =
        (em.createNativeQuery("SELECT id FROM karyo.unit_load_types LIMIT 1").resultList.firstOrNull() as Number?)?.toLong()
            ?: (em.createNativeQuery(
                "INSERT INTO karyo.unit_load_types (version, created, modified, name) VALUES (0, now(), now(), 'OCC-ULT') RETURNING id"
            ).singleResult as Number).toLong()
}
