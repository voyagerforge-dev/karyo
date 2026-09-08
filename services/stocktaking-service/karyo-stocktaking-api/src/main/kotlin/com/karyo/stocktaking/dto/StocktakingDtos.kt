package com.karyo.stocktaking.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

// ── Request ───────────────────────────────────────────────────────────────────

/** Initiates a count session.
 *
 *  **CYCLE (the default -- [type] null or `"CYCLE"`):** provide explicit location ids, an area id
 *  (expanded to its locations), a [locationNamePattern] (SQL LIKE, caller supplies its own
 *  `%`/`_` wildcards -- e.g. `"A-01-%"`), or any combination -- all three sources are unioned and
 *  deduped. An empty scope (nothing supplied, or a pattern that matches nothing) is rejected as
 *  [com.karyo.stocktaking.exception.StocktakingException.InvalidState]. An ALL-wildcard pattern
 *  (`"%"`, `"_"`, `"%_%"` ...) is a 422: it matches every location, i.e. a full inventory, which
 *  must be asked for as `type=END_OF_PERIOD` rather than smuggled through a CYCLE count.
 *
 *  **END_OF_PERIOD (St5):** the full-inventory count. The scope is every location the tenant
 *  owns (a shared `client_id = 0` location is never included) and is NOT caller-selectable --
 *  supplying [locationIds]/[areaId]/[locationNamePattern], or a [scopeStrategy] other than
 *  `FULL_WAREHOUSE`, is a 422 rather than a silently-ignored input.
 *
 *  [campaignId] is optional (St1) -- validated against the campaign's existence/tenant, state
 *  (must be OPEN) and type, which must equal this request's [type], before the session is
 *  stamped.
 *
 *  [scopeStrategy] names the [com.karyo.stocktaking.spi.CountScopeStrategy] to apply
 *  (`"EXPLICIT"` when omitted); an unknown name is a 422, never a fallback. */
data class StartCountRequest(
    val locationIds: List<Long> = emptyList(),
    val areaId: Long? = null,
    val locationNamePattern: String? = null,
    val blindCount: Boolean = true,
    val campaignId: Long? = null,
    /** `null`/`"CYCLE"` (default) or `"END_OF_PERIOD"`; anything else is a 422.
     *  @see com.karyo.stocktaking.vo.CountType */
    val type: String? = null,
    /** Scope-strategy name; defaults to `"EXPLICIT"` for a CYCLE count and is forced to
     *  `"FULL_WAREHOUSE"` for END_OF_PERIOD. */
    val scopeStrategy: String? = null,
)

// ── Campaign (St1) ───────────────────────────────────────────────────────────

/** POST /count-campaigns body. [type] defaults to CYCLE; the only other legal value is
 *  END_OF_PERIOD (validated against [com.karyo.stocktaking.vo.CountType]). [name] mirrors the
 *  `count_campaigns.name VARCHAR(100) NOT NULL` column -- bean validation (400) catches a
 *  blank/oversized name before it can surface as an unmapped Hibernate 500. */
data class CreateCampaignRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val type: String? = null,
)

/** The operator's counted amount for a single [com.karyo.stocktaking.domain.model.CountLine]. */
data class CountInput(
    val lineId: Long,
    val countedAmount: BigDecimal,
)

/** REST body for POST /count-orders/{id}/count — wraps the per-line inputs. */
data class SubmitCountRequest(
    val lines: List<CountInput> = emptyList(),
)

/** REST body for POST /count-orders/{id}/unit-loads/missing (St4). */
data class UnitLoadMissingRequest(
    val unitLoadId: Long,
)

// ── Views ─────────────────────────────────────────────────────────────────────

/**
 * Blind entry projection for operators — omits [plannedAmount] and [countedAmount] so the
 * operator cannot see the expected quantity before entering their count.
 * Returned by GET /count-orders/{id}?view=entry.
 */
data class CountEntryView(
    val id: Long,
    val orderNumber: String,
    val locationName: String,
    val lines: List<CountEntryLine>,
)

/** A single line in the blind [CountEntryView]. No amounts exposed.
 *  [unitLoadLabel] (St4) is the FE grouping key (`?? 'Loose stock'` for a null/pre-migration
 *  line); [unitLoadId] is what the FE sends back to POST .../unit-loads/missing. [counted]
 *  signals a line already resolved (e.g. by the missing-op) without leaking the actual counted
 *  amount -- the FE locks that line's input. */
data class CountEntryLine(
    val lineId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val serialNumber: String?,
    val unitLoadId: Long? = null,
    val unitLoadLabel: String? = null,
    val counted: Boolean = false,
)

