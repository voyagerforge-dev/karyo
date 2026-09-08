package com.karyo.layout.spi

/**
 * Read seam over `WorkingArea` (L5, locations-layout sprint Task 8) — myWMS's
 * `LOSWorkingArea`: a named set of `LocationCluster`s modeling an operator workstation's
 * reach. Consumed by the work module's CONTINGENT `?workingAreaId=` filter on
 * `GET /api/v1/work/available`.
 *
 * Deliberately NO user binding — myWMS has none either. An operator does not have a
 * standing working area; they choose one per request (or per shift, client-side), and the
 * server just resolves membership → locations for whichever id is passed. Persisting a
 * per-user default is a future UX affordance, not a domain rule.
 */
interface WorkingAreaLookup {
    /**
     * Union of location ids across every cluster that is a member of [workingAreaId].
     * A working area with no assigned clusters (or an id that does not exist) resolves to
     * an empty set — callers that need to distinguish "unknown id" from "empty area" must
     * call [exists] first (the work module's filter does exactly this to produce its 400).
     */
    fun locationIdsFor(workingAreaId: Long): Set<Long>

    fun exists(id: Long): Boolean
}
