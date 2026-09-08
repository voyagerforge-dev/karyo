package com.karyo.layout.exception

import com.karyo.common.exception.KaryoException
import com.karyo.layout.vo.AreaUsage
import com.karyo.layout.vo.StorageStrategySortType
import java.math.BigDecimal

sealed class LayoutException(message: String) : KaryoException(message) {
    class NotFound(entityType: String, identifier: Any) :
        LayoutException("$entityType not found: $identifier")

    class DuplicateName(entityType: String, name: String) :
        LayoutException("$entityType with name '$name' already exists")

    class InvalidLockTransition(locationId: Long, currentLock: Int, targetLock: Int) :
        LayoutException("Invalid lock transition for location $locationId: $currentLock -> $targetLock")

    class LocationInUse(locationId: Long, reason: String) :
        LayoutException("Location $locationId cannot be modified: $reason")

    class InvalidReference(entityType: String, referenceType: String, referenceId: Long) :
        LayoutException("$entityType references invalid $referenceType: $referenceId")

    class WeightLimitExceeded(locationId: Long, currentWeight: BigDecimal, proposedWeight: BigDecimal, limit: BigDecimal) :
        LayoutException("Weight limit exceeded at location $locationId: current=$currentWeight + proposed=$proposedWeight > limit=$limit")

    class ProductValidationFailed(itemDataId: Long, reason: String) :
        LayoutException("Product validation failed for itemDataId=$itemDataId: $reason")

    class InvalidUsage(usage: String) :
        LayoutException("Invalid area usage: '$usage'. Valid values: ${AreaUsage.entries.map { it.name }}")

    class HasDependents(entityType: String, id: Long, dependentType: String) :
        LayoutException("$entityType $id has associated $dependentType and cannot be deleted")

    /**
     * A LIST of ids embedded in the REQUEST BODY (not a path parameter) don't resolve —
     * e.g. `StorageArea.clusterIds`, or the ordered area-id list on `PUT .../areas`.
     * Deliberately 400 (bad input), not 404/422: unlike a single scalar reference field
     * (still `InvalidReference` -> 422, see `FixAssignmentService.validateProduct` /
     * `ItemDataAreaService.resolveProduct`) or a single required "belongs-to" parent
     * reference (still `NotFound` -> 404, see `FixAssignment.locationId` /
     * `ItemDataArea.storageAreaId`), this is a batch/list validation of caller-supplied
     * content — do NOT use this for a single scalar id field, only genuine lists.
     */
    class InvalidReferenceList(entityType: String, referenceType: String, invalidIds: List<Long>) :
        LayoutException("$entityType references unknown $referenceType ids: $invalidIds")

    /**
     * `StorageStrategy.sorts` save-time validation (locations-layout sprint Task 5). The
     * column STAYS free-text (existing rows may already hold unrecognized tokens — the
     * finder's read-time parser SKIPS those with a WARN, never throws), but a NEW write is
     * rejected outright so the mistake is caught immediately instead of silently no-op'ing
     * at find time.
     */
    class InvalidSortTokens(invalidTokens: List<String>) :
        LayoutException(
            "Invalid 'sorts' token(s): $invalidTokens. Valid values: ${StorageStrategySortType.entries.map { it.name }}"
        )
}
