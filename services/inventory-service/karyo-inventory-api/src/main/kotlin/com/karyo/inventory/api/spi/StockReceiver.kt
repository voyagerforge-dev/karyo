package com.karyo.inventory.api.spi

import java.math.BigDecimal
import java.time.LocalDate

/**
 * In-process goods-receipt contract. Implemented by inventory-core and consumed by
 * other modules (e.g. the orders/receiving module) instead of a cross-service REST
 * client — same api-only seam as [StockReserver].
 *
 * Stock created through this contract enters in state INCOMING(100) with
 * `strategyDate = bestBefore` (the FIFO rule); the receipt-finish step promotes it
 * to ON_STOCK via [markOnStock].
 */
interface StockReceiver {

    /**
     * Receives stock at a location: resolves-or-creates the unit load and creates a
     * stock unit in state INCOMING(100).
     *
     * Unit-load resolution: when [ReceiveStockRequest.unitLoadLabel] matches an
     * existing unit load at the same location in state INCOMING or ON_STOCK, that
     * unit load is reused; otherwise a new one is created (a generated label is used
     * when none is supplied). A label that exists but is not reusable (different
     * location or state) is rejected.
     *
     * Receive-time lock: when [ReceiveStockRequest.lockType] is given, the existing
     * stock lock mechanism is applied with that inventory LockType code (the caller
     * validates the receive-appropriate subset) — the unit stays INCOMING and locked
     * until a manual unlock + state change. [ReceiveStockRequest.lockNote] is passed
     * as the lock's journal reason, truncated VISIBLY to 50 chars when longer
     * (`take(47) + "…"` — activity_code is VARCHAR(50)); the caller keeps the
     * authoritative full note.
     */
    fun receive(request: ReceiveStockRequest): ReceivedStock

    /**
     * Promotes the given stock units INCOMING(100) → ON_STOCK(300) — the
     * receipt-finish transition.
     *
     * Defensive like [StockReserver.release]: ids that no longer exist, are locked
     * (e.g. QA-held), or have already advanced past INCOMING are skipped with a log
     * entry instead of failing the batch.
     */
    fun markOnStock(stockUnitIds: List<Long>)

    /**
     * B3 reversal counterpart of [receive]: soft-delete the stock unit created by a
     * goods-receipt line, guarded by myWMS's amount-equality check — the stock must
     * still hold EXACTLY [expectedAmount] (any pick/partial consumption/adjustment
     * makes the line un-reversible by construction). [clientId] is the goods owner
     * and must match the stock unit's owner (integrity, not authorization).
     */
    fun unreceive(stockUnitId: Long, expectedAmount: BigDecimal, clientId: Long)
}

/**
 * One goods-receipt line as seen by the inventory module.
 *
 * @param itemDataNumber denormalized product number; the caller validates the
 *        product (ProductLookup) and passes the number so inventory needs no
 *        product-module dependency.
 * @param unitLoadLabel reuse an existing unit load with this label at the same
 *        location when its state is INCOMING/ON_STOCK, else create one; null ⇒
 *        always create with a generated label.
 * @param unitLoadTypeId unit load type for a newly created unit load; null ⇒ the
 *        default type (lowest id, seeded 'Euro Pallet').
 * @param bestBefore drives `strategyDate` (FIFO) on the created stock unit.
 * @param lockType inventory LockType code to lock the created unit with
 *        (e.g. QUALITY_FAULT 103); null ⇒ received unlocked.
 * @param lockNote free-text lock reason routed to the journal's activityCode,
 *        visibly truncated to 50 chars when longer.
 */
data class ReceiveStockRequest(
    /** The goods owner named by the goods receipt — attribution for the received stock. */
    val clientId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val locationId: Long,
    val locationName: String,
    val unitLoadLabel: String? = null,
    val unitLoadTypeId: Long? = null,
    val lotNumber: String? = null,
    /** Serial number as received; carried onto the created stock unit. */
    val serialNumber: String? = null,
    /**
     * ID-only reference to the product module's PackagingUnit the goods arrived in.
     * Stored on the created stock unit, not validated (no PackagingUnitLookup SPI yet).
     */
    val packagingUnitId: Long? = null,
    val bestBefore: LocalDate? = null,
    val lockType: Int? = null,
    val lockNote: String? = null,
)

/** Identifiers created/resolved by [StockReceiver.receive]. */
data class ReceivedStock(
    val stockUnitId: Long,
    val unitLoadId: Long,
    val unitLoadLabel: String,
)
