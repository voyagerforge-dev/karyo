package com.karyo.ai

import com.karyo.ai.tools.WarehouseInsightTools
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.layout.service.LocationService
import com.karyo.orders.service.OrderService
import com.karyo.replenishment.dto.ReplenishmentNeed
import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.security.TenantContext
import com.karyo.webhooks.service.WebhookDeliveryService
import com.karyo.webhooks.service.WebhookSubscriptionService
import com.karyo.work.service.WorkDispatchService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class WarehouseInsightToolsTest {

    private val orders = mockk<OrderService>(relaxed = true)
    private val pickOrders = mockk<PickOrderRepository>(relaxed = true)
    private val shipments = mockk<ShipmentRepository>(relaxed = true)
    private val webhookSubs = mockk<WebhookSubscriptionService>(relaxed = true)
    private val webhookDeliveries = mockk<WebhookDeliveryService>(relaxed = true)
    private val work = mockk<WorkDispatchService>(relaxed = true)
    private val replen = mockk<ReplenishmentService>(relaxed = true)
    private val outbox = mockk<OutboxEventRepository>(relaxed = true)
    private val locations = mockk<LocationService>(relaxed = true)
    private val tenant = TenantContext().apply {
        clientId = 1L
        username = "testuser"
    }

    private fun buildTools(
        orders: OrderService = this.orders,
        pickOrders: PickOrderRepository = this.pickOrders,
        shipments: ShipmentRepository = this.shipments,
        webhookSubs: WebhookSubscriptionService = this.webhookSubs,
        webhookDeliveries: WebhookDeliveryService = this.webhookDeliveries,
        work: WorkDispatchService = this.work,
        replen: ReplenishmentService = this.replen,
        outbox: OutboxEventRepository = this.outbox,
        locations: LocationService = this.locations,
        tenant: TenantContext = this.tenant,
    ) = WarehouseInsightTools(orders, pickOrders, shipments, webhookSubs, webhookDeliveries, work, replen, outbox, locations, tenant)

    // ── recentActivity ────────────────────────────────────────────────────────

    @Test
    fun `recentActivity summarizes outbox events`() {
        every { outbox.recentForTenant(1L, any(), any()) } returns listOf(
            mockk(relaxed = true) {
                every { eventType } returns "StockReceived"
                every { aggregateType } returns "StockUnit"
                every { aggregateId } returns 42L
            },
        )
        val out = buildTools(outbox = outbox).recentActivity(60)
        assertTrue(out.contains("StockReceived"), "should contain event type")
    }

    @Test
    fun `recentActivity returns none message when outbox is empty`() {
        every { outbox.recentForTenant(1L, any(), any()) } returns emptyList()
        val out = buildTools(outbox = outbox).recentActivity(60)
        assertTrue(out.contains("No "), "should return a none message")
    }

    // ── replenishmentNeeds ────────────────────────────────────────────────────

    @Test
    fun `replenishmentNeeds lists locations below minimum`() {
        val need = ReplenishmentNeed(
            fixAssignmentId = 10L,
            locationId = 20L,
            locationName = "A-01-01",
            itemDataId = 5L,
            itemDataNumber = "ITEM-001",
            currentAmount = BigDecimal("2"),
            minAmount = BigDecimal("10"),
            desiredAmount = BigDecimal("20"),
            belowMin = true,
            hasOpenTask = false,
        )
        every { replen.needs(1L) } returns listOf(need)
        val out = buildTools(replen = replen).replenishmentNeeds()
        assertTrue(out.contains("A-01-01"), "should contain location name")
        assertTrue(out.contains("ITEM-001"), "should contain item number")
    }

    @Test
    fun `replenishmentNeeds returns none message when empty`() {
        every { replen.needs(1L) } returns emptyList()
        val out = buildTools(replen = replen).replenishmentNeeds()
        assertTrue(out.contains("No "), "should return a none message when no needs")
    }

    // ── listOpenOrders ────────────────────────────────────────────────────────

    @Test
    fun `listOpenOrders returns order numbers from OrderService`() {
        val order = mockk<com.karyo.orders.dto.DeliveryOrderResponse>(relaxed = true) {
            every { orderNumber } returns "DO-12345"
            every { stateName } returns "CREATED"
            every { customerName } returns "Acme Corp"
            every { prio } returns 50
        }
        every { orders.list(eq(1L), any(), any(), any()) } returns mockk(relaxed = true) {
            every { content } returns listOf(order)
        }
        val out = buildTools(orders = orders).listOpenOrders()
        assertTrue(out.contains("DO-12345"), "should contain order number")
    }

    // ── listPickOrders ────────────────────────────────────────────────────────

    @Test
    fun `listPickOrders returns pick order numbers`() {
        val pickOrder = mockk<com.karyo.fulfillment.domain.model.PickOrder>(relaxed = true) {
            every { pickOrderNumber } returns "PO-999"
            every { deliveryOrderNumber } returns "DO-001"
            every { state } returns 100
            every { operatorId } returns null
        }
        every { pickOrders.findByClient(1L) } returns listOf(pickOrder)
        val out = buildTools(pickOrders = pickOrders).listPickOrders()
        assertTrue(out.contains("PO-999"), "should contain pick order number")
    }

    // ── listShipments ─────────────────────────────────────────────────────────

    @Test
    fun `listShipments returns shipment numbers`() {
        val shipment = mockk<com.karyo.fulfillment.domain.model.Shipment>(relaxed = true) {
            every { shipmentNumber } returns "SH-777"
            every { deliveryOrderNumber } returns "DO-001"
            every { state } returns 640
            every { carrierName } returns "UPS"
            every { trackingNumber } returns "1Z999"
        }
        every { shipments.findByClient(1L) } returns listOf(shipment)
        val out = buildTools(shipments = shipments).listShipments()
        assertTrue(out.contains("SH-777"), "should contain shipment number")
    }

    // ── listWebhooks ──────────────────────────────────────────────────────────

    @Test
    fun `listWebhooks returns subscription names and urls`() {
        val sub = mockk<com.karyo.webhooks.domain.model.WebhookSubscription>(relaxed = true) {
            every { name } returns "ERP Hook"
            every { targetUrl } returns "https://erp.example.com/events"
            every { active } returns true
            every { eventTypes } returns listOf("DeliveryOrderStateChanged")
        }
        every { webhookSubs.list(1L) } returns listOf(sub)
        val out = buildTools(webhookSubs = webhookSubs).listWebhooks()
        assertTrue(out.contains("ERP Hook"), "should contain subscription name")
        assertTrue(out.contains("https://erp.example.com/events"), "should contain target URL")
    }

    // ── myWorkInbox ───────────────────────────────────────────────────────────

    @Test
    fun `myWorkInbox returns work items for operator`() {
        val item = mockk<com.karyo.work.dto.WorkItem>(relaxed = true) {
            every { summary } returns "Pick 5x ITEM-001 from A-01-01"
            every { workType } returns com.karyo.work.vo.WorkType.PICK
            every { priority } returns 50
            every { primaryLocation } returns "A-01-01"
        }
        every { work.available("testuser", 1L, any()) } returns listOf(item)
        val out = buildTools(work = work).myWorkInbox()
        assertTrue(out.contains("Pick 5x ITEM-001"), "should contain work item summary")
    }

    @Test
    fun `myWorkInbox returns none message when no work items`() {
        every { work.available("testuser", 1L, any()) } returns emptyList()
        val out = buildTools(work = work).myWorkInbox()
        assertTrue(out.contains("No "), "should return a none message when inbox is empty")
    }

    // ── listLocations ─────────────────────────────────────────────────────────

    @Test
    fun `listLocations returns all locations when no zone filter`() {
        val loc = mockk<com.karyo.layout.dto.LocationResponse>(relaxed = true) {
            every { name } returns "A-01-01"
            every { zone } returns mockk(relaxed = true) { every { name } returns "Zone A" }
            every { lockTypeName } returns "UNLOCKED"
            every { allocation } returns BigDecimal("0.5")
        }
        every { locations.listLocations(1L) } returns listOf(loc)
        val out = buildTools(locations = locations).listLocations(null)
        assertTrue(out.contains("A-01-01"), "should contain location name")
    }

    @Test
    fun `listLocations filters by zone name when provided`() {
        val locZoneA = mockk<com.karyo.layout.dto.LocationResponse>(relaxed = true) {
            every { name } returns "A-01-01"
            every { zone } returns mockk(relaxed = true) { every { name } returns "Zone A" }
            every { lockTypeName } returns "UNLOCKED"
            every { allocation } returns BigDecimal("0.3")
        }
        val locZoneB = mockk<com.karyo.layout.dto.LocationResponse>(relaxed = true) {
            every { name } returns "B-01-01"
            every { zone } returns mockk(relaxed = true) { every { name } returns "Zone B" }
            every { lockTypeName } returns "UNLOCKED"
            every { allocation } returns BigDecimal("0.7")
        }
        every { locations.listLocations(1L) } returns listOf(locZoneA, locZoneB)
        val out = buildTools(locations = locations).listLocations("Zone A")
        assertTrue(out.contains("A-01-01"), "should include Zone A location")
        assertFalse(out.contains("B-01-01"), "should not include Zone B location")
    }
}
