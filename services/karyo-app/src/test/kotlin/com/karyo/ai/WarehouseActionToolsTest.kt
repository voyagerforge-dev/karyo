package com.karyo.ai

import com.karyo.ai.proposal.ActionProposalStore
import com.karyo.ai.service.CopilotSession
import com.karyo.ai.tools.ToolIds
import com.karyo.ai.tools.WarehouseActionTools
import com.karyo.product.dto.ProductResponse
import com.karyo.product.service.ProductService
import com.karyo.security.TenantContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for WarehouseActionTools (Task 7).
 *
 * Auth-field note: @RolesAllowed in Quarkus checks SecurityIdentity.roles, which
 * TenantFilter maps to TenantContext.roles (NOT .permissions — that's a separate
 * custom JWT claim). Tests prime TenantContext.roles to mirror production behaviour.
 */
class WarehouseActionToolsTest {

    private val products = mockk<ProductService>()
    private val ids = mockk<ToolIds> { every { next() } returns "fixed-id" }
    private val session = CopilotSession().apply { sessionId = "s1" }

    private fun tenant(vararg roles: String) = TenantContext().apply {
        clientId = 1L
        username = "alice"
        this.roles = roles.toSet()
    }

    private fun product() = mockk<ProductResponse>(relaxed = true) {
        every { id } returns 7L
        every { number } returns "DEMO-MOUSE"
        every { name } returns "Wireless Mouse"
    }

    // -------------------------------------------------------------------------
    // receiveStock
    // -------------------------------------------------------------------------

    @Test
    fun `receiveStock with inventory-write role creates a proposal`() {
        every { products.findByNumber("DEMO-MOUSE", 1L) } returns product()
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-write"), session)

        val out = tools.receiveStock("DEMO-MOUSE", 100, 1L)

        assertTrue(out.lowercase().contains("confirm"), "Response should mention confirmation, got: $out")
        val p = store.pop("fixed-id")
        assertNotNull(p, "Proposal should have been stored")
        assertTrue(p!!.toolName == "receiveStock", "Expected toolName=receiveStock, got ${p.toolName}")
        assertTrue(p.params["productId"] == 7L, "Expected productId=7L, got ${p.params["productId"]}")
        assertTrue(p.params["amount"] == 100, "Expected amount=100, got ${p.params["amount"]}")
        assertTrue(p.sessionId == "s1", "Expected sessionId=s1, got ${p.sessionId}")
        assertTrue(p.owner == "alice", "Expected owner=alice (tenant.username), got ${p.owner}")
    }

    @Test
    fun `proposal owner is bound to the authenticated username`() {
        every { products.findByNumber("DEMO-MOUSE", 1L) } returns product()
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-write"), session)

        tools.receiveStock("DEMO-MOUSE", 100, 1L)

        val p = store.pop("fixed-id")
        assertNotNull(p)
        assertTrue(p!!.owner == "alice", "owner must equal tenant.username, got ${p.owner}")
    }

    @Test
    fun `receiveStock without inventory-write role is refused and stores nothing`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-read"), session)

        val out = tools.receiveStock("DEMO-MOUSE", 100, 1L)

        assertTrue(out.lowercase().contains("permission"), "Refusal should mention permission, got: $out")
        assertNull(store.pop("fixed-id"), "No proposal should be stored on refusal")
    }

    // -------------------------------------------------------------------------
    // putawayStock
    // -------------------------------------------------------------------------

    @Test
    fun `putawayStock with inventory-write role creates a proposal`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-write"), session)

        val out = tools.putawayStock(42L, 99L)

        assertTrue(out.lowercase().contains("confirm"), "Response should mention confirmation, got: $out")
        val p = store.pop("fixed-id")
        assertNotNull(p)
        assertTrue(p!!.toolName == "putawayStock")
        assertTrue(p.params["stockUnitId"] == 42L)
        assertTrue(p.params["targetUnitLoadId"] == 99L)
    }

    @Test
    fun `putawayStock without inventory-write role is refused`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("viewer"), session)

        val out = tools.putawayStock(42L, 99L)

        assertTrue(out.lowercase().contains("permission"))
        assertNull(store.pop("fixed-id"))
    }

    // -------------------------------------------------------------------------
    // changeStockState
    // -------------------------------------------------------------------------

    @Test
    fun `changeStockState with inventory-write role creates a proposal`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-write"), session)

        val out = tools.changeStockState(55L, "PICKED")

        assertTrue(out.lowercase().contains("confirm"))
        val p = store.pop("fixed-id")
        assertNotNull(p)
        assertTrue(p!!.toolName == "changeStockState")
        assertTrue(p.params["stockUnitId"] == 55L)
        assertTrue(p.params["newState"] == "PICKED")
    }

    // -------------------------------------------------------------------------
    // createUnitLoad
    // -------------------------------------------------------------------------

    @Test
    fun `createUnitLoad with inventory-write role creates a proposal`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-write"), session)

        val out = tools.createUnitLoad(10L, 20L)

        assertTrue(out.lowercase().contains("confirm"))
        val p = store.pop("fixed-id")
        assertNotNull(p)
        assertTrue(p!!.toolName == "createUnitLoad")
        assertTrue(p.params["unitLoadTypeId"] == 10L)
        assertTrue(p.params["locationId"] == 20L)
    }

    // -------------------------------------------------------------------------
    // pickOrder
    // -------------------------------------------------------------------------

    @Test
    fun `pickOrder with fulfillment-write role creates a proposal`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("fulfillment-write"), session)

        val out = tools.pickOrder(77L)

        assertTrue(out.lowercase().contains("confirm"))
        val p = store.pop("fixed-id")
        assertNotNull(p)
        assertTrue(p!!.toolName == "pickOrder")
        assertTrue(p.params["deliveryOrderId"] == 77L)
    }

    @Test
    fun `pickOrder without fulfillment-write role is refused`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("fulfillment-read"), session)

        val out = tools.pickOrder(77L)

        assertTrue(out.lowercase().contains("permission"))
        assertNull(store.pop("fixed-id"))
    }

    // -------------------------------------------------------------------------
    // shipShipment
    // -------------------------------------------------------------------------

    @Test
    fun `shipShipment with fulfillment-write role creates a proposal`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("fulfillment-write"), session)

        val out = tools.shipShipment(88L, "FedEx", "Ground")

        assertTrue(out.lowercase().contains("confirm"))
        val p = store.pop("fixed-id")
        assertNotNull(p)
        assertTrue(p!!.toolName == "shipShipment")
        assertTrue(p.params["shipmentId"] == 88L)
        assertTrue(p.params["carrierName"] == "FedEx")
        assertTrue(p.params["carrierService"] == "Ground")
    }

    @Test
    fun `shipShipment without fulfillment-write role is refused`() {
        val store = ActionProposalStore(ttlSeconds = 600)
        val tools = WarehouseActionTools(products, store, ids, tenant("inventory-write"), session)

        val out = tools.shipShipment(88L, "FedEx", "Ground")

        assertTrue(out.lowercase().contains("permission"))
        assertNull(store.pop("fixed-id"))
    }
}
