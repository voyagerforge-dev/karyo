package com.karyo.stocktaking.spi

/** Resolves which locations to count for a given [CountScopeRequest].
 *
 *  **Selection is BY NAME, not by priority (St5).** The caller picks the strategy explicitly —
 *  `StartCountRequest.scopeStrategy` for a cycle count, forced to `"FULL_WAREHOUSE"` for an
 *  END_OF_PERIOD count — and an unknown name is a 422, never a fallback. A custom strategy is
 *  therefore adopted by *asking for it by name*, not by out-ranking the built-ins.
 *
 *  This replaces the original "lowest priority wins" contract, which stopped being safe the
 *  moment a second built-in existed: `FullWarehouseScope` (priority 100) outranks
 *  [com.karyo.stocktaking.service.ExplicitLocationScope] (`Int.MAX_VALUE`), so an unnamed
 *  resolve would silently turn every plain cycle count into a warehouse-wide freeze. */
interface CountScopeStrategy {
    /** Tie-break among strategies answering to the SAME [name] (lower wins) — that, and the
     *  ordering of the deliberately-unnamed resolve, which no production caller uses. It is NOT
     *  a way to displace a built-in: see the interface KDoc, selection is by [name]. */
    val priority: Int
    /** The strategy name this answers to — the actual selector. Built-ins: "EXPLICIT",
     *  "FULL_WAREHOUSE". */
    val name: String
    fun resolveLocations(request: CountScopeRequest, clientId: Long): List<Long>
}

/** Input to [CountScopeStrategy.resolveLocations]. Callers may supply explicit location ids,
 *  an area id whose locations will be expanded, a location-name pattern, or any combination —
 *  the built-in [com.karyo.stocktaking.service.ExplicitLocationScope] unions and dedups all
 *  three sources.
 *
 *  [locationNamePattern] is a SQL `LIKE` pattern: the caller supplies its own `%`/`_`
 *  wildcards (e.g. `"A-01-%"`), matched case-sensitively against
 *  [com.karyo.layout.domain.model.StorageLocation.name] via a bound JPQL parameter — this is
 *  behavioral parity with legacy myWMS's location-name pattern filter. (A from→to contiguous
 *  range mode was the original register premise for this row, but no such mode exists in
 *  myWMS — the WORKLIST entry is being corrected to match.) Default null keeps this field
 *  source-compatible with existing callers.
 *
 *  Note the caller-side guard this type does NOT itself apply: `StartCountRequest` refuses an
 *  all-wildcard pattern (422) before a CYCLE count ever reaches a strategy, because such a pattern
 *  is the full-warehouse scope in disguise. A strategy invoked directly is unguarded. */
data class CountScopeRequest(
    val locationIds: List<Long> = emptyList(),
    val areaId: Long? = null,
    val locationNamePattern: String? = null,
)
