package com.karyo.fulfillment.exception

import com.karyo.common.exception.KaryoException

sealed class FulfillmentException(message: String) : KaryoException(message) {
    class NotFound(entityType: String, identifier: Any) :
        FulfillmentException("$entityType not found: $identifier")

    class NotReleasable(orderId: Long, detail: String) :
        FulfillmentException("Order $orderId cannot be released to picking: $detail")

    class InvalidPickConfirmation(detail: String) :
        FulfillmentException("Invalid pick confirmation: $detail")

    class NotPackable(orderId: Long, detail: String) :
        FulfillmentException("Order $orderId cannot be packed: $detail")

    class InvalidPackRequest(detail: String) :
        FulfillmentException("Invalid pack request: $detail")

    class NotShippable(shipmentId: Long, detail: String) :
        FulfillmentException("Shipment $shipmentId cannot be shipped: $detail")

    /** S4: cancel/removeUnit/removeLine refused once a shipment has passed PACKED (manifested/shipped). */
    class NotCancelable(shipmentId: Long, detail: String) :
        FulfillmentException("Shipment $shipmentId cannot be canceled or modified: $detail")

    class DocumentNotReady(shipmentId: Long, documentType: String, detail: String) :
        FulfillmentException("Document $documentType for shipment $shipmentId is not ready: $detail")

    class ValidationFailed(detail: String) : FulfillmentException("Validation failed: $detail")

    /**
     * Row 16 (PickingOrderPrepareEvent): every candidate pick for [deliveryOrderId] was consumed
     * by an extension observer — no PickOrder was created. Not a failure of the release itself
     * (the reservations are still intact; the extension took ownership of materializing them),
     * so the REST caller gets a clear signal rather than a fabricated empty PickOrder.
     */
    class AllPicksConsumedByExtension(deliveryOrderId: Long) :
        FulfillmentException(
            "All planned picks for DeliveryOrder $deliveryOrderId were consumed by an extension " +
                "observer; no PickOrder was created",
        )
}
