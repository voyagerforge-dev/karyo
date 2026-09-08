package com.karyo.tasks.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.vo.TransportType
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * A unit-load move (PUTAWAY, MOVE, REPLENISH, or — PT15 — TRANSFER, a chain successor).
 * Reuses the shared [OrderState] vocabulary from karyo-orders-api; the v1.2 subset is
 * CREATED(50) → RELEASED(100) → RESERVED(400, operator assigned) → STARTED(500) →
 * FINISHED(700), plus CANCELED(800) pre-STARTED.
 *
 * clientId (via [TenantEntity]) is the domain-owner attribute per silo tenancy.
 * Location/unit-load references are id+denormalized-name (no cross-module FKs).
 *
 * [executorType] is fixed to HUMAN in v1.2; the column exists so WCS/equipment routing
 * (ADR-034, v2.x) can populate it without a schema churn. [locationReservationId]
 * (unused as a stored FK — the layout module keys its reservation by transportOrderId)
 * is retained as a breadcrumb of the soft reservation held while the task is open.
 */
@Entity
@Table(name = "transport_orders")
class TransportOrder : TenantEntity() {

    @Column(name = "order_number", nullable = false, length = 100)
    lateinit var orderNumber: String

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", nullable = false, length = 20)
    lateinit var transportType: TransportType

    @Column(name = "unit_load_id", nullable = false)
    var unitLoadId: Long = 0

    @Column(name = "unit_load_label", nullable = false, length = 255)
    lateinit var unitLoadLabel: String

    @Column(name = "source_location_id", nullable = false)
    var sourceLocationId: Long = 0

    @Column(name = "source_location_name", nullable = false, length = 100)
    lateinit var sourceLocationName: String

    @Column(name = "destination_location_id")
    var destinationLocationId: Long? = null

    @Column(name = "destination_location_name", length = 100)
    var destinationLocationName: String? = null

    @Column(name = "suggested_location_id")
    var suggestedLocationId: Long? = null

    @Column(name = "suggested_location_name", length = 100)
    var suggestedLocationName: String? = null

    @Column(nullable = false)
    var state: Int = OrderState.UNDEFINED.code

    @Column(nullable = false)
    var prio: Int = DEFAULT_PRIO

    @Column(name = "operator_id", length = 100)
    var operatorId: String? = null

    @Column(name = "executor_type", nullable = false, length = 20)
    var executorType: String = EXECUTOR_HUMAN

    /** The layout soft-reservation correlation (the layout module keys by orderNumber/id). */
    @Column(name = "location_reservation_id")
    var locationReservationId: Long? = null

    /** Idempotency key + provenance for auto-created putaway tasks (one TO per receipt line). */
    @Column(name = "goods_receipt_line_id")
    var goodsReceiptLineId: Long? = null

    /** Idempotency key + provenance for auto-created replenishment tasks (one open TO per fix assignment). */
    @Column(name = "fix_assignment_id")
    var fixAssignmentId: Long? = null

    /**
     * R12b (replenishment sprint Task 6): provenance for an area-level (Mode 2) replenishment
     * order minted by [com.karyo.replenishment.service.ReplenishmentService.scanAreas] — the
     * [com.karyo.layout.domain.model.ItemDataArea] the order was generated to satisfy. `null`
     * for every other transport order kind (fix-face REPLENISH, PUTAWAY, MOVE, TRANSFER) — the
     * two REPLENISH provenance columns ([fixAssignmentId] here) are mutually exclusive per order,
     * never both non-null on the same row.
     */
    @Column(name = "item_data_area_id")
    var itemDataAreaId: Long? = null

    /**
     * V503 (inbound-completion row 7 residual): the receipt line's validated putaway strategy
     * override, if any — PERSISTED (not just threaded at create time) so the re-resolve-on-start
     * path ([com.karyo.tasks.service.TaskService.start]) can replay the SAME override when the
     * original suggestion was empty; an unpersisted value would be silently dropped there.
     */
    @Column(name = "storage_strategy_id")
    var storageStrategyId: Long? = null

    @Column(length = 500)
    var note: String? = null

    @Column
    var started: Instant? = null

    @Column
    var finished: Instant? = null

    /**
     * Orthogonal pause stamp: non-null = paused. [state] NEVER moves on pause/resume,
     * so resume restores the task exactly where it was (myWMS's PAUSE state jump lost
     * STARTED on resume — deliberately not adopted from myWMS here). Same model as
     * [com.karyo.orders.domain.model.GoodsReceipt.pausedAt].
     */
    @Column(name = "paused_at")
    var pausedAt: Instant? = null

