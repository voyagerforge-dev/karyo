package com.karyo.product.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class ProductExceptionMapper : ExceptionMapper<ProductException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: ProductException): Response {
        val (status, type) = when (exception) {
            is ProductException.NotFound -> 404 to "product-not-found"
            is ProductException.DuplicateSku -> 409 to "duplicate-sku"
            is ProductException.DuplicateBarcode -> 409 to "duplicate-barcode"
            is ProductException.DuplicateSubstitution -> 409 to "duplicate-substitution"
            is ProductException.InvalidStateTransition -> 422 to "invalid-state-transition"
            is ProductException.InvalidConfiguration -> 422 to "invalid-configuration"
            is ProductException.InvalidItemUnit -> 422 to "invalid-item-unit"
            is ProductException.InvalidPackagingUnit -> 422 to "invalid-packaging-unit"
            is ProductException.ValidationFailed -> 400 to "validation-failed"
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
