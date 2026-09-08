package com.karyo.inventory.service

import com.karyo.inventory.api.dto.CreateStockUnitRequest
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.api.spi.ReceiveStockRequest
import com.karyo.inventory.api.spi.ReceivedStock
import com.karyo.inventory.api.spi.StockReceiver
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal

/**
 * Default in-process implementation of [StockReceiver], scoped to the current tenant.
 *
 * Delegates to the existing inventory services instead of reimplementing them:
 * [UnitLoadService.create] resolves-or-creates the receiving unit load,
 * [StockService.createStock] creates the INCOMING stock unit (including the
 * `strategyDate = bestBefore` FIFO rule, journal CREATED record, and outbox event),
 * [StockService.setLock] applies the caller's receive-time lock (journal +
 * LockChanged outbox event — the mechanism the domain already supports), and
 * [StockService.changeState] performs the INCOMING→ON_STOCK promotion (forward-only
 * guard, journal, StateChanged outbox event).
 */
@ApplicationScoped
class DefaultStockReceiver(
    private val stockService: StockService,
    private val unitLoadService: UnitLoadService,
    private val unitLoadTypeService: UnitLoadTypeService,
    private val unitLoadRepository: UnitLoadRepository,
    private val tenantContext: TenantContext,
    private val sequenceNumberService: SequenceNumberService,
    private val weightCalculator: UnitLoadWeightCalculator,
) : StockReceiver {

    @Transactional
    override fun receive(request: ReceiveStockRequest): ReceivedStock {
        if (request.amount <= BigDecimal.ZERO) {
            throw InventoryException.ValidationFailed("receive amount must be positive")
        }
        val unitLoad = resolveUnitLoad(request)
        val stockUnit = stockService.createStock(
            CreateStockUnitRequest(
                itemDataId = request.itemDataId,
                itemDataNumber = request.itemDataNumber,
                amount = request.amount,
                unitLoadId = unitLoad.id!!,
                lotNumber = request.lotNumber,
                serialNumber = request.serialNumber,
                packagingUnitId = request.packagingUnitId,
                bestBefore = request.bestBefore,
                state = StockState.INCOMING.code,
                activityCode = ACTIVITY_CODE,
            ),
            tenantContext,
        )
        val lockType = request.lockType
        if (lockType != null && lockType != LockType.UNLOCKED.code) {
            stockService.setLock(stockUnit.id!!, lockType, tenantContext, reason = journalReason(request.lockNote))
        }
        return ReceivedStock(
            stockUnitId = stockUnit.id!!,
            unitLoadId = unitLoad.id!!,
            unitLoadLabel = unitLoad.labelId,
        )
    }

    /**
     * Row 16 (fix round 1): [stockUnitIds] can span several unit loads (unlike the other three
     * bulk-`changeState` sites, which each operate on a single one), so the per-item
     * `changeState` call opts out of the recompute and this method tracks every DISTINCT unit
     * load actually promoted, recomputing each exactly once after the loop rather than once per
     * flipped stock unit.
     */
    @Transactional
    override fun markOnStock(stockUnitIds: List<Long>) {
        val touchedUnitLoads = linkedMapOf<Long, UnitLoad>()
        for (id in stockUnitIds) {
            try {
                val stockUnit = stockService.findByIdForWrite(id, tenantContext)
                when {
                    stockUnit.lockType != LockType.UNLOCKED.code ->
                        LOG.warnf(
                            "Skipping ON_STOCK promotion of stock unit %d: locked with lockType=%d",
                            id, stockUnit.lockType,
                        )
                    stockUnit.state >= StockState.ON_STOCK.code ->
                        LOG.warnf(
                            "Skipping ON_STOCK promotion of stock unit %d: already in state %d",
                            id, stockUnit.state,
                        )
                    else -> {
                        stockService.changeState(id, StockState.ON_STOCK.code, tenantContext, recalculateWeight = false)
                        touchedUnitLoads[stockUnit.unitLoad.id!!] = stockUnit.unitLoad
                    }
                }
            } catch (e: InventoryException.NotFound) {
                // Defensive like StockReserver.release: unit gone — skip and log.
                LOG.warnf(e, "Stock unit %d no longer exists; skipping ON_STOCK promotion", id)
            }
        }
        touchedUnitLoads.values.forEach(weightCalculator::recalculate)
    }

    /**
     * B3 reversal: soft-deletes the stock via [StockService.deleteStock], guarded by
     * myWMS's amount-equality check (`GoodsReceipt.stockIsChanged`) — any pick,
     * partial consumption or manual adjustment since receipt makes the amount
     * mismatch and refuses the whole reversal. The reserved-amount guard is
     * belt-and-braces on top of that: a reservation doesn't touch `amount`, so
     * equality alone would let a reserved-but-unpicked unit reverse and strand the
     * reservation.
     *
     * Deliberately does NOT call `StockService.trashUnitLoadIfEmpty` even when this empties the
     * unit load (task-10, defect-burndown-4, row 14 considered and rejected here): a reversal is
     * a receiving CORRECTION, not a retirement, and `AsnUlAdviceService.reopen`'s KDoc documents
     * the receiving workflow re-receiving onto the SAME unit load (same label) right after a
     * reversal; `UlAdviceFlowTest`'s "(g)" case pins this. Auto-flipping the unit load DELETABLE
     * here would permanently block that label (labels are globally unique, and a DELETABLE unit
     * load refuses reuse per this same task's guard), which is the opposite of what the receiving
     * workflow needs. [StockService.deleteStock] itself is symmetric with this choice, see its
     * KDoc, leaving the two callers who genuinely mean "this is retired" (REST `DELETE
     * /stock-units/{id}` and the cycle-count zero-branch) to opt in explicitly instead.
     */
    @Transactional
    override fun unreceive(stockUnitId: Long, expectedAmount: BigDecimal, clientId: Long) {
        val su = stockService.findByIdForWrite(stockUnitId, tenantContext)
        requireOwnedByClient(su, stockUnitId, clientId)
        requireNotAlreadyReversed(su, stockUnitId)
        requireUnchangedAndUnreserved(su, stockUnitId, expectedAmount)
        stockService.deleteStock(stockUnitId, tenantContext)
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Integrity, not authorization: the receipt's owner must own the stock it is
     * un-receiving. tenantContext's write scope (already enforced by
     * [StockService.findByIdForWrite]) is what actually authorizes the call; this
     * catches a stock unit that belongs to a DIFFERENT owner than the caller believes
     * it does. Split out from [unreceive] so no single function's throw count trips
     * detekt's `ThrowsCount` (max 2) — see the `unitLoadForWrite` precedent in
     * [StockService] for the same pattern applied to a different rule violation.
     */
    private fun requireOwnedByClient(su: StockUnit, stockUnitId: Long, clientId: Long) {
        if (su.clientId != clientId) {
            throw InventoryException.ValidationFailed(
                "stock unit $stockUnitId does not belong to client $clientId"
            )
        }
    }

    /**
     * Defense-in-depth: GoodsReceiptService.reverseLine already refuses a repeat
     * reversal via GoodsReceiptLine.reversed before ever calling here.
     */
    private fun requireNotAlreadyReversed(su: StockUnit, stockUnitId: Long) {
        if (su.state == StockState.DELETABLE.code) {
            throw InventoryException.InvalidStateTransition(stockUnitId, su.state, StockState.DELETABLE.code)
        }
    }

    /**
     * Behavioral parity with myWMS's goods-receipt "stock is changed" guard, matching in
     * intent (independent implementation): the stock must be untouched (amount) and
     * unencumbered (reservations) since receipt.
     */
    private fun requireUnchangedAndUnreserved(su: StockUnit, stockUnitId: Long, expectedAmount: BigDecimal) {
        if (su.amount.compareTo(expectedAmount) != 0) {
            throw InventoryException.ConcurrencyConflict(
                "stock unit $stockUnitId has changed: amount ${su.amount.toPlainString()} " +
                    "!= received ${expectedAmount.toPlainString()}"
            )
        }
        if (su.reservedAmount.signum() != 0) {
            throw InventoryException.Encumbered(
                "stock unit $stockUnitId has ${su.reservedAmount.toPlainString()} reserved"
            )
        }
    }

    /**
     * VISIBLE truncation of the lock note for the journal hop: `activity_code` is
     * VARCHAR(50), so anything longer becomes `take(47) + "…"` — a reader can always
     * tell text was cut (never a silent cut). The GR line keeps the full note.
     */
    private fun journalReason(note: String?): String? = note?.let {
        if (it.length > MAX_REASON_LENGTH) it.take(TRUNCATED_REASON_LENGTH) + ELLIPSIS else it
    }

    /**
     * Resolve-or-create the receiving unit load. An existing label is reused only
     * when the unit load sits at the requested location in state INCOMING/ON_STOCK
     * (per the [StockReceiver] contract); any other clash is rejected because labels
     * are globally unique.
     */
    private fun resolveUnitLoad(request: ReceiveStockRequest): UnitLoad {
        val label = request.unitLoadLabel
        if (label != null) {
            unitLoadRepository.findByLabelId(label)?.let { existing ->
                return validateReusable(existing, request)
            }
            return createUnitLoad(label, request)
        }
        return createUnitLoad(generateLabel(request), request)
    }

    private fun validateReusable(existing: UnitLoad, request: ReceiveStockRequest): UnitLoad {
        // The ACTOR's owner is deliberately not consulted — receiving staff handle every
        // owner's goods. The RECEIPT's owner is: a unit load holds exactly one customer's
        // goods, so reusing another owner's unit load would misattribute this stock
        // (StockService.createStock inherits clientId from the unit load).
        val reusable = existing.clientId == request.clientId &&
            existing.storageLocationId == request.locationId &&
            existing.state in REUSABLE_UL_STATES
        if (!reusable) {
            throw InventoryException.ValidationFailed(
                "Unit load label '${existing.labelId}' is already in use and not reusable here " +
                    "(expected an INCOMING/ON_STOCK unit load at location ${request.locationName} " +
                    "owned by client ${request.clientId})"
            )
        }
        return existing
    }

    private fun createUnitLoad(label: String, request: ReceiveStockRequest): UnitLoad {
        val typeId = request.unitLoadTypeId?.also { unitLoadTypeService.findById(it) }
            ?: defaultUnitLoadTypeId()
        val unitLoad = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = request.clientId,
                labelId = label,
                unitLoadTypeId = typeId,
                storageLocationId = request.locationId,
                storageLocationName = request.locationName,
            ),
            tenantContext,
        )
        // Receiving unit loads start INCOMING so follow-up receive calls can reuse them.
        unitLoad.state = StockState.INCOMING.code
        return unitLoad
    }

    /** Default unit load type = lowest id (the seeded 'Euro Pallet'). */
    private fun defaultUnitLoadTypeId(): Long =
        unitLoadTypeService.findAll().minByOrNull { it.id ?: Long.MAX_VALUE }?.id
            ?: throw InventoryException.NotFound("UnitLoadType", "default")

    /** SC17: unit-load labels are `unit_loads.label_id` (VARCHAR(255)), globally unique across owners. */
    private fun generateLabel(request: ReceiveStockRequest): String =
        sequenceNumberService.next("unitload.labelId", "UL", request.clientId, MAX_LABEL_LENGTH) {
            unitLoadRepository.findByLabelId(it) == null
        }

    companion object {
        private val LOG: Logger = Logger.getLogger(DefaultStockReceiver::class.java)
        private val REUSABLE_UL_STATES = setOf(StockState.INCOMING.code, StockState.ON_STOCK.code)
        private const val ACTIVITY_CODE = "RECEIVE"
        /** inventory_journals.activity_code is VARCHAR(50). */
        private const val MAX_REASON_LENGTH = 50
        private const val TRUNCATED_REASON_LENGTH = 47
        private const val ELLIPSIS = "…"
        /** unit_loads.label_id is VARCHAR(255). */
        private const val MAX_LABEL_LENGTH = 255
    }
}
