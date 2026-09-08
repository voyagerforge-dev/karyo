package com.karyo.work

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.messaging.PickWorkProvider
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.vo.PickState
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

@QuarkusTest
class PickWorkProviderTest {

    @Inject lateinit var provider: PickWorkProvider
    @Inject lateinit var repo: PickOrderRepository
    @Inject lateinit var tenantContext: TenantContext

    @Transactional
    fun seedReleasedPickOrder(clientId: Long): Long {
        val o = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "PK-TEST-$clientId-1"
            deliveryOrderId = 0L
            deliveryOrderNumber = "DO-TEST-$clientId-1"
            state = PickState.RELEASED.code
            prio = 50
        }
        repo.persist(o)
        return o.id!!
    }

    @Test
    fun `listOpen surfaces a RELEASED pick order, claim sets operator and removes it from the pool`() {
        val clientId = 8201L
        val id = seedReleasedPickOrder(clientId)
        tenantContext.clientId = clientId  // prime @RequestScoped context for direct SPI calls

        val open = provider.listOpen(WorkFilter(setOf(WorkType.PICK)))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.PICK, id))

        val claimed = provider.claim(WorkRef(WorkType.PICK, id), "alice")
        assertThat(claimed.state).isEqualTo(WorkState.CLAIMED)
        assertThat(claimed.claimedBy).isEqualTo("alice")
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.PICK))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.PICK, id))
        assertThat(provider.listClaimedBy("alice").map { it.ref }).contains(WorkRef(WorkType.PICK, id))

        provider.release(WorkRef(WorkType.PICK, id), "alice", false)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.PICK))).map { it.ref })
            .contains(WorkRef(WorkType.PICK, id))
    }

    @Test
    fun `claim of a nonexistent id throws WorkClaimConflictException (dispatch loop delete-race path)`() {
        val clientId = 8201L
        tenantContext.clientId = clientId
        val nonExistentId = Long.MAX_VALUE - 1  // guaranteed not in DB
        assertThatThrownBy { provider.claim(WorkRef(WorkType.PICK, nonExistentId), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$nonExistentId")
    }

    @Transactional
    fun cancelOrder(id: Long) {
        val order = repo.findById(id)!!
        order.state = PickState.CANCELED.code
        order.operatorId = null
    }

    @Test
    fun `a CANCELED order is absent from listOpen (was RELEASED)`() {
        val clientId = System.nanoTime()
        val id = seedReleasedPickOrder(clientId)
        tenantContext.clientId = clientId

        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.PICK))).map { it.ref })
            .contains(WorkRef(WorkType.PICK, id))

        cancelOrder(id)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.PICK))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.PICK, id))
    }

    @Test
    fun `a CANCELED order is absent from listClaimedBy (was STARTED)`() {
        val clientId = System.nanoTime()
        val id = seedReleasedPickOrder(clientId)
        tenantContext.clientId = clientId
        provider.claim(WorkRef(WorkType.PICK, id), "carol")

        assertThat(provider.listClaimedBy("carol").map { it.ref }).contains(WorkRef(WorkType.PICK, id))

        cancelOrder(id)
        assertThat(provider.listClaimedBy("carol").map { it.ref })
            .doesNotContain(WorkRef(WorkType.PICK, id))
    }

    /** Row 20 (V605) null-blast-radius pin: an EXTINGUISH order has no backing DeliveryOrder. */
    @Transactional
    fun seedReleasedExtinguishOrder(clientId: Long): Long {
        val o = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "EXT-TEST-$clientId-1"
            deliveryOrderId = null
            deliveryOrderNumber = null
            state = PickState.RELEASED.code
            prio = 50
        }
        repo.persist(o)
        return o.id!!
    }

    @Test
    fun `an EXTINGUISH order is visible in the work pool like any other RELEASED order, with a null-safe label`() {
        val clientId = System.nanoTime()
        val id = seedReleasedExtinguishOrder(clientId)
        tenantContext.clientId = clientId

        val open = provider.listOpen(WorkFilter(setOf(WorkType.PICK)))
        val item = open.single { it.ref == WorkRef(WorkType.PICK, id) }
        assertThat(item.summary).doesNotContain("null")
        assertThat(item.summary).contains("EXT-TEST-$clientId-1")
    }
}
