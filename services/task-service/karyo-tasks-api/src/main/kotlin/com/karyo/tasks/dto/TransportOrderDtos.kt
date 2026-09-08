package com.karyo.tasks.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * Create a manual MOVE transport order (PUTAWAY/REPLENISH/TRANSFER tasks are all
 * auto-created — by receiving, the replenishment engine, and chain continuation
 * respectively — never via this endpoint). The source location is resolved from the unit
 * load's current location via the inventory lookup; only the destination is supplied.
 *
 * PT17: [externalNumber]/[externalId] are the caller's ERP reference pair (see
 * [com.karyo.tasks.domain.model.TransportOrder.externalNumber]'s KDoc for the naming
 * divergence) — optional, threaded only through this manual-move path; auto-created
 * putaway/replenish/transfer orders leave both null.
 */
data class CreateTransportOrderRequest(
    @field:NotNull @field:Positive
    val unitLoadId: Long?,
    @field:NotNull @field:Positive
    val destinationLocationId: Long?,
    val destinationLocationName: String? = null,
    val prio: Int = 50,
    @field:Size(max = 100)
    val externalNumber: String? = null,
    @field:Size(max = 255)
    val externalId: String? = null,
)

/**
 * Assign a RELEASED task to the caller. Since 2026-08-19 the operator is derived from the JWT
 * server-side; [operatorId] is accepted-and-ignored for one release (kept so existing callers'
 * bodies still deserialize) and will then be removed -- same deprecation pattern as the print
 * endpoint's reserved `printer` field.
 */
data class AssignTransportOrderRequest(
    @Deprecated("ignored; the operator is derived from the JWT")
    val operatorId: String? = null,
)

/**
 * Complete a started task; the operator may override the suggested destination, OR (PT16)
 * confirm-merge the task's unit load's stock into an EXISTING unit load via
 * [destinationUnitLoadId] instead. The two destination shapes are mutually exclusive — supplying
 * both is a 400 (see [com.karyo.tasks.service.TaskService.complete]).
 *
 * PT17: [amount] requests a PARTIAL confirm — only meaningful when the order's own unit load
 * carries exactly one live stock unit (409 `MixedSourceLoad` otherwise). `null` (the default)
 * is the existing whole-UL/merge behavior, unchanged. A non-null [amount] that is `>=` the
 * source stock's amount is treated as a full confirm via the existing paths; an [amount] strictly
 * less than it is a genuine partial — see `ConfirmVariantService` for the full semantics.
 */
data class CompleteTransportOrderRequest(
    val destinationLocationId: Long? = null,
    val destinationLocationName: String? = null,
    /** PT16: confirm-merge into an EXISTING unit load instead of a location. */
    val destinationUnitLoadId: Long? = null,
    /** PT17: request a partial confirm of this amount rather than the whole source stock. */
    @field:Positive
    val amount: BigDecimal? = null,
)

data class TransportOrderResponse(
    val id: Long,
    val orderNumber: String,
    val transportType: String,
    val unitLoadId: Long,
    val unitLoadLabel: String,
    val sourceLocationId: Long,
    val sourceLocationName: String,
    val destinationLocationId: Long?,
    val destinationLocationName: String?,
    val suggestedLocationId: Long?,
    val suggestedLocationName: String?,
    val state: Int,
    val stateName: String,
    val prio: Int,
    val operatorId: String?,
    val executorType: String,
    val note: String?,
    val goodsReceiptLineId: Long?,
    val clientId: Long,
    val created: String,
    val modified: String,
    /** PT18: orthogonal pause stamp — non-null means paused; `state` never moves. */
    val pausedAt: String? = null,
    /** Stranded fields (recon-flagged): [com.karyo.tasks.domain.model.TransportOrder.started]/`finished`
     *  existed on the entity but were never surfaced on this response until now. */
    val started: String? = null,
    val finished: String? = null,
    /** PT15: non-null when this order chained onto a TRANSFER successor at completion. */
    val successorId: Long? = null,
    /** PT17: ERP reference pair — see [com.karyo.tasks.domain.model.TransportOrder.externalNumber]. */
    val externalNumber: String? = null,
    val externalId: String? = null,
    /** PT17 denorm-at-creation — see [com.karyo.tasks.domain.model.TransportOrder.itemDataId]. */
    val itemDataId: Long? = null,
    val itemDataNumber: String? = null,
    val lotNumber: String? = null,
    val amount: java.math.BigDecimal? = null,
    /** PT17: written on every confirm path — see [com.karyo.tasks.domain.model.TransportOrder.confirmedAmount]. */
    val confirmedAmount: java.math.BigDecimal? = null,
    val sourceStockUnitId: Long? = null,
)
