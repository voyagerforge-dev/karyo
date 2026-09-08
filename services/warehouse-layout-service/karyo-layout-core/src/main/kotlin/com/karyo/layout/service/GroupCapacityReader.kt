package com.karyo.layout.service

import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.StorageLocationRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Computes the [LocationFinderService] built-in field/section group-capacity check
 * (locations-layout sprint Task 7, L4): myWMS's `LocationType.fieldLiftingCapacity`/
 * `sectionLiftingCapacity` — a cap on the TOTAL weight resting across every
 * [StorageLocation] sharing a candidate's (area, rack, field) or (area, section) group, not
 * just the single candidate. Split out of [LocationFinderService] to keep that class's
 * methods within the project's Detekt size limits — same reasoning as [AreaOccupancyReader]'s
 * KDoc on why this bean is `public` (Kotlin has no package-private visibility) but unexported
 * from `karyo-layout-api`.
 *
 * **Batching:** ONE group-membership query for the union of every distinct area among the
 * candidates that actually need a check ([StorageLocationRepository.findGroupMembersByAreaIds]),
 * and ONE weight lookup ([StockUnitLookup.grossWeightByLocationIds]) for the union of every
 * member location id across every group — never per-candidate or per-group.
 *
 * **Group boundary:** (rack, field) or section ALONE is not the group key — the same rack/field
 * name in a DIFFERENT [com.karyo.layout.domain.model.Area] is a different group (racks are
 * commonly re-numbered per area/site). A location with a blank rack/field (or section) is **not
 * part of any group on that axis at all** — [GroupCapacities.allows] skips that axis' cap check
 * entirely for such a candidate (full exemption, per the sprint's adjudicated null-group-field
 * rule), rather than degrading to a lone-candidate cap-vs-incoming check. This is distinct from
 * the weight-degradation gap below: a candidate with a real rack/field but zero KNOWN occupancy
 * weight still gets the cap-vs-incoming check; a candidate with NO rack/field gets no check.
 *
 * **Weight degradation (honest gap, KDoc'd per the sprint's global constraints):** occupied
 * weight comes from [StockUnitLookup.grossWeightByLocationIds], which sums
 * [com.karyo.inventory.domain.model.UnitLoad.weight] — the SAME field the incoming
 * [com.karyo.layout.spi.LocationFinderRequest.weight] is itself sourced from. A unit load that
 * was never weighed contributes ZERO to the group sum. **When no weight data exists anywhere in
 * the group, this check degrades to cap-vs-incoming-UL-weight only** — it never blocks a
 * candidate on occupancy it cannot see, it just can no longer account for what else is already
 * in the group.
 */
@ApplicationScoped
class GroupCapacityReader(
    private val locationRepository: StorageLocationRepository,
    private val stockUnitLookup: StockUnitLookup,
) {

    /**
     * Resolves per-location group-occupied-weight for every candidate in [candidates] whose
     * [com.karyo.layout.domain.model.LocationType] defines a field or section cap. Candidates
     * whose type defines NEITHER cap need no group lookup at all — the common case for a
     * freshly migrated DB (every existing [com.karyo.layout.domain.model.LocationType] row
     * carries neither column) — and cost zero queries.
     */
    fun resolve(candidates: List<StorageLocation>): GroupCapacities {
        val needing = candidates.filter {
            it.locationType.fieldLiftingCapacity != null || it.locationType.sectionLiftingCapacity != null
        }
        if (needing.isEmpty()) return GroupCapacities(emptyMap(), emptyMap())

        val areaIds = needing.map { it.area.id!! }.distinct()
        val members = toRefs(locationRepository.findGroupMembersByAreaIds(areaIds))

        val fieldGroups = members
            .filter { it.rack != null && it.field != null }
            .groupBy { FieldKey(it.areaId, it.rack!!, it.field!!) }
        val sectionGroups = members
            .filter { it.section != null }
            .groupBy { SectionKey(it.areaId, it.section!!) }

        val allMemberIds = (fieldGroups.values + sectionGroups.values)
            .flatten()
            .map { it.locationId }
            .toSet()
        val weightByLocation = if (allMemberIds.isEmpty()) {
            emptyMap()
        } else {
            stockUnitLookup.grossWeightByLocationIds(allMemberIds)
        }

        return GroupCapacities(
            fieldGroupWeightByLocationId = expandGroupWeights(fieldGroups.values, weightByLocation),
            sectionGroupWeightByLocationId = expandGroupWeights(sectionGroups.values, weightByLocation),
        )
    }

    /** For each group, sums its members' weight ONCE then maps that total back onto every
     * member location id — so [GroupCapacities.allows] is a plain map lookup per candidate. */
    private fun expandGroupWeights(
        groups: Collection<List<LocationGroupRef>>,
        weightByLocation: Map<Long, BigDecimal>,
    ): Map<Long, BigDecimal> =
        groups.flatMap { members ->
            val total = members.sumOf { weightByLocation[it.locationId] ?: BigDecimal.ZERO }
            members.map { it.locationId to total }
        }.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun toRefs(rows: List<Array<Any?>>): List<LocationGroupRef> = rows.map {
        LocationGroupRef(
            locationId = it[0] as Long,
            areaId = it[1] as Long,
            rack = it[2] as String?,
            field = it[3] as String?,
            section = it[4] as String?,
        )
    }

    private data class LocationGroupRef(
        val locationId: Long,
        val areaId: Long,
        val rack: String?,
        val field: String?,
        val section: String?,
    )

    private data class FieldKey(val areaId: Long, val rack: String, val field: String)
    private data class SectionKey(val areaId: Long, val section: String)
}

/**
 * Per-location group-occupied-weight, already expanded from group totals — see
 * [GroupCapacityReader.resolve]. A location id absent from either map means "no group data
 * needed/found for that axis", equivalent to zero occupied weight.
 */
data class GroupCapacities(
    val fieldGroupWeightByLocationId: Map<Long, BigDecimal>,
    val sectionGroupWeightByLocationId: Map<Long, BigDecimal>,
) {
    /**
     * True when [loc] plus [incomingWeight] fits within BOTH its field-group cap (if the
     * location type defines one AND [loc] actually has a rack+field to group by) and its
     * section-group cap (if defined AND [loc] has a section) — checked independently.
     *
     * A [loc] with a null rack/field (or null section) is **exempt** from that axis' cap
     * entirely — "not part of any group" per the sprint's adjudicated rule, not a degenerate
     * lone-candidate cap-vs-incoming check. A [loc] that DOES have a rack/field (or section) but
     * whose group's occupancy weight is unknown/zero still gets the cap-vs-incoming check (the
     * separate weight-degradation gap — see [GroupCapacityReader]'s KDoc).
     */
    fun allows(loc: StorageLocation, incomingWeight: BigDecimal): Boolean {
        loc.locationType.fieldLiftingCapacity?.let { cap ->
            if (loc.rack != null && loc.field != null) {
                val occupied = fieldGroupWeightByLocationId[loc.id] ?: BigDecimal.ZERO
                if (occupied + incomingWeight > cap) return false
            }
        }
        loc.locationType.sectionLiftingCapacity?.let { cap ->
            if (loc.section != null) {
                val occupied = sectionGroupWeightByLocationId[loc.id] ?: BigDecimal.ZERO
                if (occupied + incomingWeight > cap) return false
            }
        }
        return true
    }
}
