package com.karyo.ai.proposal

import com.karyo.ai.exception.CopilotForbiddenException
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.ShippingService
import com.karyo.inventory.api.dto.CreateStockUnitRequest
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.service.StockService
import com.karyo.inventory.service.UnitLoadService
import com.karyo.layout.service.LocationService
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal

/**
 * ActionExecutor (Task 8): dispatches on [ActionProposal.toolName] and performs the
 * mutation that was gated behind the confirm-before-execute flow.
 *
 * Inventory actions use [TenantContext] explicitly (StockService / UnitLoadService APIs
 * require a tenant param). Fulfillment actions (PickOrderService / ShippingService)
 * inject TenantContext themselves, so ActionExecutor only passes what each service needs.
 *
 * newState parsing for changeStockState: tries enum name first ("ON_STOCK"),
 * falls back to numeric code ("300"). Unknown → safe string, nothing executed.
 *
 * putawayStock amount: NOT stored in params at proposal time — read from DB here
 * via StockService.findById before calling transferStock.
 *
 * createUnitLoad labelId: generated via SequenceNumberService ("AI-{millis}-{3-digit}",
 * SC17 — was a truncated UUID with no conflict check).
 */
@ApplicationScoped
class ActionExecutor(
    private val stock: StockService,
    private val unitLoads: UnitLoadService,
    private val picking: PickOrderService,
    private val shipping: ShippingService,
    private val locations: LocationService,
    private val tenant: TenantContext,
    private val sequenceNumberService: SequenceNumberService,
) {
    @Transactional
    fun execute(p: ActionProposal): String {
        // Re-authorize at confirm time: the /ai/confirm endpoint is only gated on
        // inventory-read, but executing a proposal performs a WRITE. Verify the current
        // user actually holds the write role this action requires (mirrors the create-time
        // guard in WarehouseActionTools). Throws -> mapped to HTTP 403 by CopilotResource.
        val requiredRole = requiredWriteRole(p.toolName)
        if (requiredRole != null && !tenant.roles.contains(requiredRole)) {
            throw CopilotForbiddenException(
                "You don't have permission to execute ${p.toolName} (needs $requiredRole role).",
            )
        }
        val a = p.params
        return when (p.toolName) {
            "receiveStock" -> executeReceiveStock(a)
            "putawayStock" -> executePutawayStock(a)
            "changeStockState" -> executeChangeStockState(a)
            "createUnitLoad" -> executeCreateUnitLoad(a)
            "pickOrder" -> executePickOrder(a)
            "shipShipment" -> executeShipShipment(a)
            else -> "Unknown action ${p.toolName}; nothing executed."
        }
    }

    /**
     * Single source of truth for which write role each action requires — kept here
     * (the executor is the only thing that knows what each toolName mutates) so the
     * create-time guard in WarehouseActionTools and this confirm-time re-check stay
     * aligned. Returns null for unknown toolNames (the else branch executes nothing).
     */
    /** Exposed as internal so [com.karyo.ai.api.v1.CopilotResource] can pre-check the role
     *  before consuming the proposal (pop). The executor's own check inside [execute] is kept
     *  unchanged as a second line of defense (defense-in-depth). */
    internal fun requiredWriteRole(toolName: String): String? = TOOL_WRITE_ROLES[toolName]

    companion object {
        /** unit_loads.label_id is VARCHAR(255). */
        private const val MAX_LABEL_LENGTH = 255

        private val TOOL_WRITE_ROLES: Map<String, String> = mapOf(
            "receiveStock" to "inventory-write",
            "putawayStock" to "inventory-write",
            "changeStockState" to "inventory-write",
            "createUnitLoad" to "inventory-write",
            "pickOrder" to "fulfillment-write",
            "shipShipment" to "fulfillment-write",
        )
    }

    private fun executeReceiveStock(a: Map<String, Any?>): String {
        val req = CreateStockUnitRequest(
            itemDataId = (a["productId"] as Number).toLong(),
            itemDataNumber = a["itemDataNumber"] as String,
            amount = BigDecimal(a["amount"].toString()),
            unitLoadId = (a["unitLoadId"] as Number).toLong(),
            state = StockState.ON_STOCK.code,
            activityCode = "AI_COPILOT",
        )
        val su = stock.createStock(req, tenant)
        return "Received — stock unit ${su.id} created."
    }

    private fun executePutawayStock(a: Map<String, Any?>): String {
        val stockUnitId = (a["stockUnitId"] as Number).toLong()
        val targetUnitLoadId = (a["targetUnitLoadId"] as Number).toLong()
        // Amount not stored in params at proposal time — read from DB
        val su = stock.findById(stockUnitId, tenant)
        val result = stock.transferStock(stockUnitId, targetUnitLoadId, su.amount, "AI_COPILOT", tenant)
        return "Moved — stock unit ${result.id} transferred to unit load $targetUnitLoadId."
    }

    private fun executeChangeStockState(a: Map<String, Any?>): String {
        val stockUnitId = (a["stockUnitId"] as Number).toLong()
        val newStateStr = a["newState"]?.toString() ?: ""
        // Try enum name first (e.g., "ON_STOCK"), then numeric code (e.g., "300")
        val newStateCode = runCatching { StockState.valueOf(newStateStr).code }
            .getOrElse {
                runCatching { StockState.fromCode(newStateStr.toInt()).code }
                    .getOrElse { return "Unknown state '$newStateStr'; nothing executed." }
            }
        val su = stock.changeState(stockUnitId, newStateCode, tenant)
        return "State changed — stock unit ${su.id} now ${su.state} (${StockState.fromCode(su.state).name})."
    }

    private fun executeCreateUnitLoad(a: Map<String, Any?>): String {
        val unitLoadTypeId = (a["unitLoadTypeId"] as Number).toLong()
        val locationId = (a["locationId"] as Number).toLong()
        val location = locations.findById(locationId, tenant.clientId)
        // Attribution is the acting principal's own client. The proposal map is
        // LLM-generated and there is no Client entity to validate an arbitrary owner
        // against, so a proposal-supplied clientId is deliberately NOT honored —
        // it would let the copilot attribute goods to the wrong customer. Cross-owner
        // AI actions need a validated work-item owner, not a free-form field.
        val owner = tenant.clientId
        // SC17: was a truncated UUID with no conflict check. unit_loads.label_id is VARCHAR(255)
        // and globally unique (not scoped to `owner`). ai-core has no direct UnitLoadRepository
        // (that's inventory-core-internal, never exposed past its service layer), so the already-
        // injected UnitLoadService.findByLabelId is the uniqueness check -- no new cross-module
        // Gradle edge, ai-core already depends on inventory-core for `unitLoads` itself.
        // findByLabelId throws NotFound both for a genuinely absent label AND one outside the
        // caller's readScope, so either outcome is correctly treated as "unique from here" -- the
        // DB's UNIQUE constraint on label_id is the ultimate guard for the (now much rarer,
        // millis+random) cross-owner collision case.
        val labelId = sequenceNumberService.next("ai.unitLoadLabel", "AI", owner, MAX_LABEL_LENGTH) { candidate ->
            runCatching { unitLoads.findByLabelId(candidate, tenant) }.isFailure
        }
        val req = CreateUnitLoadRequest(
            clientId = owner,
            labelId = labelId,
            unitLoadTypeId = unitLoadTypeId,
            storageLocationId = locationId,
            storageLocationName = location.name,
        )
        val ul = unitLoads.create(req, tenant)
        return "Created unit load ${ul.id} (label $labelId) at ${location.name}."
    }

    private fun executePickOrder(a: Map<String, Any?>): String {
        val deliveryOrderId = (a["deliveryOrderId"] as Number).toLong()
        // Row 8: releaseToPicking now returns a list (createTypeOrders can split one release into
        // several PickOrders) -- report every one created, not just the first.
        val pickOrders = picking.releaseToPicking(deliveryOrderId)
        val label = pickOrders.joinToString(", ") { "${it.id} (${it.pickOrderNumber})" }
        return "Released to picking - pick order(s): $label."
    }

    private fun executeShipShipment(a: Map<String, Any?>): String {
        val shipmentId = (a["shipmentId"] as Number).toLong()
        val carrierName = a["carrierName"] as String
        val carrierService = a["carrierService"] as String
        val shipment = shipping.manifest(shipmentId, carrierName, carrierService, null)
        return "Manifested — shipment ${shipment.id} via $carrierName ($carrierService)."
    }
}
