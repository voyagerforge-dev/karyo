package com.karyo.ai.tools

import com.karyo.ai.proposal.ActionProposal
import com.karyo.ai.proposal.ActionProposalStore
import com.karyo.ai.service.CopilotSession
import com.karyo.product.service.ProductService
import com.karyo.security.TenantContext
import dev.langchain4j.agent.tool.Tool
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Action @Tool beans for the warehouse copilot (Task 7).
 *
 * Each tool validates the caller's write role (from [TenantContext.roles], which mirrors
 * what @RolesAllowed checks via SecurityIdentity.roles), then builds and stores an
 * [ActionProposal] — it NEVER calls a mutating service method directly.
 * The actual mutation is executed by Task 8's ActionExecutor after the user confirms.
 *
 * Auth note: @RolesAllowed in Quarkus checks SecurityIdentity.roles, mapped by TenantFilter
 * into TenantContext.roles. TenantContext.permissions is a separate custom JWT claim and is
 * NOT checked by @RolesAllowed. Action tools guard on .roles to stay in sync.
 *
 * toolName strings are the stable T8 dispatch contract — do not rename without updating
 * ActionExecutor.
 *
 * T8 param-key contract per tool:
 *   receiveStock    → productId(Long), itemDataNumber(String), amount(Int), unitLoadId(Long)
 *   putawayStock    → stockUnitId(Long), targetUnitLoadId(Long)
 *   changeStockState→ stockUnitId(Long), newState(String)
 *   createUnitLoad  → unitLoadTypeId(Long), locationId(Long)
 *   pickOrder       → deliveryOrderId(Long)
 *   shipShipment    → shipmentId(Long), carrierName(String), carrierService(String)
 */
@ApplicationScoped
class WarehouseActionTools(
    private val products: ProductService,
    private val store: ActionProposalStore,
    private val ids: ToolIds,
    private val tenant: TenantContext,
    private val session: CopilotSession,
) {

    // -------------------------------------------------------------------------
    // Inventory actions — require "inventory-write" role
    // -------------------------------------------------------------------------

    @Tool(
        "Propose receiving stock: add `amount` units of product `productNumber` into unit load `unitLoadId`. " +
            "Requires confirmation before execution.",
    )
    fun receiveStock(productNumber: String, amount: Int, unitLoadId: Long): String {
        if (!tenant.roles.contains("inventory-write"))
            return "You don't have permission to receive stock (needs inventory-write role)."
        val product = runCatching { products.findByNumber(productNumber, tenant.clientId) }.getOrNull()
            ?: return "No product found with number $productNumber."
        val summary = "Receive $amount × ${product.number} (${product.name}) into unit load $unitLoadId"
        store.put(
            ActionProposal(
                id = ids.next(),
                sessionId = session.sessionId,
                toolName = "receiveStock",
                summary = summary,
                params = mapOf(
                    "productId" to product.id,
                    "itemDataNumber" to product.number,
                    "amount" to amount,
                    "unitLoadId" to unitLoadId,
                ),
                createdAt = Instant.now(),
                owner = tenant.username,
            ),
        )
        return "Prepared: $summary. This needs your confirmation before it runs."
    }

    @Tool(
        "Propose moving (putaway) stock unit `stockUnitId` into target unit load `targetUnitLoadId`. " +
            "Requires confirmation before execution.",
    )
    fun putawayStock(stockUnitId: Long, targetUnitLoadId: Long): String {
        if (!tenant.roles.contains("inventory-write"))
            return "You don't have permission to move stock (needs inventory-write role)."
        val summary = "Putaway stock unit $stockUnitId into unit load $targetUnitLoadId"
        store.put(
            ActionProposal(
                id = ids.next(),
                sessionId = session.sessionId,
                toolName = "putawayStock",
                summary = summary,
                params = mapOf(
                    "stockUnitId" to stockUnitId,
                    "targetUnitLoadId" to targetUnitLoadId,
                ),
                createdAt = Instant.now(),
                owner = tenant.username,
            ),
        )
        return "Prepared: $summary. This needs your confirmation before it runs."
    }

    @Tool(
        "Propose changing the state of stock unit `stockUnitId` to `newState` (e.g. ON_STOCK, PICKED, PACKED). " +
            "Requires confirmation before execution.",
    )
    fun changeStockState(stockUnitId: Long, newState: String): String {
        if (!tenant.roles.contains("inventory-write"))
            return "You don't have permission to change stock state (needs inventory-write role)."
        val summary = "Change stock unit $stockUnitId state to $newState"
        store.put(
            ActionProposal(
                id = ids.next(),
                sessionId = session.sessionId,
                toolName = "changeStockState",
                summary = summary,
                params = mapOf(
                    "stockUnitId" to stockUnitId,
                    "newState" to newState,
                ),
                createdAt = Instant.now(),
                owner = tenant.username,
            ),
        )
        return "Prepared: $summary. This needs your confirmation before it runs."
    }

    @Tool(
        "Propose creating a new unit load of type `unitLoadTypeId` at location `locationId`. " +
            "Requires confirmation before execution.",
    )
    fun createUnitLoad(unitLoadTypeId: Long, locationId: Long): String {
        if (!tenant.roles.contains("inventory-write"))
            return "You don't have permission to create unit loads (needs inventory-write role)."
        val summary = "Create unit load (type $unitLoadTypeId) at location $locationId"
        store.put(
            ActionProposal(
                id = ids.next(),
                sessionId = session.sessionId,
                toolName = "createUnitLoad",
                summary = summary,
                params = mapOf(
                    "unitLoadTypeId" to unitLoadTypeId,
                    "locationId" to locationId,
                ),
                createdAt = Instant.now(),
                owner = tenant.username,
            ),
        )
        return "Prepared: $summary. This needs your confirmation before it runs."
    }

    // -------------------------------------------------------------------------
    // Fulfillment actions — require "fulfillment-write" role
    // -------------------------------------------------------------------------

    @Tool(
        "Propose releasing a pick order for delivery order `deliveryOrderId`. " +
            "Requires confirmation before execution.",
    )
    fun pickOrder(deliveryOrderId: Long): String {
        if (!tenant.roles.contains("fulfillment-write"))
            return "You don't have permission to initiate picking (needs fulfillment-write role)."
        val summary = "Release pick order for delivery order $deliveryOrderId"
        store.put(
            ActionProposal(
                id = ids.next(),
                sessionId = session.sessionId,
                toolName = "pickOrder",
                summary = summary,
                params = mapOf(
                    "deliveryOrderId" to deliveryOrderId,
                ),
                createdAt = Instant.now(),
                owner = tenant.username,
            ),
        )
        return "Prepared: $summary. This needs your confirmation before it runs."
    }

    @Tool(
        "Propose dispatching shipment `shipmentId` via carrier `carrierName` using service level `carrierService`. " +
            "Requires confirmation before execution.",
    )
    fun shipShipment(shipmentId: Long, carrierName: String, carrierService: String): String {
        if (!tenant.roles.contains("fulfillment-write"))
            return "You don't have permission to ship shipments (needs fulfillment-write role)."
        val summary = "Ship shipment $shipmentId via $carrierName ($carrierService)"
        store.put(
            ActionProposal(
                id = ids.next(),
                sessionId = session.sessionId,
                toolName = "shipShipment",
                summary = summary,
                params = mapOf(
                    "shipmentId" to shipmentId,
                    "carrierName" to carrierName,
                    "carrierService" to carrierService,
                ),
                createdAt = Instant.now(),
                owner = tenant.username,
            ),
        )
        return "Prepared: $summary. This needs your confirmation before it runs."
    }
}
