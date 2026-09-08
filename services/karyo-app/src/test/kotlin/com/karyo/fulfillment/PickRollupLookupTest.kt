package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.spi.PickRollupLookup
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * SPI-level pin of the D4 pick rollup: real [PickRollupLookup] bean + real repository/DB, picks
 * persisted directly (deliveryOrderLineId is a cross-module id — no FK, so synthetic unique line
 * ids are enough). Only PICKED(600) picks count, and ordered-SKU vs substitute-SKU quantities
 * are split (substitution follow-ups share the parent's line id with a DIFFERENT SKU).
 */
@QuarkusTest
class PickRollupLookupTest {

    @Inject lateinit var pickRollupLookup: PickRollupLookup
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var tenantContext: TenantContext

    @Transactional
    fun persistPick(
        lineId: Long,
        picked: String,
        pickState: PickState,
        substitutedFrom: Long? = null,
        client: Long = 1L,
    ) {
        // picks.pick_order_id has an FK to pick_orders — persist a real parent per pick.
        val parent = PickOrder().apply {
            clientId = client
            pickOrderNumber = "PO-D4-${System.nanoTime()}"
            deliveryOrderId = lineId
            deliveryOrderNumber = "ORD-D4"
            state = PickState.RELEASED.code
        }
        pickOrderRepository.persist(parent)
        pickRepository.persist(
            Pick().apply {
                clientId = client
                pickOrderId = parent.id!!
                deliveryOrderLineId = lineId
                itemDataId = 30L
                itemDataNumber = "SKU-ROLLUP"
                sourceStockUnitId = 40L
                plannedAmount = BigDecimal(picked)
                pickedAmount = BigDecimal(picked)
                state = pickState.code
                pickingType = PickingType.PICK.name
                substitutedItemDataId = substitutedFrom
            }
        )
    }

    @Test
    fun `two PICKED picks on the ordered SKU sum into pickedAmount`() {
        tenantContext.clientId = 1L
        val lineId = System.nanoTime()
        persistPick(lineId, "10.000", PickState.PICKED)
        persistPick(lineId, "15.000", PickState.PICKED)

        val rollup = pickRollupLookup.pickedAmountsByLineIds(setOf(lineId)).getValue(lineId)
        assertThat(rollup.pickedAmount).isEqualByComparingTo(BigDecimal(25))
        assertThat(rollup.substitutedAmount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a RELEASED pick is not counted — only PICKED state rolls up`() {
        tenantContext.clientId = 1L
        val lineId = System.nanoTime()
        persistPick(lineId, "10.000", PickState.PICKED)
        // pickedAmount is only ever written at confirm; a non-zero value on a RELEASED row is
        // deliberately corrupt fixture data to prove the state filter (not amount == 0) excludes it.
        persistPick(lineId, "99.000", PickState.RELEASED)

        val rollup = pickRollupLookup.pickedAmountsByLineIds(setOf(lineId)).getValue(lineId)
        assertThat(rollup.pickedAmount).isEqualByComparingTo(BigDecimal(10))
    }

    @Test
    fun `a substitution follow-up lands in substitutedAmount, not pickedAmount`() {
        tenantContext.clientId = 1L
        val lineId = System.nanoTime()
        persistPick(lineId, "45.000", PickState.PICKED)
        persistPick(lineId, "15.000", PickState.PICKED, substitutedFrom = 777L)

        val rollup = pickRollupLookup.pickedAmountsByLineIds(setOf(lineId)).getValue(lineId)
        assertThat(rollup.pickedAmount).isEqualByComparingTo(BigDecimal(45))
        assertThat(rollup.substitutedAmount).isEqualByComparingTo(BigDecimal(15))
    }

    @Test
    fun `substitution picks from TWO different SKUs both fold into substitutedAmount`() {
        tenantContext.clientId = 1L
        val lineId = System.nanoTime()
        persistPick(lineId, "10.000", PickState.PICKED)
        persistPick(lineId, "5.000", PickState.PICKED, substitutedFrom = 555L)
        persistPick(lineId, "7.000", PickState.PICKED, substitutedFrom = 666L)

        val rollup = pickRollupLookup.pickedAmountsByLineIds(setOf(lineId)).getValue(lineId)
        assertThat(rollup.pickedAmount).isEqualByComparingTo(BigDecimal(10))
        assertThat(rollup.substitutedAmount).isEqualByComparingTo(BigDecimal(12))
    }

    @Test
    fun `empty input returns an empty map`() {
        tenantContext.clientId = 1L
        assertThat(pickRollupLookup.pickedAmountsByLineIds(emptySet())).isEmpty()
    }

    @Test
    fun `an absent line id has no entry — callers render zero, never fabricate`() {
        tenantContext.clientId = 1L
        val neverPicked = System.nanoTime()
        assertThat(pickRollupLookup.pickedAmountsByLineIds(setOf(neverPicked))).doesNotContainKey(neverPicked)
    }

    /**
     * Row 20 (V605) null-blast-radius pin: an EXTINGUISH pick has `deliveryOrderLineId = null`,
     * so it can never match a `lineIds in (...)` predicate built from real DeliveryOrder line ids
     * — [com.karyo.fulfillment.repository.PickRepository.sumPickedByLineIds]'s `p.deliveryOrderLineId
     * in :lineIds` clause excludes it for free (SQL `IN` never matches NULL), with no special-case
     * code needed. Seeds a mixed scenario: a real ordered-SKU PICKED pick on [lineId] alongside an
     * unrelated PICKED EXTINGUISH pick (null line, its own PickOrder) sharing the same client, and
     * asserts the rollup is untouched by the EXT pick's existence.
     */
    @Transactional
    fun persistExtinguishPick(picked: String, client: Long = 1L) {
        val parent = PickOrder().apply {
            clientId = client
            pickOrderNumber = "EXT-${System.nanoTime()}"
            deliveryOrderId = null
            deliveryOrderNumber = null
            state = PickState.RELEASED.code
        }
        pickOrderRepository.persist(parent)
        pickRepository.persist(
            Pick().apply {
                clientId = client
                pickOrderId = parent.id!!
                deliveryOrderLineId = null
                itemDataId = 30L
                itemDataNumber = "SKU-ROLLUP"
                sourceStockUnitId = 41L
                plannedAmount = BigDecimal(picked)
                pickedAmount = BigDecimal(picked)
                state = PickState.PICKED.code
                pickingType = PickingType.EXTINGUISH.name
            },
        )
    }

    @Test
    fun `an EXTINGUISH pick (null line id) never pollutes another line's rollup, and never NPEs the query`() {
        tenantContext.clientId = 1L
        val lineId = System.nanoTime()
        persistPick(lineId, "10.000", PickState.PICKED)
        persistExtinguishPick("999.000") // deliberately huge -- would be obvious if it leaked in

        val rollup = pickRollupLookup.pickedAmountsByLineIds(setOf(lineId)).getValue(lineId)
        assertThat(rollup.pickedAmount).isEqualByComparingTo(BigDecimal(10))
        assertThat(rollup.substitutedAmount).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
