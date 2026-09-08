package com.karyo.layout.spi

/**
 * In-process existence check for a [com.karyo.layout.domain.model.StorageStrategy], consumed
 * by other modules (the orders module's receive-line validation, inbound-completion row 7
 * residual) instead of a cross-service REST client — same api-only seam as
 * [FixAssignmentLookup]/[StagingLocationLookup].
 *
 * Deliberately a bare existence check, not a full read: the only caller today (receive-line
 * validation) needs to reject an unknown/foreign id BEFORE writing anything; it never reads
 * the strategy's fields (that stays layout's job, via [LocationFinder]'s own tenant-scoped
 * resolution once the id reaches the finder).
 */
interface StorageStrategyLookup {
    /** True when [id] names a [com.karyo.layout.domain.model.StorageStrategy] belonging to [clientId]. */
    fun exists(id: Long, clientId: Long): Boolean
}
