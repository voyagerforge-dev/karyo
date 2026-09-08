package com.karyo.stocktaking.domain.event

import java.time.Instant

/**
 * A claimed [com.karyo.stocktaking.domain.model.CountOrder] was handed back to the unclaimed pool
 * by [com.karyo.stocktaking.service.StocktakingService.release]. The order stays GENERATED --
 * a release clears the holder, it does not move the count's state.
 *
 * The audit reason this exists: a MANAGER may release work claimed by someone else
 * (`WorkProvider.release(asManager = true)`), and before this event that override left no trace
 * at all. [releasedFrom] is the operator who HELD the count, [releasedBy] the one who performed
 * the release; [managerOverride] is derived from those two rather than from the caller's
 * `asManager` capability flag, because that flag only says the actor *could* override -- a
 * manager releasing their own count is a self-release. For COUNT that distinction is not merely
 * tidier, it is load-bearing: `WorkInboxResource` derives `asManager` from the `inventory-write`
 * role, which is ALSO the role COUNT releases require at all, so `asManager` is true for every
 * legitimate count release and would mislabel every self-release as an override.
 *
 * Unlike a pick, an unheld count order cannot be released at all (`release` rejects a null
 * holder), so [releasedFrom] is in practice never null; it stays nullable to mirror the entity
 * column.
 *
 * Outbox-only: the count lifecycle has no in-process consumer for a release, and the outbox row
 * is the durable audit record. Declared here in `domain/event` rather than in
 * `karyo-stocktaking-api` for the same reason [com.karyo.fulfillment.domain.event] events are --
 * `api` is for payloads a FOREIGN core must observe, and nothing outside stocktaking-core reads
 * this one.
 */
data class CountOrderReleasedEvent(
    val countOrderId: Long,
    val orderNumber: String,
    val locationId: Long,
    val locationName: String,
    val releasedFrom: String?,
    val releasedBy: String,
    val managerOverride: Boolean,
    val clientId: Long,
    val occurredAt: Instant,
)
