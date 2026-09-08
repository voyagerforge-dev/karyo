package com.karyo.orders.messaging

import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.service.GoodsReceiptService
import com.karyo.security.TenantContext
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.spi.WorkProvider
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Surfaces open goods receipts (inbound-completion row 4 residual) in the unified floor
 * work inbox. Mirrors [com.karyo.stocktaking.messaging.CountWorkProvider]'s shape: a thin
 * SPI adapter over the owning service, writing through to [GoodsReceiptService] as the
 * source of truth — claim/release never touch [GoodsReceiptRepository] directly.
 *
 * **Authz note:** the work inbox (`WorkInboxResource`) gates `claim`/`release` uniformly at
 * `inventory-read`/`inventory-write` for EVERY work type (PICK/COUNT/transport/RECEIVE alike)
 * — RECEIVE adds no new gate here, it inherits the inbox's existing posture. The receipt
 * module's own DIRECT `POST /api/v1/goods-receipts/{id}/claim` requires `order-write`
 * instead. These are genuinely different role checks: the OPERATOR realm composite carries
 * `order-write` alongside `inventory-read` (`infrastructure/keycloak/karyo-realm.json`), so
 * every floor operator holds equivalent privilege on both paths in practice — but VIEWER
 * (`inventory-read`, no `order-write`) can claim a receipt via the inbox while being refused
 * on the direct endpoint. That asymmetry is a PRE-EXISTING inbox-wide design posture (every
 * other `WorkProvider` — `CountWorkProvider`, `TransportWorkProvider`, `PickWorkProvider` —
 * inherits the identical gate), not something introduced by [ReceivingWorkProvider]; filed as
 * its own WORKLIST row (filed 2026-08-03) rather than fixed inline here, since a guard change
 * would affect every work type, not just RECEIVE.
 */
@ApplicationScoped
class ReceivingWorkProvider(
    private val repository: GoodsReceiptRepository,
    private val goodsReceiptService: GoodsReceiptService,
    private val tenantContext: TenantContext,
) : WorkProvider {

    override fun workTypes(): Set<WorkType> = setOf(WorkType.RECEIVE)

    override fun listOpen(filter: WorkFilter): List<WorkItem> {
        val types = filter.types
        if (types != null && !types.contains(WorkType.RECEIVE)) return emptyList()
        return repository.findClaimable(tenantContext.clientId).map { it.toWorkItem(WorkState.OPEN) }
    }

    override fun listClaimedBy(operatorId: String): List<WorkItem> =
        repository.findClaimedBy(tenantContext.clientId, operatorId).map { it.toWorkItem(WorkState.CLAIMED) }

    /**
     * Claims via [GoodsReceiptService.claim] (the source of truth), then re-reads the entity
     * for the [WorkItem] shape — [GoodsReceiptService.claim] returns a response DTO, not the
     * entity, so a fresh tenant-scoped read is the simplest correct way to get the
     * [java.time.Instant] `created` back (the DTO already stringifies it). Split into
     * [claimOrConflict] + [findClaimedOrConflict] so neither function's throw count trips
     * detekt's `ThrowsCount` (max 2) — the same split [GoodsReceiptService] already uses for
     * [OrderException.NotCancelable] guards.
     */
    @Transactional
    override fun claim(ref: WorkRef, operatorId: String): WorkItem {
        claimOrConflict(ref, operatorId)
        return findClaimedOrConflict(ref)
    }

    /**
     * Both of [GoodsReceiptService.claim]'s failure modes are translated to
     * [WorkClaimConflictException] — the exact type
     * [com.karyo.work.service.WorkDispatchService.getNext]'s race-skip loop depends on:
     * [OrderException.ReceiptClaimConflict] (already claimed, or the receipt is closed) and
     * [OrderException.NotFound] (the delete-race path: the receipt vanished between the pool
     * read and this claim — see `CountWorkProviderTest`'s pinned nonexistent-id case).
     */
    private fun claimOrConflict(ref: WorkRef, operatorId: String) {
        try {
            goodsReceiptService.claim(ref.sourceId, operatorId, tenantContext.clientId)
        } catch (_: OrderException.ReceiptClaimConflict) {
            throw WorkClaimConflictException("GoodsReceipt ${ref.sourceId} claim conflict")
        } catch (_: OrderException.NotFound) {
            throw WorkClaimConflictException("GoodsReceipt ${ref.sourceId} not found")
        }
    }

    private fun findClaimedOrConflict(ref: WorkRef): WorkItem {
        val receipt = repository.findByIdAndClient(ref.sourceId, tenantContext.clientId)
            ?: throw WorkClaimConflictException("GoodsReceipt ${ref.sourceId} not found")
        return receipt.toWorkItem(WorkState.CLAIMED)
    }

    override fun release(ref: WorkRef, operatorId: String, asManager: Boolean) {
        goodsReceiptService.release(ref.sourceId, operatorId, asManager, tenantContext.clientId)
    }

    /**
     * [priority] passes [GoodsReceipt.prio] through UNINVERTED — same direction as
     * [com.karyo.tasks.messaging.TransportWorkProvider] passes its transport `prio` (both
     * follow the `DeliveryOrder.prio` convention: lower = more urgent), kept for
     * Karyo-internal consistency across every [WorkProvider].
     */
    private fun GoodsReceipt.toWorkItem(state: WorkState) = WorkItem(
        ref = WorkRef(WorkType.RECEIVE, id!!),
        workType = WorkType.RECEIVE,
        priority = prio,
        state = state,
        claimedBy = operatorId,
        zone = null,
        primaryLocation = dockLocationName,
        destination = null,
        summary = dockLocationName?.let { "Receive $receiptNumber @ $it" } ?: "Receive $receiptNumber",
        createdAt = created,
        primaryLocationId = dockLocationId,
    )
}
