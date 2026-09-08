package com.karyo.orders.service

import com.karyo.inventory.api.vo.LockType
import com.karyo.layout.spi.StorageStrategyLookup
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.exception.OrderException
import com.karyo.product.dto.ProductResponse
import com.karyo.product.spi.ProductLookup
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * Receive-line product resolution + pre-write validation — split out of [GoodsReceiptService]
 * (inbound-completion row 7) so the new [StorageStrategyLookup] dependency does not push that
 * service's constructor past the 10-param detekt ceiling. This sibling took over [productLookup]'s
 * ENTIRE usage (both fetch call sites), not just the constraint checks, so [GoodsReceiptService]
 * could swap the [productLookup] param for this validator at ZERO net constructor growth rather
 * than adding an 11th param.
 *
 * All checks here run BEFORE [GoodsReceiptService.receiveLine] performs any write
 * ([com.karyo.inventory.api.spi.StockReceiver.receive] / the GR-line persist).
 */
@ApplicationScoped
class ReceiveLineValidator(
    private val productLookup: ProductLookup,
    private val storageStrategyLookup: StorageStrategyLookup,
) {

    /**
     * Resolves the product for an ASN-bound receive. B6: a product deleted after the ASN was
     * created must FAIL the receive rather than skip validation — a vanished product must never
     * become a constraint bypass (and its stock would be orphaned anyway).
     */
    fun resolveAsnLineProduct(itemDataId: Long, itemDataNumber: String): ProductResponse =
        productLookup.findById(itemDataId)
            ?: throw OrderException.ReceiptConstraintViolation(
                "product $itemDataId ($itemDataNumber) no longer exists; the ASN line cannot be received"
            )

    /** Resolves the product for a blind receive (no ASN line). */
    fun resolveBlindProduct(itemDataId: Long): ProductResponse =
        productLookup.findById(itemDataId)
            ?: throw OrderException.InvalidReference("Product", "id=$itemDataId")

    /**
     * Receive-time locks accept only the receive-appropriate subset of the INVENTORY LockType
     * (never the layout enum): an explicit UNLOCKED(0) is refused so "no lock" has exactly one
     * spelling (omission), and STOCKTAKING(7) or an unknown code is nonsensical at receipt → 422.
     */
    fun validateLockType(lockType: Int?) {
        if (lockType != null && lockType !in ALLOWED_RECEIPT_LOCK_TYPES) {
            throw OrderException.UnsupportedLockType(lockType)
        }
    }

    /**
     * B6: receive-time enforcement of the product's capture constraints — the flags exist on
     * ItemData, are editable, and are rendered by the UI, but nothing checked them at the only
     * moment they matter. Runs on BOTH paths, before any write.
     *
     * Deliberately NO min-remaining-shelf-life threshold: `shelflife` is a product duration, not
     * an acceptance threshold — that datum does not exist yet and is recorded as a future knob on
     * the worklist row, not invented here.
     */
    fun validateReceiptConstraints(product: ProductResponse, request: ReceiveLineRequest) {
        val violation = when {
            product.lotMandatory && request.lotNumber.isNullOrBlank() ->
                "product ${product.number} requires a lot number at receipt (lotMandatory)"
            product.bestBeforeMandatory && request.bestBefore == null ->
                "product ${product.number} requires a best-before date at receipt (bestBeforeMandatory)"
            request.bestBefore?.isBefore(LocalDate.now()) == true ->
                "bestBefore ${request.bestBefore} is in the past — expired goods cannot be received"
            else -> null
        }
        if (violation != null) {
            throw OrderException.ReceiptConstraintViolation(violation)
        }
    }

    /**
     * Inbound-completion row 7: a caller-supplied [ReceiveLineRequest.storageStrategyId] must
     * name a [com.karyo.layout.domain.model.StorageStrategy] owned by [clientId] — unknown or
     * foreign (both collapse to the same [StorageStrategyLookup.exists] `false`, mirroring
     * `LocationFinderService.resolveStrategy`'s ownership fail-close) is a 422, matching the
     * `packagingUnitId` in-module precedent shape (a caller-supplied target that is semantically
     * unusable, not merely absent). `null` (no override requested) is always valid.
     */
    fun validateStorageStrategy(storageStrategyId: Long?, clientId: Long) {
        if (storageStrategyId != null && !storageStrategyLookup.exists(storageStrategyId, clientId)) {
            throw OrderException.InvalidStorageStrategy(storageStrategyId)
        }
    }

    companion object {
        /** Receive-appropriate inventory lock types: {1, 103, 202, 203}. */
        private val ALLOWED_RECEIPT_LOCK_TYPES = setOf(
            LockType.GENERAL.code,
            LockType.QUALITY_FAULT.code,
            LockType.LOT_EXPIRED.code,
            LockType.LOT_TOO_YOUNG.code,
        )
    }
}
