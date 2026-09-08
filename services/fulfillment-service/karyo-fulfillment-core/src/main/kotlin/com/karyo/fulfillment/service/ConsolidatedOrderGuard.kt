package com.karyo.fulfillment.service

import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.orders.spi.DeliveryOrderLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * B12 ruling (Bulk Allocation Sprint C): a wave member whose picks live on batch PickOrders
 * (deliveryOrderId null) must never get a per-order shipment, because a per-order pack would
 * flip the whole shared cart's stock. Fails fast with a 409 naming the wave.
 *
 * **Scope note.** [BatchPickPort.pickedByLines] returns PICKED slices on batch PickOrders only
 * (the Sprint A sort-station contract) -- it says nothing about a member's picks that are still
 * OPEN on a batch PickOrder. So this guard fires only once the member's batch picks have been
 * confirmed; a member whose batch picks are still open falls through to the pre-existing
 * "no pick order for this order" refusal in [PackingService.openPacking]. That is acceptable:
 * nothing is packable yet either way, so the caller still gets a 409, just a less specific one.
 *
 * **HYBRID members bypass this guard by design (ruling 13, Sprint C final-review fix wave).** A
 * wave released in HYBRID mode gives a member ordinary per-order PickOrders for its COMPLETE lines
 * and cross-order batch PickOrders only for the PICK remainder. [BatchPickPort.pickedByLines]
 * reports the batch slices, so a member with NO batch slice at all -- every line COMPLETE-picked --
 * passes straight through and packs per-order, which is correct: its containers came off its own
 * pick containers, nothing shared. The consequence to be aware of is that such a member can end up
 * holding BOTH a per-order shipment (for its COMPLETE lines) and a place on its consolidation
 * group's shipment (for its batch remainder) -- two live shipments for one delivery order. That is
 * intended, not a duplicate: they carry disjoint quantities, and `ShippingUnitRepository`'s
 * per-slice ledger keeps them from ever packing the same pick twice.
 */
@ApplicationScoped
class ConsolidatedOrderGuard(
    private val batchPickPort: BatchPickPort,
    private val deliveryOrderLookup: DeliveryOrderLookup,
) {
    fun requireNotConsolidated(orderId: Long, clientId: Long) {
        val lineIds = deliveryOrderLookup.findForPicking(orderId, clientId)?.lines?.map { it.lineId } ?: return
        val batchSlice = batchPickPort.pickedByLines(lineIds, clientId).firstOrNull() ?: return
        val waveId = batchPickPort.cartByUnitLoad(batchSlice.cartUnitLoadId, clientId)?.waveId
        throw FulfillmentException.ValidationFailed(
            "delivery order $orderId is consolidated in wave ${waveId ?: "?"}; pack it at its consolidation group",
        )
    }
}
