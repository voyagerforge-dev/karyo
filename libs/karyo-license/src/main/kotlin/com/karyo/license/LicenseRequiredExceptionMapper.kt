package com.karyo.license

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/** Maps [LicenseRequiredException] to 403 with a distinct `.../license-required` problem type. */
@Provider
class LicenseRequiredExceptionMapper : ExceptionMapper<LicenseRequiredException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: LicenseRequiredException): Response {
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/license-required",
            title = "License Required",
            status = 403,
            detail = "The '${exception.moduleKey}' module requires an active license entitlement.",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(problem.status).entity(problem).build()
    }
}
