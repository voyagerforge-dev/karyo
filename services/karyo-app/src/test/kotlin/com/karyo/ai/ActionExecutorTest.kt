package com.karyo.ai

import com.karyo.ai.exception.CopilotForbiddenException
import com.karyo.ai.proposal.ActionExecutor
import com.karyo.ai.proposal.ActionProposal
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.ShippingService
import com.karyo.inventory.service.StockService
import com.karyo.inventory.service.UnitLoadService
import com.karyo.layout.service.LocationService
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Plain unit test for ActionExecutor (Task 8 TDD — RED first, then GREEN after ActionExecutor is created).
 *
 * No CDI / @QuarkusTest — services are mocked with MockK.
 */
class ActionExecutorTest {

    private val stock = mockk<StockService>(relaxed = true)
    // Prime the write role: ActionExecutor re-checks the caller's role at confirm time,
    // so the executor would otherwise reject (CopilotForbiddenException) a role-less tenant.
    private val tenant = TenantContext().apply {
        clientId = 1L
        roles = setOf("inventory-write", "fulfillment-write")
    }

    private fun buildExecutor(
        stock: StockService = this.stock,
        tenant: TenantContext = this.tenant,
    ) = ActionExecutor(
        stock = stock,
        unitLoads = mockk(relaxed = true),
        picking = mockk(relaxed = true),
        shipping = mockk(relaxed = true),
        locations = mockk(relaxed = true),
        tenant = tenant,
        sequenceNumberService = mockk(relaxed = true),
    )

    @Test
    fun `executes receiveStock by calling StockService createStock`() {
        every { stock.createStock(any(), tenant) } returns mockk(relaxed = true) { every { id } returns 99L }

        val exec = buildExecutor(stock = stock, tenant = tenant)
        val p = ActionProposal(
            id = "p1",
            sessionId = "s1",
            toolName = "receiveStock",
            summary = "Receive 100 × DEMO-MOUSE",
            params = mapOf(
                "productId" to 7L,
                "itemDataNumber" to "DEMO-MOUSE",
                "amount" to 100,
                "unitLoadId" to 5L,
            ),
            createdAt = Instant.now(),
            owner = "alice",
        )

        val out = exec.execute(p)

        verify {
            stock.createStock(
                match { it.itemDataId == 7L && it.amount == BigDecimal(100) && it.unitLoadId == 5L },
                tenant,
            )
        }
        assertTrue(out.contains("99") || out.lowercase().contains("received"))
    }

    @Test
    fun `execute throws CopilotForbiddenException when caller lacks the write role`() {
        val readOnly = TenantContext().apply {
            clientId = 1L
            roles = setOf("inventory-read")
        }
        val exec = buildExecutor(stock = stock, tenant = readOnly)
        val p = ActionProposal(
            id = "p1",
            sessionId = "s1",
            toolName = "receiveStock",
            summary = "Receive 100 × DEMO-MOUSE",
            params = mapOf("productId" to 7L, "itemDataNumber" to "DEMO-MOUSE", "amount" to 100, "unitLoadId" to 5L),
            createdAt = Instant.now(),
            owner = "alice",
        )

        assertThrows(CopilotForbiddenException::class.java) { exec.execute(p) }
        // The mutation must NOT have been attempted.
        verify(exactly = 0) { stock.createStock(any(), any()) }
    }
}