    /**
     * PT15: set on the PREDECESSOR when [com.karyo.tasks.service.ChainContinuationService]
     * spins up a TRANSFER successor at completion time — the id of that successor order.
     * `null` for every order that never chained (the overwhelming majority). The relationship is
     * RECURSIVE, not one-hop: a successor is itself an ordinary [TransportOrder] carrying its own
     * [suggestedLocationId] (the chain's real final target, inherited unchanged), so if IT later
     * completes short of that target on another transfer-staging location, [ChainContinuationService.maybeChain]
     * fires again and stamps ITS OWN [successorId], minting a third hop — and so on. A chain only
     * terminates when a hop completes at the final target or at any ordinary, non-staging
     * location, at which point that hop's [successorId] stays `null`. Walk a full chain forward by
     * following [successorId] from the first order until it is `null`.
     */
    @Column(name = "successor_id")
    var successorId: Long? = null

    /**
     * PT17: caller's ERP reference for this move. Mirrors the naming divergence already
     * established across modules -- orders (`DeliveryOrder.externalNumber` /
     * `DeliveryOrderLine.externalNumber` / `Asn.externalNumber`, VARCHAR(100)) uses
     * `externalNumber`; inventory (`UnitLoad.externalId`, VARCHAR(255)) uses `externalId`.
     * TransportOrder carries BOTH here rather than picking a side -- Karyo-native (myWMS's
     * TransportOrder has no ERP-reference concept to be faithful to).
     */
    @Column(name = "external_number", length = 100)
    var externalNumber: String? = null

    /** See [externalNumber]'s KDoc -- the inventory-module-shaped half of the same pair. */
    @Column(name = "external_id", length = 255)
    var externalId: String? = null

    /**
     * PT17 denorm-at-creation (see `ConfirmVariantService.denormalizeAtCreation`): the product
     * on this order's own unit load's single live (non-DELETABLE) stock unit at the moment the
     * order was created. `null` when the unit load carried zero or more than one live stock unit
     * at creation time -- an honest gap (no single "the stock" to denormalize), never a guess.
     * [itemDataNumber], [lotNumber], [amount], and [sourceStockUnitId] share this same condition
     * and are stamped together.
     */
    @Column(name = "item_data_id")
    var itemDataId: Long? = null

    @Column(name = "item_data_number", length = 100)
    var itemDataNumber: String? = null

    @Column(name = "lot_number", length = 255)
    var lotNumber: String? = null

    /** See [itemDataId]'s KDoc. The denormalized stock's amount AT CREATION TIME -- a fixed
     *  snapshot, never updated after; [confirmedAmount] carries what actually got confirmed. Also
     *  backs `TaskService.complete`'s R13 default-amount rule (an amount-less, destination-less
     *  complete request defaults to this snapshot) -- Task 3 (defect-burndown-4, row 31) review
     *  finding: that call site deliberately keeps using this snapshot rather than a live re-read,
     *  because a genuine R13 top-up cap (deliberately smaller than the live stock) is
     *  indistinguishable from a stale whole-UL denorm using this field alone -- see
     *  `TaskService.complete`'s KDoc for the full reasoning. */
    @Column(precision = 17, scale = 4)
    var amount: BigDecimal? = null

    /**
     * PT17: the amount actually confirmed, written on EVERY confirm path -- whole-UL move,
     * confirm-merge (= the merged stock amount), partial (= the partial amount transferred). Task
     * 3 (defect-burndown-4, rows 6 + 31): the whole-UL path now records the LIVE single-live-stock
     * amount on the order's own unit load, re-read at confirm time via
     * `ConfirmVariantService.liveSingleStockAmount` -- not the creation-time [amount] denorm --
     * so it reflects the ACTUAL relocated quantity even when [amount] itself is stale (e.g. an
     * explicit-destination staging-drop bypassing the R13 default, or the source stock shrinking
     * between task creation and confirm). [amount] is used as the whole-UL fallback only when the
     * unit load carries zero or multiple live stocks at confirm time. Residual, deliberately
     * accepted gaps: a multi-SKU unit load still 409s
     * [com.karyo.tasks.exception.TaskException.MixedSourceLoad] on the partial/merge paths
     * (recoverable via cancel + rescan); a source stock that GREW between creation and a
     * default-gesture (no explicit destination/amount) complete can still make an R13-capped
     * order look like a smaller-than-intended partial, since the default-amount rule itself keeps
     * using the mint-time [amount] snapshot (see that rule's KDoc) -- also recoverable via cancel
     * + rescan.
     */
    @Column(name = "confirmed_amount", precision = 17, scale = 4)
    var confirmedAmount: BigDecimal? = null

    /** See [itemDataId]'s KDoc -- the id of the stock unit the denorm was read from. */
    @Column(name = "source_stock_unit_id")
    var sourceStockUnitId: Long? = null

    companion object {
        const val DEFAULT_PRIO = 50
        const val EXECUTOR_HUMAN = "HUMAN"
    }
}
