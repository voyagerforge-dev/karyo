package com.karyo.tasks.vo

/**
 * Kind of transport order.
 *
 *  - [PUTAWAY] — move freshly received stock from the receiving dock to a storage
 *    location chosen by the location finder. Auto-created by the receiving event.
 *  - [MOVE] — an ad-hoc operator-initiated move of a unit load between two locations.
 *  - [REPLENISH] — auto-created by the replenishment engine to top up a pick face from
 *    its reserve (bulk) location when stock falls below the face's min-qty threshold.
 *  - [TRANSFER] — PT15: the second (and any further) hop of a multi-hop transport chain.
 *    Auto-created by [com.karyo.tasks.service.ChainContinuationService] when a predecessor
 *    order (of any type) completes onto a transfer-staging [com.karyo.layout.domain.model.StorageArea]
 *    location while still carrying a different, real final target — the successor carries
 *    the unit load the rest of the way, from the staging location to that final target.
 *  - [CROSS_DOCK]: cross-docking sprint, mints straight from the receiving dock to a
 *    staging location, bypassing standard putaway, when the (paid) cross-docking engine
 *    matches incoming stock against open outbound demand. Minted by
 *    [com.karyo.tasks.service.TaskService.createCrossDock] via
 *    [com.karyo.tasks.spi.TransportOrderPort.createCrossDock]; the paid module answers
 *    [com.karyo.tasks.spi.CrossDockLookup] so the auto-putaway observer skips lines it
 *    already intercepted. An expiry/cancel fallback ([com.karyo.tasks.spi.TransportOrderPort
 *    .createPutawayFromStaging]) mints a normal PUTAWAY for a unit load left on staging.
 */
enum class TransportType {
    PUTAWAY,
    MOVE,
    REPLENISH,
    TRANSFER,
    CROSS_DOCK,
}
