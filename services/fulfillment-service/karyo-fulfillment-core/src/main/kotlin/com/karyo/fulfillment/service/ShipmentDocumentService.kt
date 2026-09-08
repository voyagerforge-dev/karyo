package com.karyo.fulfillment.service

import com.karyo.documents.DocumentRenderer
import com.karyo.documents.DocumentStore
import com.karyo.fulfillment.config.ShipFromConfig
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.spi.DocumentType
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Renders the shipment documents — packing slip (PDF), bill of lading (PDF), shipping label
 * (ZPL), the packet content list (PDF, D8), and the shipment packet list (PDF, D9) — from
 * classpath Qute templates. All but the packet content list are gated by the configurable
 * [DocumentAvailabilityResolver]: a document is refused with [FulfillmentException.DocumentNotReady]
 * until the shipment reaches the gating state. The packet content list carries no such gate
 * (see [packetContentListPdf]).
 *
 * D8 chose this service (not [PickDocumentService]) as the packet content list's home: a
 * `ShippingUnit` is a shipping-document entity, and this method sits beside packing-slip/
 * BOL/label, all of which already read [ShippingUnitRepository] — [PickDocumentService]
 * never touches `ShippingUnit` at all.
 *
 * Read-only: these methods don't mutate, so they aren't `@Transactional` (mirrors
 * [PackingService.getShipment] / [PackingService.unitsOf]).
 */
