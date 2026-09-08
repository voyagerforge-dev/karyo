package com.karyo.inventory.exception

import com.karyo.common.exception.KaryoException
import java.math.BigDecimal

sealed class InventoryException(message: String) : KaryoException(message) {
    class NotFound(val entityType: String, val entityId: Any) :
        InventoryException("$entityType with id $entityId not found")
    class InsufficientStock(val available: BigDecimal, val requested: BigDecimal) :
        InventoryException("Insufficient stock: available=$available, requested=$requested")
    class StockLocked(val stockUnitId: Long, val lockType: Int) :
        InventoryException("StockUnit $stockUnitId is locked with lockType=$lockType")
    class InvalidStateTransition(val entityId: Long, val currentState: Int, val targetState: Int) :
        InventoryException("Invalid state transition for entity $entityId: $currentState -> $targetState")
    class ConcurrencyConflict(msg: String) : InventoryException(msg)
    class ValidationFailed(msg: String) : InventoryException(msg)
    class HasDependents(val entityType: String, val entityId: Long, val dependentType: String) :
        InventoryException("$entityType $entityId has associated $dependentType and cannot be deleted")
    class DuplicateName(val entityType: String, val name: String) :
        InventoryException("$entityType with name '$name' already exists")

    /** The acting principal's kind forbids the operation outright (403) — e.g. changeClient is OPS-only. */
    class Forbidden(msg: String) : InventoryException(msg)

    /** A caller-supplied target entity is semantically unusable (422) — e.g. SYS or a nonexistent client. */
    class InvalidTarget(msg: String) : InventoryException(msg)

    /** The subject is encumbered — reservations or open picks — and the operation refuses totally (409). */
    class Encumbered(msg: String) : InventoryException(msg)

    /**
     * A required facility-level configuration is missing (409) — e.g. `transferToClearing`
     * with no location flagged `isClearing`. Distinct from [ValidationFailed] (400): this is
     * not bad caller input, it's the system not yet being set up for the operation.
     */
    class NotConfigured(msg: String) : InventoryException(msg)

    /**
     * D1 (user decision 2026-07-25): cross-owner stock movement is refused outright (409) —
     * `changeClient` is the single sanctioned ownership-transfer path.
     */
    class CrossOwner(msg: String) : InventoryException(msg)
}
