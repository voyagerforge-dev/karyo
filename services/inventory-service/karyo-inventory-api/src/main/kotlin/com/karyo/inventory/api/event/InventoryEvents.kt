package com.karyo.inventory.api.event

import java.math.BigDecimal

data class StockUnitStateChangedEvent(
    val stockUnitId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val unitLoadId: Long,
    val oldState: Int,
    val newState: Int,
    val amount: BigDecimal,
    val locationId: Long,
    val locationName: String,
)

data class StockUnitAmountChangedEvent(
    val stockUnitId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val oldAmount: BigDecimal,
    val newAmount: BigDecimal,
    val changeAmount: BigDecimal,
    val recordType: Int,
    val activityCode: String?,
    val locationId: Long,
    val locationName: String,
)

data class StockUnitDeletedEvent(
    val stockUnitId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val locationId: Long,
)

data class UnitLoadTransferredEvent(
    val unitLoadId: Long,
    val labelId: String,
    val fromLocationId: Long,
    val fromLocationName: String,
    val toLocationId: Long,
    val toLocationName: String,
    val stockUnitCount: Int,
)

data class UnitLoadClientChangedEvent(
    val unitLoadId: Long,
    val labelId: String,
    val oldClientId: Long,
    val newClientId: Long,
    val stockUnitCount: Int,
)

data class UnitLoadTrashedEvent(
    val unitLoadId: Long,
    val labelId: String,
    val locationId: Long,
    val stockUnitIds: List<Long>,
)

/**
 * Bulk Allocation Sprint C: the exact inverse of [UnitLoadTrashedEvent], fired by
 * `StockPicker.reviveDrainedContainer` when a unit load that was tombstoned DELETABLE purely
 * because it went empty is brought back to receive returned goods (a consolidation-shipment
 * cancel putting picked stock back on its batch cart).
 *
 * Deliberately a SEPARATE event rather than a reuse of
 * [UnitLoadTransferredEvent] with `fromLocationId = 0`: nothing moved between locations here, the
 * unit load never left its slot. What has to be undone is precisely the allocation release
 * [UnitLoadTrashedEvent]'s layout observer performed (-100), so the inverse carries the same
 * shape and its observer applies the same magnitude with the opposite sign. Without it the
 * location's `allocation` drifts down by 100 per revived unit load (the defect-burndown-4 row 14
 * class of bug, in reverse).
 */
data class UnitLoadRevivedEvent(
    val unitLoadId: Long,
    val labelId: String,
    val locationId: Long,
    val stockUnitIds: List<Long>,
)

data class LockChangedEvent(
    val entityType: String,
    val entityId: Long,
    val oldLock: Int,
    val newLock: Int,
    val locationId: Long,
)

/**
 * Row 15: a stock unit's packaging-unit classification changed. The amount is deliberately not
 * carried: this operation never touches it, so a consumer that sees this event knows the
 * quantity is exactly what the last amount event said it was.
 */
data class PackagingUnitChangedEvent(
    val entityType: String,
    val entityId: Long,
    val oldPackagingUnitId: Long?,
    val newPackagingUnitId: Long?,
    val locationId: Long,
)

/**
 * Row 18: a DELETABLE stock unit was hard-deleted by the retention-window reaper
 * (`StockPurgeService`). Unlike [StockUnitDeletedEvent] (the soft-delete flip to DELETABLE),
 * this is the physical row removal -- the stock unit no longer exists after this fires.
 */
data class StockUnitPurgedEvent(
    val stockUnitId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val unitLoadId: Long,
    val amount: BigDecimal,
)

/**
 * Row 18: a terminal, now-empty unit load was hard-deleted by the retention-window reaper
 * (`StockPurgeService`), once every stock unit it carried was itself purged.
 */
data class UnitLoadPurgedEvent(
    val unitLoadId: Long,
    val labelId: String,
)
