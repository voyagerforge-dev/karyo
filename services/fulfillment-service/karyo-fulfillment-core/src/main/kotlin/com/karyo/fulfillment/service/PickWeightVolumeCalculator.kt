package com.karyo.fulfillment.service

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.vo.PickState
import com.karyo.product.spi.ProductMeasures
import java.math.BigDecimal

/**
 * WORKLIST row 19: order-level weight/volume, computed ON READ -- never a stored column
 * (sprint adjudication 5). Mirrors myWMS `PickingBusiness.calculateWeight`/`calculateVolume`'s
 * two-aggregate pattern (behavioral parity, independent implementation -- built from the
 * picking-block sprint plan's digest of the legacy semantics):
 *
 * - **Unpicked** picks (`state < PICKED`): planned amount x measure.
 * - **Picked** picks (`state == PICKED`): the ACTUAL picked amount x measure -- not planned, so
 *   a short pick's contribution reflects what actually left the shelf.
 * - **CANCELED** picks (and any other terminal state at/past PICKED that isn't PICKED itself)
 *   are excluded entirely from both aggregates.
 * - A pick whose product carries no measure for a dimension (absent from
 *   [measuresByItemId], or present with a `null` field) contributes exactly 0 to that
 *   aggregate's sum -- never fabricated, never skipped as a special case. The total is `null`
 *   **iff the summed contribution is exactly zero** -- this is myWMS's actual rule: an order
 *   whose products only PARTIALLY carry a measure still returns the honest partial sum, not
 *   null; only an all-zero/all-missing total returns null. Honest-null applies at the SUM
 *   level, not per product.
 *
 * Pure arithmetic -- no repository/lookup calls of its own. Callers (see
 * [com.karyo.fulfillment.api.v1.PickOrderResource]) pre-fetch [measuresByItemId] via ONE
 * [com.karyo.product.spi.ProductLookup.findMeasuresByIds] call per response batch (a single
 * order, or a whole list page) to avoid an N+1.
 */
object PickWeightVolumeCalculator {

    data class WeightVolume(val weight: BigDecimal?, val volume: BigDecimal?)

    fun compute(picks: List<Pick>, measuresByItemId: Map<Long, ProductMeasures>): WeightVolume {
        var weightSum = BigDecimal.ZERO
        var volumeSum = BigDecimal.ZERO
        for (pick in picks) {
            val amount = when {
                pick.state == PickState.PICKED.code -> pick.pickedAmount
                pick.state < PickState.PICKED.code -> pick.plannedAmount
                else -> null // CANCELED (or any future post-PICKED state): excluded entirely
            } ?: continue
            val measures = measuresByItemId[pick.itemDataId] ?: continue
            measures.weight?.let { weightSum = weightSum.add(amount.multiply(it)) }
            measures.volume?.let { volumeSum = volumeSum.add(amount.multiply(it)) }
        }
        return WeightVolume(
            weight = weightSum.takeIf { it.signum() != 0 },
            volume = volumeSum.takeIf { it.signum() != 0 },
        )
    }
}
