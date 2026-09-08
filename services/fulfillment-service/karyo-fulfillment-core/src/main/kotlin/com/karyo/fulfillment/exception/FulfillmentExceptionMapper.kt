package com.karyo.fulfillment.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class FulfillmentExceptionMapper : ExceptionMapper<FulfillmentException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: FulfillmentException): Response {
        val (status, type) = when (exception) {
            is FulfillmentException.NotFound -> 404 to "pick-not-found"
            is FulfillmentException.NotReleasable -> 409 to "order-not-releasable"
            is FulfillmentException.InvalidPickConfirmation -> 422 to "invalid-pick-confirmation"
            is FulfillmentException.NotPackable -> 409 to "order-not-packable"
            is FulfillmentException.InvalidPackRequest -> 422 to "invalid-pack-request"
            is FulfillmentException.NotShippable -> 409 to "shipment-not-shippable"
            is FulfillmentException.NotCancelable -> 409 to "shipment-not-cancelable"
            is FulfillmentException.DocumentNotReady -> 409 to "document-not-ready"
            is FulfillmentException.ValidationFailed -> 409 to "fulfillment-validation-failed"
            is FulfillmentException.AllPicksConsumedByExtension -> 409 to "all-picks-consumed-by-extension"
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
}
