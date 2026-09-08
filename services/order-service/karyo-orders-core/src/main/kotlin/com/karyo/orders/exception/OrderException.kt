package com.karyo.orders.exception

import com.karyo.common.exception.KaryoException
import com.karyo.orders.spi.ConcurrentStateChange
import com.karyo.orders.spi.OrderReleaseViolation
import java.math.BigDecimal

sealed class OrderException(message: String) : KaryoException(message) {

    class NotFound(entityType: String, identifier: Any) :
        OrderException("$entityType not found: $identifier")

    class DuplicateName(entityType: String, name: String) :
        OrderException("$entityType with name '$name' already exists")

    class InvalidTransition(entityId: Long, currentState: Int, targetState: Int) :
        OrderException("Invalid state transition for entity $entityId: $currentState -> $targetState"), ConcurrentStateChange

    /** A referenced entity (product, strategy) does not exist — 404-style per inventory precedent. */
    class InvalidReference(entityType: String, identifier: Any) :
        OrderException("$entityType not found: $identifier")

    /** Header/lines are frozen after release; edits allowed only in CREATED. */
    class NotEditable(entityId: Long, currentState: Int, entityType: String = "Order") :
        OrderException("$entityType $entityId is not editable in state $currentState (only CREATED entities can be updated)")

    /** An [com.karyo.orders.spi.OrderReleaseValidator] extension vetoed the release. */
    class ReleaseRejected(val violations: List<OrderReleaseViolation>) :
        OrderException("Order release rejected: " + violations.joinToString("; ") { "${it.code}: ${it.message}" })

    class ValidationFailed(detail: String) :
        OrderException(detail)

    /**
     * Receiving [amount] would push the ASN line past its expectedAmount. Overridable
     * per request via `allowOverReceipt`.
     */
    class OverReceipt(asnLineId: Long, expected: BigDecimal, received: BigDecimal, amount: BigDecimal) :
        OrderException(
            "Over-receipt on ASN line $asnLineId: received $received + $amount exceeds expected $expected " +
                "(set allowOverReceipt to override)"
        )

    /** The ASN is not in a receivable state (must be RELEASED or STARTED). */
    class AsnNotReceivable(asnId: Long, currentState: Int) :
        OrderException("ASN $asnId is not receivable in state $currentState (must be RELEASED or STARTED)")

    /** The goods receipt is not in a receivable state (must be CREATED or STARTED). */
    class ReceiptNotReceivable(receiptId: Long, currentState: Int) :
        OrderException("GoodsReceipt $receiptId cannot receive lines in state $currentState (must be CREATED or STARTED)")

    /** Cancellation gate beyond the state machine (e.g. ASN already STARTED, receipt has lines). */
    class NotCancelable(entityType: String, entityId: Long, reason: String) :
        OrderException("$entityType $entityId cannot be canceled: $reason")

    /**
     * A receive-time lockType outside the accepted subset (422) — incl. an explicit
     * UNLOCKED(0): omit the field to receive without a lock.
     */
    class UnsupportedLockType(lockType: Int) :
        OrderException(
            "Lock type $lockType is not accepted at receipt " +
                "(allowed: 1 GENERAL, 103 QUALITY_FAULT, 202 LOT_EXPIRED, 203 LOT_TOO_YOUNG; omit for no lock)"
        )

    /** An unknown [com.karyo.orders.vo.GoodsReceiptType] code at create (422). */
    class UnsupportedReceiptType(receiptType: Int) :
        OrderException("Receipt type $receiptType is not a known GoodsReceiptType (allowed: 0 NORMAL, 1 RETOUR)")

    /** RETOUR receipts must not bind an ASN — customer returns ride the blind path (422). */
    class RetourWithAsn(asnId: Long) :
        OrderException(
            "RETOUR receipt cannot have ASNs (attempted to bind ASN $asnId): customer returns do not " +
                "arrive on supplier ASNs (open a blind RETOUR receipt instead)"
        )

