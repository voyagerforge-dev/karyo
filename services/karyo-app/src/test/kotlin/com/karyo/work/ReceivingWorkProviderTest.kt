package com.karyo.work

import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.messaging.ReceivingWorkProvider
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class ReceivingWorkProviderTest {

    @Inject lateinit var provider: ReceivingWorkProvider
    @Inject lateinit var repository: GoodsReceiptRepository
    @Inject lateinit var tenantContext: TenantContext

    /** Seeds a goods receipt for [clientId], returns its id. */
    @Transactional
    fun seedReceipt(
        clientId: Long,
        state: Int = OrderState.CREATED.code,
        operatorId: String? = null,
        pausedAt: Instant? = null,
    ): Long {
        val seq = System.nanoTime()
        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = "GR-WORK-TEST-$seq"
            this.state = state
            this.operatorId = operatorId
            this.pausedAt = pausedAt
            this.dockLocationId = 5L
            this.dockLocationName = "DOCK-A"
        }
        repository.persist(receipt)
        return receipt.id!!
    }

    @Test
    fun `listOpen surfaces a CREATED unclaimed receipt, claim sets operator and removes it from pool, release restores it`() {
        val clientId = System.nanoTime()
        val id = seedReceipt(clientId)
        tenantContext.clientId = clientId // prime @RequestScoped context for direct SPI calls

        val open = provider.listOpen(WorkFilter(setOf(WorkType.RECEIVE)))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.RECEIVE, id))
        val item = open.first { it.ref == WorkRef(WorkType.RECEIVE, id) }
        assertThat(item.primaryLocationId).isEqualTo(5L)
        assertThat(item.primaryLocation).isEqualTo("DOCK-A")
        assertThat(item.summary).contains("DOCK-A")
        assertThat(item.state).isEqualTo(WorkState.OPEN)

        val claimed = provider.claim(WorkRef(WorkType.RECEIVE, id), "alice")
        assertThat(claimed.state).isEqualTo(WorkState.CLAIMED)
        assertThat(claimed.claimedBy).isEqualTo("alice")
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.RECEIVE))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.RECEIVE, id))
        assertThat(provider.listClaimedBy("alice").map { it.ref }).contains(WorkRef(WorkType.RECEIVE, id))

        provider.release(WorkRef(WorkType.RECEIVE, id), "alice", false)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.RECEIVE))).map { it.ref })
            .contains(WorkRef(WorkType.RECEIVE, id))
    }

    @Test
    fun `listOpen omits a receipt with no dock — summary falls back to receiptNumber only`() {
        val clientId = System.nanoTime()
        val seq = System.nanoTime()
        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = "GR-NODOCK-$seq"
            this.state = OrderState.CREATED.code
        }
        persistReceipt(receipt)
        tenantContext.clientId = clientId

        val item = provider.listOpen(WorkFilter(setOf(WorkType.RECEIVE)))
            .first { it.ref == WorkRef(WorkType.RECEIVE, receipt.id!!) }
        assertThat(item.primaryLocationId).isNull()
        assertThat(item.primaryLocation).isNull()
        assertThat(item.summary).isEqualTo("Receive ${receipt.receiptNumber}")
    }

    @Transactional
    fun persistReceipt(receipt: GoodsReceipt) {
        repository.persist(receipt)
    }

    @Test
    fun `claim of an already-claimed receipt throws WorkClaimConflictException`() {
        val clientId = System.nanoTime()
        val id = seedReceipt(clientId, operatorId = "bob")
        tenantContext.clientId = clientId

        assertThatThrownBy { provider.claim(WorkRef(WorkType.RECEIVE, id), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$id")
    }

    @Test
    fun `claim of a nonexistent id throws WorkClaimConflictException (dispatch loop delete-race path)`() {
        val clientId = System.nanoTime()
        tenantContext.clientId = clientId
        val nonExistentId = Long.MAX_VALUE - 3 // guaranteed not in DB
        assertThatThrownBy { provider.claim(WorkRef(WorkType.RECEIVE, nonExistentId), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$nonExistentId")
    }

    @Test
    fun `a paused receipt is absent from both listOpen and listClaimedBy`() {
        val clientId = System.nanoTime()
        val id = seedReceipt(clientId, state = OrderState.STARTED.code, operatorId = "carol", pausedAt = Instant.now())
        tenantContext.clientId = clientId

        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.RECEIVE))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.RECEIVE, id))
        assertThat(provider.listClaimedBy("carol").map { it.ref })
            .doesNotContain(WorkRef(WorkType.RECEIVE, id))
    }

    @Test
    fun `a FINISHED receipt is absent from listOpen`() {
        val clientId = System.nanoTime()
        val id = seedReceipt(clientId, state = OrderState.FINISHED.code)
        tenantContext.clientId = clientId

        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.RECEIVE))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.RECEIVE, id))
    }
}
