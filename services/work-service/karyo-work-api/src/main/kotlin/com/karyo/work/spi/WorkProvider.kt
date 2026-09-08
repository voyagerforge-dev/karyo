package com.karyo.work.spi

import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.vo.WorkType

/**
 * Implemented once per source module. Exposes that module's open work as [WorkItem]s and
 * handles claim/release by writing through to the module's own lifecycle (source of truth).
 * Completion is NOT here — operators confirm via each module's existing confirm endpoint.
 */
interface WorkProvider {
    /** Which work-types this provider serves. */
    fun workTypes(): Set<WorkType>

    /** OPEN (claimable) items for the current tenant, narrowed by [filter], already prio-sorted desc. */
    fun listOpen(filter: WorkFilter): List<WorkItem>

    /** Items currently CLAIMED by [operatorId] for the current tenant. */
    fun listClaimedBy(operatorId: String): List<WorkItem>

    /**
     * Atomically claim [ref] for [operatorId] within the owning module's transaction.
     * @throws com.karyo.work.exception.WorkClaimConflictException if already claimed/taken.
     */
    fun claim(ref: WorkRef, operatorId: String): WorkItem

    /** Return [ref] to the pool (only the claimer or a manager; enforced by caller authz). */
    fun release(ref: WorkRef, operatorId: String, asManager: Boolean = false)
}
