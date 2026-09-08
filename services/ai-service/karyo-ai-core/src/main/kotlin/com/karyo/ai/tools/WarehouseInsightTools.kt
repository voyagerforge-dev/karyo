package com.karyo.ai.tools

import com.karyo.common.pagination.PaginationParams
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.layout.service.LocationService
import com.karyo.orders.service.OrderService
import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.security.TenantContext
import com.karyo.webhooks.service.WebhookDeliveryService
import com.karyo.webhooks.service.WebhookSubscriptionService
import com.karyo.work.dto.WorkFilter
import com.karyo.work.service.WorkDispatchService
import dev.langchain4j.agent.tool.Tool
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Read-only @Tool beans for the warehouse copilot — batch 2 (Task 5).
 *
 * Covers: locations, open orders, pick orders, shipments, webhook subscriptions,
 * webhook delivery log, work inbox, replenishment needs, and recent outbox activity.
 *
 * All methods return human-readable strings; never throw to the model — every
 * risky call is wrapped in [runCatching]. Tenant scoping via [TenantContext].
 *
 * Field notes (verified against source):
 *  - PaginatedResponse.content (not .items) holds the page list.
 *  - OutboxEvent.created (BaseEntity field, not createdAt) is the timestamp.
 *  - LocationResponse.zone?.name — zone is nullable (unzoned locations).
 *  - WorkDispatchService.available() needs operatorId = tenant.username.
 */
