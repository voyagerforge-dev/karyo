package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.spi.OpenPickGuard
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Service-level tests (real beans, no mocks) for [OpenPickGuard] — the inventory-api READ
 * contract implemented in fulfillment-core that changeClient uses to refuse reassigning
 * stock that is still referenced by a non-terminal pick.
 *
 * Fixture idiom from [PickOrderPersistenceTest]: persist [PickOrder]/[Pick] directly via
 * repositories inside the test's transaction; stock unit ids AND deliveryOrderId are
 * nanoTime-derived so rows left behind by other tests on the shared DB can never collide with
 * ours (deliveryOrderId in particular must never be a small literal -- a real order created by
 * an unrelated test later in the same run can be assigned that exact id, and
 * PackingService.openPacking keys its pick-order/shipment lookups on it).
 */
@QuarkusTest
class OpenPickGuardTest {

    @Inject
    lateinit var openPickGuard: OpenPickGuard

    @Inject
    lateinit var pickOrderRepository: PickOrderRepository

    @Inject
    lateinit var pickRepository: PickRepository

    private fun uniqueStockUnitId(): Long = System.nanoTime()

    private fun createPick(sourceStockUnitId: Long, state: PickState): Pick {
        val deliveryOrderId = System.nanoTime()
        val po = PickOrder().apply {
            clientId = 1L
            pickOrderNumber = "OPG-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-OPG-$deliveryOrderId"
            this.state = state.code
        }
        pickOrderRepository.persist(po)
        val pick = Pick().apply {
            clientId = 1L
            pickOrderId = po.id!!
            deliveryOrderLineId = 20L
            itemDataId = 30L
            itemDataNumber = "SKU-OPG"
            this.sourceStockUnitId = sourceStockUnitId
            plannedAmount = BigDecimal("5.000")
            this.state = state.code
            pickingType = PickingType.PICK.name
        }
        pickRepository.persist(pick)
        return pick
    }

    @Test
    @Transactional
    fun `a stock unit referenced by a live pick has open picks - in every non-terminal state`() {
        for (open in listOf(PickState.CREATED, PickState.RELEASED, PickState.STARTED)) {
            val suId = uniqueStockUnitId()
            createPick(suId, open)
            assertThat(openPickGuard.hasOpenPicks(listOf(suId)))
                .describedAs("state %s must count as open", open)
                .isTrue()
        }
    }

    @Test
    @Transactional
    fun `a stock unit referenced only by terminal picks has no open picks`() {
        val suId = uniqueStockUnitId()
        createPick(suId, PickState.PICKED)
        createPick(suId, PickState.CANCELED)
        assertThat(openPickGuard.hasOpenPicks(listOf(suId))).isFalse()
    }

    @Test
    fun `empty input returns false without querying`() {
        assertThat(openPickGuard.hasOpenPicks(emptyList())).isFalse()
    }

    @Test
    @Transactional
    fun `batched call over several ids is true when any one of them is mid-pick`() {
        val untouched1 = uniqueStockUnitId()
        val terminal = uniqueStockUnitId()
        val midPick = uniqueStockUnitId()
        val untouched2 = uniqueStockUnitId()
        createPick(terminal, PickState.PICKED)
        createPick(midPick, PickState.STARTED)
        assertThat(openPickGuard.hasOpenPicks(listOf(untouched1, terminal, midPick, untouched2))).isTrue()
        assertThat(openPickGuard.hasOpenPicks(listOf(untouched1, terminal, untouched2))).isFalse()
    }
}