    /**
     * V424: an ASN cannot be detached from a receipt while a non-reversed line still
     * references one of its ASN lines (409) — detaching would orphan a live receive-time link.
     */
    class AsnDetachConflict(receiptId: Long, asnId: Long) :
        OrderException(
            "GoodsReceipt $receiptId cannot detach ASN $asnId: a non-reversed line still references it " +
                "(reverse the line first)"
        )

    /**
     * A receive-time product constraint failed (422): lot-mandatory without a lot,
     * best-before-mandatory without a date, an already-expired bestBefore, or a
     * product that vanished after its ASN was created. The message names the rule.
     */
    class ReceiptConstraintViolation(detail: String) :
        OrderException(detail)

    /**
     * B7 claim/release conflict (409, mirroring the pick precedent where BOTH the
     * already-claimed claim and the non-owner release land as 409): the receipt is
     * already claimed (incl. by the caller — claim is NOT idempotent), is closed,
     * or a non-owner tried to release it without the MANAGER override.
     */
    class ReceiptClaimConflict(receiptId: Long, reason: String) :
        OrderException("GoodsReceipt $receiptId claim conflict: $reason")

    /** B7: receiving/finishing refused because the receipt is paused (409). */
    class ReceiptPaused(receiptId: Long) :
        OrderException("GoodsReceipt $receiptId is paused — resume it before receiving or finishing")

    /** B7 pause/resume conflict (409): double pause, resume of a non-paused receipt, or pausing a closed one. */
    class ReceiptPauseConflict(receiptId: Long, reason: String) :
        OrderException("GoodsReceipt $receiptId pause conflict: $reason")

    /**
     * D7: a document was requested before the order reached the document's gating state
     * (409) — mirrors [com.karyo.fulfillment.exception.FulfillmentException.DocumentNotReady].
     * The delivery note gates on PICKED: before picking there is nothing to reconcile.
     */
    class DocumentNotReady(orderId: Long, documentType: String, detail: String) :
        OrderException("Document $documentType for order $orderId is not ready: $detail")

    /**
     * Inbound-completion row 7: a receive-line `storageStrategyId` that is unknown OR belongs to
     * a different tenant (both collapse to the same [com.karyo.layout.spi.StorageStrategyLookup.exists]
     * `false` — 422, the caller-supplied-semantically-unusable-target precedent shape, same as
     * the `packagingUnitId` in-module check, not the 404 [InvalidReference] used for missing refs).
     */
    class InvalidStorageStrategy(storageStrategyId: Long) :
        OrderException("Storage strategy $storageStrategyId does not exist or does not belong to this client")

    /**
     * Row 10 (stock-and-orders sprint): a caller-supplied `destinationLocationId` that is unknown
     * OR belongs to a different tenant (both collapse to the same
     * [com.karyo.layout.spi.StorageLocationLookup.findById] `null` result -- 422), same shape as
     * [InvalidStorageStrategy]. `null` (no destination set) is always valid.
     */
    class InvalidDestinationLocation(destinationLocationId: Long) :
        OrderException("Destination location $destinationLocationId does not exist or does not belong to this client")

    /**
     * Order streaming (B3): a per-order `releaseModeOverride` that does not parse to
     * MANUAL|WAVE|STREAM (422) -- a DEDICATED type per the spec/global-constraints ruling that
     * a bad enum-like value is 422, not the 400 [ValidationFailed] maps to.
     */
    class InvalidReleaseMode(raw: String) :
        OrderException("releaseModeOverride must be MANUAL, WAVE or STREAM (got '$raw')")

    /**
     * Row 10 claim/release conflict (409): mirrors [ReceiptClaimConflict]'s shape exactly -- the
     * order is already claimed (incl. by the caller, claim is NOT idempotent), is closed (state at
     * or past FINISHED(700), which also covers CANCELED(800) numerically), or a non-owner tried to
     * release without the MANAGER override. A DEDICATED type because [ValidationFailed] maps to
     * 400, not 409.
     */
    class OrderClaimConflict(val orderId: Long, val reason: String) :
        OrderException("DeliveryOrder $orderId claim conflict: $reason")
}