@ApplicationScoped
class ShipmentDocumentService(
    private val shipmentRepository: ShipmentRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val shipmentOrderRepository: ShipmentOrderRepository,
    private val pickRepository: PickRepository,
    private val stockUnitLookup: StockUnitLookup,
    private val deliveryOrderLookup: DeliveryOrderLookup,
    private val availability: DocumentAvailabilityResolver,
    private val renderer: DocumentRenderer,
    private val shipFrom: ShipFromConfig,
    private val tenantContext: TenantContext,
    private val documentStore: Instance<DocumentStore>,
) {
    /**
     * Sprint C, Task 5: [orderId] narrows a GROUP shipment's slip to one member's section (used
     * by the sort-station/consolidation UI to hand a picker "just this order's boxes"). Omitted
     * (the default) renders every member as its own section; on a per-order shipment [orderId]
     * is a no-op (there is only ever one section, unfiltered, as before this task).
     */
    fun packingSlipPdf(shipmentId: Long, store: Boolean = false, orderId: Long? = null): ByteArray {
        val s = gatedShipment(shipmentId, DocumentType.PACKING_SLIP)
        val bytes = renderer.htmlToPdf(
            renderer.render("/templates/packing-slip.html", slipData(s, orderId), s.clientId),
        )
        archive(store, s.clientId, "shipment", s.id!!, "packing-slip", "application/pdf", bytes)
        return bytes
    }

    fun bolPdf(shipmentId: Long, store: Boolean = false): ByteArray {
        val s = gatedShipment(shipmentId, DocumentType.BOL)
        val bytes = renderer.htmlToPdf(renderer.render("/templates/bol.html", bolData(s), s.clientId))
        archive(store, s.clientId, "shipment", s.id!!, "bol", "application/pdf", bytes)
        return bytes
    }

    fun labelZpl(shippingUnitId: Long, store: Boolean = false): String {
        val clientId = tenantContext.clientId
        val unit = shippingUnitRepository.findByIdAndClient(shippingUnitId, clientId)
            ?: throw FulfillmentException.NotFound("ShippingUnit", shippingUnitId)
        val shipment = gatedShipment(unit.shipmentId, DocumentType.SHIPPING_LABEL)
        // CRITICAL 1 (final-review wave, outbound-completion sprint): "Box i of N" must stay
        // coherent with positionIndex after a removeUnit hard-delete -- N is the highest
        // positionIndex ever issued on this shipment, not the current unit COUNT (which drops
        // below it once a unit is removed). See PackingService.persistUnits' numbering KDoc.
        val totalUnits = shippingUnitRepository.findByShipmentId(shipment.id!!)
            .maxOfOrNull { it.positionIndex } ?: 0
        val zpl = renderer.render(
            "/templates/label.zpl",
            mapOf(
                "shipmentNumber" to shipment.shipmentNumber,
                "shippingUnitNumber" to unit.shippingUnitNumber,
                "trackingNumber" to (unit.trackingNumber ?: shipment.trackingNumber ?: ""),
                "shipTo" to shipToOverrideMap(unit, shipment.deliveryOrderId ?: firstMemberOrderId(shipment)),
                "positionIndex" to unit.positionIndex,
                "totalUnits" to totalUnits,
            ),
            unit.clientId,
        )
        archive(
            store, unit.clientId, "shipping-unit", unit.id!!, "label",
            "text/plain; charset=utf-8", zpl.toByteArray(Charsets.UTF_8),
        )
        return zpl
    }

    /**
     * D9 shipment packet list — one row per [ShippingUnit] on the shipment (unit #, type, weight,
     * tracking, line count, total qty), unlike [packetContentListPdf] which lists one
     * ShippingUnit's SKU-level lines. Gated the same as [packingSlipPdf] (PACKED/650): both are
     * plain listings of an already-packed shipment, needing nothing from the manifest step.
     * Scope note: this collapses the sprint brief's "picking/shipping/delivery lists" wording to
     * ONE shipment-level list — the picking list is [PickDocumentService.pickTicketPdf] (D10),
     * and the delivery grouping is [com.karyo.orders.service.OrderDocumentService.deliveryNotePdf]
     * (D7's note).
     */
    fun packetListPdf(shipmentId: Long, store: Boolean = false): ByteArray {
        val s = gatedShipment(shipmentId, DocumentType.PACKET_LIST)
        val bytes = renderer.htmlToPdf(renderer.render("/templates/packet-list.html", packetListData(s), s.clientId))
        archive(store, s.clientId, "shipment", s.id!!, "packet-list", "application/pdf", bytes)
        return bytes
    }

    /**
     * D8 packet content list — SKU/qty/lot per [ShippingUnitLine], enriched with best-before/
     * serial via the join `line.sourcePickId` -> [com.karyo.fulfillment.domain.model.Pick.targetStockUnitId]
     * -> [StockUnitLookup.findContentRefsByIds]. Deliberately `targetStockUnitId`, not
     * `sourceStockUnitId`: the target is the stock unit the picked amount actually LANDED on
     * (what [PackingService] itself treats as "what's in the box" — see `PackPick.sourceStockUnitId
     * = pick.targetStockUnitId` there), and the two can genuinely disagree.
     * `StockService.resolveTransferTargetStock`'s aggregate-merge matches an existing target
     * stock unit on itemDataId+lotNumber+clientId only and never reconciles bestBefore/serial —
     * a second pick merged onto an already-occupied target keeps the FIRST pick's BB/serial.
     * Joining via the source would print the picked-FROM batch's BB, not the box's actual one.
     * `targetStockUnitId` is null until [PickOrderService.confirmPick] sets it, so the join is
     * null-safe throughout, same as the sourcePickId chain.
     *
     * Not gated by [DocumentAvailabilityResolver]: a content list is a plain listing of what a
     * shipping unit contains, useful the moment the unit exists — unlike the BOL/label, which
     * need a state gate. A broken join (a deleted pick, a pick never confirmed so its target is
     * still null, or a pick whose target stock unit was since deleted) renders "—" for that
     * row's best-before/serial rather than a 500 — the batched lookups below simply omit the
     * id, they never throw.
     */
    fun packetContentListPdf(shippingUnitId: Long, store: Boolean = false): ByteArray {
        val unit = shippingUnitRepository.findByIdAndClient(shippingUnitId, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("ShippingUnit", shippingUnitId)
        val lines = shippingUnitRepository.findLinesByUnitId(unit.id!!)
        val bytes = renderer.htmlToPdf(
            renderer.render("/templates/packet-content-list.html", packetContentData(unit, lines), unit.clientId),
        )
        archive(store, unit.clientId, "shipping-unit", unit.id!!, "content-list", "application/pdf", bytes)
        return bytes
    }

    /**
     * Sprint C, Task 5: [combinedOrderNumber] (was a raw nullable [Shipment.deliveryOrderNumber])
     * and a per-unit "orders" column (see [packetListUnitRow]) -- a group shipment has no order
     * number of its own, so both fall back to its member orders.
     */
    private fun packetListData(s: Shipment): Map<String, Any?> {
        val mem = members(s)
        val numbers = memberNumbers(mem)
        return mapOf(
            "shipmentNumber" to s.shipmentNumber,
            "orderNumber" to combinedOrderNumber(s, mem),
            "stateLabel" to ShipmentState.fromCode(s.state).name,
            "generatedAt" to Instant.now().toString(),
            "units" to shippingUnitRepository.findByShipmentId(s.id!!).map { u -> packetListUnitRow(s, u, numbers) },
        )
    }

    private fun packetListUnitRow(s: Shipment, u: ShippingUnit, numbers: Map<Long, String>): Map<String, Any?> {
        val lines = shippingUnitRepository.findLinesByUnitId(u.id!!)
        val orderIds = lines.mapNotNull { it.deliveryOrderId }.distinct()
        val orders = if (orderIds.isEmpty()) {
            s.deliveryOrderNumber ?: ""
        } else {
            orderIds.joinToString(", ") { numbers[it] ?: "" }
        }
        return mapOf(
            "shippingUnitNumber" to u.shippingUnitNumber,
            "type" to u.type,
            "weight" to u.weight.toPlainString(),
            "tracking" to (u.trackingNumber ?: "—"),
            "lineCount" to lines.size,
            "totalQty" to lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount }.toPlainString(),
            "orders" to orders,
        )
    }

    private fun packetContentData(unit: ShippingUnit, lines: List<ShippingUnitLine>): Map<String, Any?> {
        val pickIds = lines.mapNotNull { it.sourcePickId }.toSet()
        val picksById = pickRepository.findByIdsAndClient(pickIds, tenantContext.clientId).associateBy { it.id!! }
        val stockUnitIds = picksById.values.mapNotNull { it.targetStockUnitId }.toSet()
        val contentRefs = stockUnitLookup.findContentRefsByIds(stockUnitIds)
        return mapOf(
            "shippingUnitNumber" to unit.shippingUnitNumber,
            "generatedAt" to Instant.now().toString(),
            "lines" to lines.map { line ->
                val pick = line.sourcePickId?.let { picksById[it] }
                val ref = pick?.targetStockUnitId?.let { contentRefs[it] }
                mapOf(
                    "itemDataNumber" to line.itemDataNumber,
                    "amount" to line.amount.toPlainString(),
                    "lotNumber" to (line.lotNumber ?: "—"),
                    "bestBefore" to (ref?.bestBefore?.toString() ?: "—"),
                    "serialNumber" to (ref?.serialNumber ?: "—"),
                )
            },
        )
    }

    private fun gatedShipment(shipmentId: Long, type: DocumentType): Shipment {
        val s = shipmentRepository.findByIdAndClient(shipmentId, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("Shipment", shipmentId)
        if (s.state < availability.availableFrom(type)) {
            throw FulfillmentException.DocumentNotReady(shipmentId, type.name, "shipment state ${s.state} below gate")
        }
        return s
    }

    private fun lineMaps(shipmentId: Long): List<Map<String, Any?>> =
        shippingUnitRepository.findByShipmentId(shipmentId)
            .flatMap { u -> shippingUnitRepository.findLinesByUnitId(u.id!!) }
            .map { mapOf("sku" to it.itemDataNumber, "qty" to it.amount.toPlainString()) }

    /**
     * Task 3 (outbound-completion sprint): the packing slip must read as "what's in each box",
     * not one flattened SKU list across every [ShippingUnit] on the shipment -- a multi-packet
     * shipment (P1) otherwise prints an unattributed line list a picker/customer can't reconcile
     * against the actual cartons. Grouped by unit, header per box: `Box {positionIndex}: {unitNumber}`.
     * [bolData] keeps the flat [lineMaps] shape unchanged -- the BOL is a bill of lading (total
     * contents for the carrier), not a per-box pack list, and out of this task's scope.
     *
     * Sprint C, Task 5: [filterOrderId] narrows each unit's [ShippingUnitLine]s to one member
     * order -- a unit that has none of that order's lines is dropped from the result entirely
     * (a cross-order-only container never surfaces on an order it doesn't touch). `null` (a
     * per-order shipment, or an unfiltered group section) keeps every unit's lines as-is.
     */
    private fun unitGroups(shipmentId: Long, filterOrderId: Long?): List<Map<String, Any?>> =
        shippingUnitRepository.findByShipmentId(shipmentId).mapNotNull { u ->
            val allLines = shippingUnitRepository.findLinesByUnitId(u.id!!)
            val lines = if (filterOrderId != null) allLines.filter { it.deliveryOrderId == filterOrderId } else allLines
            if (filterOrderId != null && lines.isEmpty()) {
                null
            } else {
                mapOf(
                    "positionIndex" to u.positionIndex,
                    "unitNumber" to u.shippingUnitNumber,
                    "lines" to lines.map { mapOf("sku" to it.itemDataNumber, "qty" to it.amount.toPlainString()) },
                )
            }
        }

    /**
     * Sprint C, Task 5: per-order packing-slip sections. A per-order shipment (no members) is
     * always ONE unfiltered section, [orderId] a no-op -- the pre-Sprint-C shape. A GROUP
     * shipment renders one section per member ([orderId] `null`), or narrows to exactly the
     * requested member (404 [FulfillmentException.NotFound] when [orderId] isn't one of them).
     */
    private fun orderSections(s: Shipment, orderId: Long?): List<Map<String, Any?>> {
        val mem = members(s)
        if (mem.isEmpty()) {
            return listOf(mapOf("orderNumber" to (s.deliveryOrderNumber ?: ""), "units" to unitGroups(s.id!!, null)))
        }
        if (orderId != null) {
            val member = mem.firstOrNull { it.deliveryOrderId == orderId }
                ?: throw FulfillmentException.NotFound("ShipmentOrder", orderId)
            return listOf(orderSection(s, member))
        }
        return mem.map { orderSection(s, it) }
    }

    private fun orderSection(s: Shipment, member: ShipmentOrder): Map<String, Any?> =
        mapOf("orderNumber" to member.deliveryOrderNumber, "units" to unitGroups(s.id!!, member.deliveryOrderId))

    /**
     * P2 (outbound-completion sprint, Task 2): resolves the label's ship-to from the unit's flat
     * override fields when ANY of them is set, else falls back to the order-level address. A
     * per-parcel label/print override, not multi-drop routing -- one unit either carries the full
     * override address or none of it (partial overrides aren't a supported shape).
     */
    private fun shipToOverrideMap(unit: ShippingUnit, orderId: Long): Map<String, Any?> {
        val hasOverride = listOf(
            unit.shipToName, unit.shipToStreet, unit.shipToStreetNumber,
            unit.shipToZip, unit.shipToCity, unit.shipToCountry,
        ).any { !it.isNullOrBlank() }
        if (!hasOverride) return shipToMap(orderId)
        return mapOf(
            "customerName" to (unit.shipToName ?: ""),
            "street" to (unit.shipToStreet ?: ""),
            "streetNumber" to (unit.shipToStreetNumber ?: ""),
            "zipCode" to (unit.shipToZip ?: ""),
            "city" to (unit.shipToCity ?: ""),
            "country" to (unit.shipToCountry ?: ""),
        )
    }

    private fun shipToMap(orderId: Long): Map<String, Any?> {
        val v = deliveryOrderLookup.findShipTo(orderId)
        return mapOf(
            "customerName" to (v?.customerName ?: ""),
            "street" to (v?.street ?: ""),
            "streetNumber" to (v?.streetNumber ?: ""),
            "zipCode" to (v?.zipCode ?: ""),
            "city" to (v?.city ?: ""),
            "country" to (v?.country ?: ""),
        )
    }

    private fun shipFromMap() = mapOf(
        "name" to shipFrom.name(),
        "street" to shipFrom.street(),
        "city" to shipFrom.city(),
        "zipCode" to shipFrom.zipCode(),
        "country" to shipFrom.country(),
    )

    private fun totalWeight(shipmentId: Long): String =
        shippingUnitRepository.findByShipmentId(shipmentId)
            .fold(BigDecimal.ZERO) { a, u -> a + u.weight }.toPlainString()

    /** Sprint C, Task 5: a GROUP shipment's members ([ShipmentOrder] rows); always empty for a
     *  per-order shipment (never populated, see [ShipmentOrder]'s KDoc). */
    private fun members(s: Shipment): List<ShipmentOrder> =
        if (s.isGroup) shipmentOrderRepository.findByShipmentId(s.id!!, s.clientId) else emptyList()

    /** `orderId -> deliveryOrderNumber` over [mem] -- feeds [packetListUnitRow]'s per-unit "orders". */
    private fun memberNumbers(mem: List<ShipmentOrder>): Map<Long, String> =
        mem.associate { it.deliveryOrderId to it.deliveryOrderNumber }

    /** [Shipment.deliveryOrderNumber] when set (a per-order shipment), else every member's number
     *  joined -- the GROUP-shipment header value ("A, B") shared by the BOL, slip, and packet list. */
    private fun combinedOrderNumber(s: Shipment, mem: List<ShipmentOrder>): String =
        s.deliveryOrderNumber ?: mem.joinToString(", ") { it.deliveryOrderNumber }

    private fun headerData(s: Shipment): Map<String, Any?> {
        val mem = members(s)
        return mapOf(
            "orderNumber" to combinedOrderNumber(s, mem),
            "orders" to mem.map { it.deliveryOrderNumber },
            "shipmentNumber" to s.shipmentNumber,
            "date" to LocalDate.now().toString(),
            "shipTo" to shipToMap(s.deliveryOrderId ?: firstMemberOrderId(s)),
        )
    }

    /**
     * Sprint C: a GROUP shipment has no [Shipment.deliveryOrderId] of its own -- the richer
     * per-order document treatment for a group shipment lands in Task 5; for now this picks the
     * FIRST member (a group shipment always has at least one member by construction, Task 3) so
     * the existing document templates keep resolving a ship-to address.
     */
    private fun firstMemberOrderId(s: Shipment): Long = members(s).first().deliveryOrderId

    /** Task 3/Sprint C Task 5: per-order sections, each `units: [{ positionIndex, unitNumber,
     *  lines }]` -- see [orderSections] for the "why". */
    private fun slipData(s: Shipment, orderId: Long?): Map<String, Any?> = headerData(s) + mapOf(
        "orders" to orderSections(s, orderId),
    )

    private fun bolData(s: Shipment): Map<String, Any?> = headerData(s) + mapOf(
        "lines" to lineMaps(s.id!!),
        "shipFrom" to shipFromMap(),
        "carrierName" to (s.carrierName ?: ""),
        "carrierService" to (s.carrierService ?: ""),
        "trackingNumber" to (s.trackingNumber ?: ""),
        "totalWeight" to totalWeight(s.id!!),
    )

    /**
     * Opt-in archive of a just-rendered document (`?store=true`, Task 2 of the docstore-templates
     * sprint) — a no-op unless [store] is set AND a [DocumentStore] bean is actually wired
     * (karyo-docstore). [ownerClientId] is always the loaded entity's own `clientId`, never the
     * acting principal's, matching the [DocumentStore] contract.
     */
    private fun archive(
        store: Boolean,
        ownerClientId: Long,
        entityType: String,
        entityId: Long,
        documentType: String,
        mediaType: String,
        content: ByteArray,
    ) {
        if (store && documentStore.isResolvable) {
            documentStore.get().store(
                ownerClientId = ownerClientId,
                entityType = entityType,
                entityId = entityId,
                documentType = documentType,
                fileName = "$documentType-$entityId.${if (mediaType == "application/pdf") "pdf" else "zpl"}",
                mediaType = mediaType,
                content = content,
            )
        }
    }
}
