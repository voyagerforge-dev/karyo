package com.karyo.work.dto

import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import java.time.Instant

/** Stable cross-type identity for a unit of work, e.g. PICK:123. Routes claim/release to the owning provider. */
data class WorkRef(val type: WorkType, val sourceId: Long) {
    fun token(): String = "${type.name}:$sourceId"

    companion object {
        fun parse(token: String): WorkRef {
            val parts = token.split(":")
            require(parts.size == 2) { "Invalid work ref: $token" }
            return WorkRef(WorkType.valueOf(parts[0]), parts[1].toLong())
        }
    }
}

/** Unified, live read-shape returned by providers. Never persisted. */
data class WorkItem(
    val ref: WorkRef,
    val workType: WorkType,
    val priority: Int,
    val state: WorkState,
    val claimedBy: String?,
    val zone: String?,
    val primaryLocation: String?,
    val destination: String?,
    val summary: String,
    val createdAt: Instant,
    /**
     * The layout-module id backing [primaryLocation], when the source provider tracks one
     * (locations-layout sprint Task 8 — see `TransportWorkProvider`/`CountWorkProvider`).
     * `PickWorkProvider` has no location concept at all yet (`primaryLocation` is already
     * always null there too) so this stays null for PICK items — a documented gap, not a
     * bug: see the WORKLIST follow-up on wiring pick-order location. Null here means the
     * item is excluded whenever `?workingAreaId=` is applied, deliberately: an operator
     * scoped to a working area shouldn't be shown work with no known location.
     */
    val primaryLocationId: Long? = null,
    /**
     * The backing location's `orderIndex` (walking-order position), when the source provider
     * tracks one — St6, stocktaking-block sprint. Only [com.karyo.stocktaking.messaging.CountWorkProvider]
     * populates this today (via `LocationLockPort.orderIndexFor`); every other provider leaves
     * it at the default `null`, which stays source-compatible with every pre-existing
     * `WorkItem(...)` call site. Consumed only by
     * [com.karyo.work.service.TravelPathDispatchStrategy] — the built-in
     * [com.karyo.work.service.StrictPriorityDispatchStrategy] ignores it entirely, so a `null`
     * here has zero effect unless the operator has opted into `TRAVEL_PATH` dispatch via
     * `karyo.work.dispatch-strategy`.
     */
    val travelOrder: Int? = null,
)

/** Optional narrowing of the pool. Null fields mean "no constraint". */
data class WorkFilter(
    val types: Set<WorkType>? = null,
    val zones: Set<String>? = null,
    /**
     * CONTINGENT filter (Task 8): when set, [com.karyo.work.service.WorkDispatchService]
     * narrows the pool to items whose [WorkItem.primaryLocationId] falls within this
     * working area's resolved locations (via `WorkingAreaLookup`); items with a null
     * `primaryLocationId` are excluded, not passed through.
     */
    val workingAreaId: Long? = null,
)
