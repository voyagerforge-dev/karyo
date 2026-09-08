package com.karyo.work

import com.karyo.security.TenantContext
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.messaging.CountWorkProvider
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkRef
import com.karyo.stocktaking.exception.StocktakingException
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
class CountWorkProviderTest {

    @Inject lateinit var provider: CountWorkProvider
    @Inject lateinit var orderRepo: CountOrderRepository
    @Inject lateinit var sessionRepo: CountSessionRepository
    @Inject lateinit var tenantContext: TenantContext

    /** Seeds a CountSession + a GENERATED unclaimed CountOrder, returns the CountOrder id. */
    @Transactional
    fun seedGeneratedCountOrder(clientId: Long): Long {
        val session = CountSession().apply {
            this.clientId = clientId
            sessionNumber = "CS-TEST-$clientId-1"
            state = CountSessionState.OPEN.code
        }
        sessionRepo.persist(session)

        val order = CountOrder().apply {
            this.clientId = clientId
            sessionId = session.id!!
            orderNumber = "CO-TEST-$clientId-1"
            locationId = 1L
            locationName = "A-01-01"
            state = CountOrderState.GENERATED.code
        }
        orderRepo.persist(order)
        return order.id!!
    }

    @Test
    fun `listOpen surfaces a GENERATED count order, claim sets operator and removes it from pool, release restores it`() {
        val clientId = 8202L
        val id = seedGeneratedCountOrder(clientId)
        tenantContext.clientId = clientId  // prime @RequestScoped context for direct SPI calls

        val open = provider.listOpen(WorkFilter(setOf(WorkType.COUNT)))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.COUNT, id))

        val claimed = provider.claim(WorkRef(WorkType.COUNT, id), "alice")
        assertThat(claimed.state).isEqualTo(WorkState.CLAIMED)
        assertThat(claimed.claimedBy).isEqualTo("alice")
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.COUNT))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.COUNT, id))
        assertThat(provider.listClaimedBy("alice").map { it.ref }).contains(WorkRef(WorkType.COUNT, id))

        provider.release(WorkRef(WorkType.COUNT, id), "alice", false)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.COUNT))).map { it.ref })
            .contains(WorkRef(WorkType.COUNT, id))
    }

    @Test
    fun `releasing an unclaimed count order throws StocktakingException for both operator and manager paths`() {
        // clientId 8203 reserved for this test; seedGeneratedCountOrder leaves operatorId = null
        val clientId = 8203L
        val id = seedGeneratedCountOrder(clientId)
        tenantContext.clientId = clientId

        // Non-manager path
        assertThatThrownBy { provider.release(WorkRef(WorkType.COUNT, id), "alice", false) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)
            .hasMessageContaining("not claimed")

        // Manager path — new guard: manager can no longer bypass the unclaimed check
        assertThatThrownBy { provider.release(WorkRef(WorkType.COUNT, id), "alice", true) }
            .isInstanceOf(StocktakingException.InvalidState::class.java)
            .hasMessageContaining("not claimed")
    }

    @Test
    fun `claim of a nonexistent id throws WorkClaimConflictException (dispatch loop delete-race path)`() {
        val clientId = 8202L
        tenantContext.clientId = clientId
        val nonExistentId = Long.MAX_VALUE - 2  // guaranteed not in DB
        assertThatThrownBy { provider.claim(WorkRef(WorkType.COUNT, nonExistentId), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$nonExistentId")
    }
}
