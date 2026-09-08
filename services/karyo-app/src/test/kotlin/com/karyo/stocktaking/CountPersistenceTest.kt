package com.karyo.stocktaking

import com.karyo.stocktaking.domain.model.CountLine
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.repository.CountLineRepository
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class CountPersistenceTest {
    @Inject lateinit var sessions: CountSessionRepository
    @Inject lateinit var orders: CountOrderRepository
    @Inject lateinit var lines: CountLineRepository

    @Test
    @Transactional
    fun `persists a session with an order and a line`() {
        val s = CountSession().apply { clientId = 3301; sessionNumber = "CS-1"; state = CountSessionState.OPEN.code }
        sessions.persist(s)
        val o = CountOrder().apply {
            clientId = 3301; sessionId = s.id!!; orderNumber = "CO-1"
            locationId = 7; locationName = "PICK-7"; state = CountOrderState.GENERATED.code
        }
        orders.persist(o)
        val l = CountLine().apply {
            clientId = 3301; countOrderId = o.id!!; stockUnitId = 42
            itemDataId = 9; itemDataNumber = "SKU-9"; plannedAmount = BigDecimal("10")
        }
        lines.persist(l)

        assertThat(orders.findBySession(s.id!!)).extracting<Long> { it.id }.contains(o.id)
        assertThat(lines.findByOrder(o.id!!)).extracting<Long> { it.id }.contains(l.id)
    }
}
