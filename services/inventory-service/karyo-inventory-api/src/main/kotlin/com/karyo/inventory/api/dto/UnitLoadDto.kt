package com.karyo.inventory.api.dto

import java.math.BigDecimal
import java.time.Instant

data class UnitLoadResponse(
    val id: Long,
    val labelId: String,
    val externalId: String?,
    val unitLoadTypeId: Long,
    val unitLoadTypeName: String,
    val storageLocationId: Long,
    val storageLocationName: String,
    val state: Int,
    val opened: Boolean,
    val isCarrier: Boolean,
    val weight: BigDecimal?,
    /** Row 16: the recomputed value alone (type tare plus on-load stock weight), before any manual override. */
    val weightCalculated: BigDecimal?,
    /** Row 16: the manual override (for instance a scale reading), null when none is set. */
    val weightMeasure: BigDecimal?,
    val lockType: Int,
    val lockTypeName: String,
    val stockUnits: List<StockUnitSummary>,
    val created: Instant,
)

data class StockUnitSummary(
    val id: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val lotNumber: String?,
    val state: Int,
)

data class CreateUnitLoadRequest(
    /**
     * The goods owner this unit load belongs to.
     *
     * Required for an ops principal, which has no ambient owner — omitting it there is
     * rejected rather than guessed, because guessing would misattribute a customer's goods.
     * May be omitted by a goods-owner principal, which can only own its own goods; the
     * owner is then taken from the principal. Never defaults to 0 (the SYS tenant).
     */
    val clientId: Long? = null,
    val labelId: String,
    val unitLoadTypeId: Long,
    val storageLocationId: Long,
    val storageLocationName: String,
    val externalId: String? = null,
)

data class ChangeClientRequest(
    /**
     * The goods owner receiving the unit load and all its stock. Never 0 — the SYS tenant
     * is not a goods owner — and must identify an existing client.
     */
    val targetClientId: Long,
    val activityCode: String? = null,
)

data class TransferToCarrierRequest(
    val carrierUnitLoadId: Long,
)

data class SetCarrierRequest(
    val isCarrier: Boolean,
)

/** Row 16: PUT /{id}/weight-measure body. A null [weightMeasure] clears the override and drops the effective weight back to the calculation. */
data class SetWeightMeasureRequest(
    val weightMeasure: BigDecimal? = null,
)

data class TransferUnitLoadRequest(
    val destinationLocationId: Long,
    val destinationLocationName: String,
    val activityCode: String? = null,
)

/**
 * A2-3: pallet-level lock request. Defaults to `LockType.GENERAL(1)` — myWMS's
 * recursive unit-load lock default — since the legacy CLEARING lock values are
 * dead and are deliberately not implemented.
 */
data class LockUnitLoadRequest(
    val lockType: Int = 1,
    val note: String? = null,
)

/** A2-1: request body for `POST /{id}/transfer-to-clearing` — note is optional context for the lock. */
data class TransferToClearingRequest(
    val note: String? = null,
)
