package com.karyo.orders.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class OrderExceptionMapper : ExceptionMapper<OrderException> {
    @Context
    lateinit var uriInfo: UriInfo

    // Exhaustive sealed-when: one branch per OrderException subtype, by design.
    @Suppress("CyclomaticComplexMethod")
    override fun toResponse(exception: OrderException): Response {
        val (status, type) = when (exception) {
            is OrderException.NotFound -> NOT_FOUND to "not-found"
            is OrderException.InvalidReference -> NOT_FOUND to "invalid-reference"
            is OrderException.DuplicateName -> CONFLICT to "duplicate-name"
            is OrderException.InvalidTransition -> CONFLICT to "invalid-state-transition"
            is OrderException.NotEditable -> CONFLICT to "order-not-editable"
            is OrderException.ReleaseRejected -> CONFLICT to "release-rejected"
            is OrderException.ValidationFailed -> BAD_REQUEST to "validation-failed"
            is OrderException.OverReceipt -> CONFLICT to "over-receipt"
            is OrderException.AsnNotReceivable -> CONFLICT to "asn-not-receivable"
            is OrderException.ReceiptNotReceivable -> CONFLICT to "receipt-not-receivable"
            is OrderException.NotCancelable -> CONFLICT to "not-cancelable"
            is OrderException.UnsupportedLockType -> UNPROCESSABLE to "unsupported-lock-type"
            is OrderException.UnsupportedReceiptType -> UNPROCESSABLE to "unsupported-receipt-type"
            is OrderException.RetourWithAsn -> UNPROCESSABLE to "retour-with-asn"
            is OrderException.AsnDetachConflict -> CONFLICT to "asn-detach-conflict"
            is OrderException.ReceiptConstraintViolation -> UNPROCESSABLE to "receipt-constraint-violation"
            is OrderException.ReceiptClaimConflict -> CONFLICT to "receipt-claim-conflict"
            is OrderException.ReceiptPaused -> CONFLICT to "receipt-paused"
            is OrderException.ReceiptPauseConflict -> CONFLICT to "receipt-pause-conflict"
            is OrderException.DocumentNotReady -> CONFLICT to "document-not-ready"
            is OrderException.InvalidStorageStrategy -> UNPROCESSABLE to "invalid-storage-strategy"
            is OrderException.InvalidDestinationLocation -> UNPROCESSABLE to "invalid-destination-location"
            is OrderException.InvalidReleaseMode -> UNPROCESSABLE to "invalid-release-mode"
            is OrderException.OrderClaimConflict -> CONFLICT to "order-claim-conflict"
        }
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/$type",
            title = type.replace("-", " ").replaceFirstChar { it.uppercase() },
            status = status,
            detail = exception.message ?: "Unknown error",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(status).entity(problem).build()
    }

    companion object {
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val BAD_REQUEST = 400
        private const val UNPROCESSABLE = 422
    }
}
