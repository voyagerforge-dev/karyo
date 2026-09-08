package com.karyo.inventory.api.spi

/**
 * Row 18: which purge candidates are still referenced by live work.
 *
 * `stock_units.unit_load_id` is a real foreign key, and so is `unit_loads.carrier_unit_load_id`
 * (self-referencing, backing the nested-container/carrier feature) -- fix round 1 (Critical 2)
 * corrected an earlier version of this KDoc that claimed the former was the ONLY one. Every
 * OTHER reference to a stock unit or unit load id across the modules is a bare Long with no
 * constraint behind it, so a hard delete of those would strand them silently rather than
 * failing. This SPI is how each module answers for its own references before anything is
 * removed; the two real foreign keys are guarded structurally, in-query, by the candidate
 * selection itself (see `StockUnitRepository.findPurgeCandidates`'s FK-respecting delete order
 * and `UnitLoadRepository.findEmptyTerminal`'s carrier exclusion), not through this SPI.
 *
 * Historical references deliberately do NOT block: journal rows, terminal picks' SOURCE stock
 * unit, goods receipt lines, and closed count lines are records of what happened, and a purge
 * turning their id into a tombstone is exactly what a purge means. Live work does block: an open
 * pick's source stock unit, a pick's TARGET stock unit (unconditionally -- see
 * `FulfillmentPurgeBlockerLookup`'s KDoc, fix round 1 Critical 1), an order line reservation, an
 * open transport order, a pick order's target container (unconditionally), a shipping unit
 * (unconditionally), or a count line in an open session.
 *
 * Declared in inventory and implemented by the foreign cores, the same direction as [OpenPickGuard]
 * and [ReservationRefMover], so no module depends on another module's core. Both methods are
 * batched and take [clientId] explicitly: this runs from a `@Scheduled` tenant loop where an
 * ambient TenantContext read would silently see clientId 0.
 */
interface PurgeBlockerLookup {
    fun blockedStockUnitIds(candidateIds: Collection<Long>, clientId: Long): Set<Long>
    fun blockedUnitLoadIds(candidateIds: Collection<Long>, clientId: Long): Set<Long>
}