@ApplicationScoped
class WarehouseInsightTools(
    private val orders: OrderService,
    private val pickOrders: PickOrderRepository,
    private val shipments: ShipmentRepository,
    private val webhookSubs: WebhookSubscriptionService,
    private val webhookDeliveries: WebhookDeliveryService,
    private val work: WorkDispatchService,
    private val replen: ReplenishmentService,
    private val outbox: OutboxEventRepository,
    private val locations: LocationService,
    private val tenant: TenantContext,
) {

    @Tool("List storage locations. Optionally filter by zone name (case-insensitive). Returns up to 50 locations with name, zone, lock state and allocation.")
    fun listLocations(zone: String?): String {
        return runCatching {
            val all = locations.listLocations(tenant.clientId)
            val filtered = if (zone != null) {
                val zoneUpper = zone.trim().lowercase()
                all.filter { it.zone?.name?.lowercase()?.contains(zoneUpper) == true }
            } else {
                all
            }.take(50)
            if (filtered.isEmpty()) return ToolFormat.none("locations${if (zone != null) " in zone \"$zone\"" else ""}")
            "name | zone | lock | allocation%\n" + filtered.joinToString("\n") {
                ToolFormat.row(
                    it.name,
                    it.zone?.name ?: "-",
                    it.lockTypeName,
                    "%.1f%%".format(it.allocation.toDouble() * 100.0),
                )
            }
        }.getOrElse { "Error listing locations: ${it.message}" }
    }

    @Tool("List open delivery orders (up to 20 most recent). Returns order number, customer, state and priority.")
    fun listOpenOrders(): String {
        return runCatching {
            val pagination = PaginationParams().apply { page = 0; size = 20 }
            val result = orders.list(tenant.clientId, pagination, null, null)
            if (result.content.isEmpty()) return ToolFormat.none("open orders")
            "orderNumber | customer | state | prio\n" + result.content.joinToString("\n") {
                ToolFormat.row(it.orderNumber, it.customerName ?: "-", it.stateName, it.prio)
            }
        }.getOrElse { "Error listing orders: ${it.message}" }
    }

    @Tool("List pick orders (active fulfillment tasks). Returns pick order number, linked delivery order, state and assigned operator.")
    fun listPickOrders(): String {
        return runCatching {
            val list = pickOrders.findByClient(tenant.clientId).take(20)
            if (list.isEmpty()) return ToolFormat.none("pick orders")
            "pickOrder | deliveryOrder | state | operator\n" + list.joinToString("\n") {
                ToolFormat.row(it.pickOrderNumber, it.deliveryOrderNumber, it.state, it.operatorId ?: "unassigned")
            }
        }.getOrElse { "Error listing pick orders: ${it.message}" }
    }

    @Tool("List recent shipments (up to 20). Returns shipment number, delivery order, state, carrier and tracking number.")
    fun listShipments(): String {
        return runCatching {
            val list = shipments.findByClient(tenant.clientId).take(20)
            if (list.isEmpty()) return ToolFormat.none("shipments")
            "shipment | deliveryOrder | state | carrier | tracking\n" + list.joinToString("\n") {
                ToolFormat.row(
                    it.shipmentNumber,
                    it.deliveryOrderNumber,
                    it.state,
                    it.carrierName ?: "-",
                    it.trackingNumber ?: "-",
                )
            }
        }.getOrElse { "Error listing shipments: ${it.message}" }
    }

    @Tool("List active webhook subscriptions. Returns subscription name, target URL, active status and subscribed event types.")
    fun listWebhooks(): String {
        return runCatching {
            val list = webhookSubs.list(tenant.clientId)
            if (list.isEmpty()) return ToolFormat.none("webhook subscriptions")
            "name | url | active | eventTypes\n" + list.joinToString("\n") {
                ToolFormat.row(it.name, it.targetUrl, it.active, it.eventTypes.joinToString(","))
            }
        }.getOrElse { "Error listing webhooks: ${it.message}" }
    }

    @Tool("Show the 20 most recent webhook delivery attempts. Returns event type, status, HTTP code and any last error.")
    fun recentWebhookDeliveries(): String {
        return runCatching {
            val list = webhookDeliveries.list(tenant.clientId, null, null, 20)
            if (list.isEmpty()) return ToolFormat.none("webhook deliveries")
            "eventType | status | attempts | httpCode | error\n" + list.joinToString("\n") {
                ToolFormat.row(
                    it.eventType,
                    it.status,
                    it.attempts,
                    it.lastResponseCode ?: "-",
                    it.lastError?.take(60) ?: "-",
                )
            }
        }.getOrElse { "Error listing webhook deliveries: ${it.message}" }
    }

    @Tool("Show available work items for the current operator. Returns work type, priority, location, destination and summary.")
    fun myWorkInbox(): String {
        return runCatching {
            val items = work.available(tenant.username, tenant.clientId, WorkFilter())
            if (items.isEmpty()) return ToolFormat.none("work items for ${tenant.username}")
            "type | prio | location | destination | summary\n" + items.take(20).joinToString("\n") {
                ToolFormat.row(it.workType, it.priority, it.primaryLocation ?: "-", it.destination ?: "-", it.summary)
            }
        }.getOrElse { "Error listing work inbox: ${it.message}" }
    }

    @Tool("List replenishment needs — fixed-location assignments below minimum stock. Shows location, item, current vs min amount and whether a task is already open.")
    fun replenishmentNeeds(): String {
        return runCatching {
            val needs = replen.needs(tenant.clientId)
            if (needs.isEmpty()) return ToolFormat.none("replenishment needs")
            "location | item | current | min | belowMin | hasTask\n" + needs.joinToString("\n") {
                ToolFormat.row(
                    it.locationName,
                    it.itemDataNumber ?: "-",
                    it.currentAmount,
                    it.minAmount ?: "-",
                    it.belowMin,
                    it.hasOpenTask,
                )
            }
        }.getOrElse { "Error listing replenishment needs: ${it.message}" }
    }

    @Tool("Show recent warehouse activity from the event log (last N minutes, default 60). Returns event type, entity type, entity id and timestamp.")
    fun recentActivity(minutes: Int): String {
        return runCatching {
            val mins = minutes.coerceIn(1, 1440)
            val since = Instant.now().minusSeconds(mins * 60L)
            val events = outbox.recentForTenant(tenant.clientId, since, 30)
            if (events.isEmpty()) return ToolFormat.none("recent activity in the last $mins minutes")
            "eventType | entityType | entityId | timestamp\n" + events.joinToString("\n") {
                ToolFormat.row(it.eventType, it.aggregateType, it.aggregateId, it.created)
            }
        }.getOrElse { "Error reading recent activity: ${it.message}" }
    }
}