data class CountSessionView(
    val id: Long,
    val sessionNumber: String,
    val type: String,
    val state: Int,
    val orders: List<CountOrderView>,
    /** Owning [com.karyo.stocktaking.domain.model.CountCampaign] id, or null (St1). */
    val campaignId: Long? = null,
    /**
     * Names of the locations an END_OF_PERIOD start deliberately did NOT generate an order for
     * -- reserved stock, or an existing non-count lock (St5). Always empty for a CYCLE session,
     * which still refuses the whole start on reserved stock.
     *
     * **Not persisted, by design** -- there is no `count_sessions.skipped_locations` column and
     * no migration for one. This is the start call's *report* of what it walked past; it is
     * therefore populated only on the response to `POST /count-sessions` and is empty on every
     * subsequent `GET`. Persisting it would mean a new table (a session skips N locations) for
     * data whose only consumer is the operator reading the start confirmation -- deliberately
     * deferred rather than half-built.
     */
    val skippedLocations: List<String> = emptyList(),
)

/**
 * Lightweight per-session projection for GET /count-sessions -- an order-state rollup only
 * (counts, never the nested orders→lines graph [CountSessionView] carries). The previous
 * unpaginated `GET /count-sessions` returned every session's full graph (1+S+O queries,
 * VIEWER-reachable) -- a warehouse-scale OOM risk (defect-burndown row 8). `GET /count-sessions/
 * {id}` keeps returning the full [CountSessionView]; this projection is for the list only, and
 * is also what `POST /count-sessions` now returns (built in memory from the orders just
 * generated, no re-query -- see [com.karyo.stocktaking.service.StocktakingService.startCount]).
 *
 * [skippedLocations] carries the same not-persisted, start-response-only semantics documented on
 * [CountSessionView.skippedLocations] -- populated by `startCount`, empty on every list/GET read.
 *
 * [skippedLocationIds] is the machine-usable parallel to [skippedLocations] (same order, same
 * index-for-index correspondence, same start-response-only lifetime) -- a name alone gave a
 * caller nothing to act on. It exists to drive the sanctioned remediation flow for a full
 * inventory that skipped a reserved (or already-locked) location: once that reservation clears,
 * start a CYCLE session naming these ids, with `campaignId` set to the SAME END_OF_PERIOD
 * campaign the original start used -- the campaign-type gate
 * ([com.karyo.stocktaking.service.StocktakingService]'s private `requireCampaignAcceptsStart`)
 * allows a CYCLE session under an END_OF_PERIOD campaign one-way for exactly this reason, and the
 * campaign rollup then covers the remediated location. The location still cannot be counted while
 * its reservation is live -- that guard is untouched; this is only the route to file the
 * remediating count under the right campaign afterwards.
 */
data class CountSessionSummaryView(
    val id: Long,
    val sessionNumber: String,
    val type: String,
    val state: Int,
    val campaignId: Long? = null,
    val orderCount: Int,
    val countedCount: Int,
    val finishedCount: Int,
    val skippedLocations: List<String> = emptyList(),
    val skippedLocationIds: List<Long> = emptyList(),
)

data class CountOrderView(
    val id: Long,
    val orderNumber: String,
    val sessionId: Long,
    val locationId: Long,
    val locationName: String,
    val state: Int,
    val lines: List<CountLineView>,
)

/** A full line view including [plannedAmount] — returned by startCount.
 *  A blind count-entry projection (omitting plannedAmount) is added in Task 10.
 *  [unitLoadId]/[unitLoadLabel] (St4) mirror [com.karyo.stocktaking.domain.model.CountLine]'s
 *  snapshot -- null for pre-migration lines. */
data class CountLineView(
    val id: Long,
    val stockUnitId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val plannedAmount: BigDecimal,
    val countedAmount: BigDecimal?,
    val state: Int,
    val unitLoadId: Long? = null,
    val unitLoadLabel: String? = null,
)

// ── Campaign views (St1) ─────────────────────────────────────────────────────

/** Plain campaign view -- returned by POST/GET /count-campaigns (list form, no rollup). */
data class CountCampaignView(
    val id: Long,
    val campaignNumber: String,
    val name: String,
    val type: String,
    val state: Int,
    val started: java.time.Instant?,
    val ended: java.time.Instant?,
)

/** Order-state bucket for [CountCampaignRollupView.ordersByState]. Every field is present
 *  (defaulted to 0), matching the field names on [com.karyo.stocktaking.vo.CountOrderState]. */
data class OrdersByStateView(
    val generated: Long = 0,
    val counted: Long = 0,
    val finished: Long = 0,
    val cancelled: Long = 0,
)

/** Detail view -- returned by GET /count-campaigns/{id}. Adds the rollup: session count,
 *  order-state buckets, and discrepancy-line count across every session under the campaign. */
data class CountCampaignRollupView(
    val id: Long,
    val campaignNumber: String,
    val name: String,
    val type: String,
    val state: Int,
    val started: java.time.Instant?,
    val ended: java.time.Instant?,
    val sessions: Long,
    val ordersByState: OrdersByStateView,
    val discrepancyLines: Long,
)
