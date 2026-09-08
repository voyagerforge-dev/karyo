package com.karyo.inventory.api.spi

/**
 * In-process unit-load move contract. Implemented by inventory-core and consumed by the
 * tasks module to execute a putaway/move completion without a cross-service REST call.
 *
 * Delegates to the existing `UnitLoadService.transferToLocation` — the same mechanism
 * the receive→putaway workflow and `UnitLoadTransferredObserver` already exercise — so a
 * move through this SPI fires `UnitLoadTransferredEvent`, which the layout module's
 * observer turns into the source/destination allocation update. The tasks module never
 * touches inventory entities or layout allocation directly.
 */
interface UnitLoadMover {

    /**
     * Moves the unit load [unitLoadId] to [destinationLocationId]/[destinationLocationName],
     * firing the transfer event (and thus the layout allocation update). Scoped to the
     * current tenant by the implementation.
     */
    fun move(unitLoadId: Long, destinationLocationId: Long, destinationLocationName: String)

    /**
     * Appends `"-" + unitLoadId` to the unit load's `labelId`, idempotent via `endsWith` (so a
     * second call, e.g. a re-dispatch or a defensive retry, is a no-op). Corpus-faithful (S6):
     * the suffix frees the original barcode for reuse once the container ships out. `labelId`
     * is NOT NULL VARCHAR(255) UNIQUE globally; embedding the unit load's own id in the suffix
     * preserves that uniqueness without a migration. Returns the (possibly unchanged) label.
     * Scoped to the current tenant by the implementation.
     */
    fun appendDispatchSuffix(unitLoadId: Long): String
}
