package com.karyo.common.exception

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {
    override fun toResponse(exception: ConstraintViolationException): Response {
        val detail = exception.constraintViolations.joinToString("; ") {
            "${it.propertyPath}: ${it.message}"
        }
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/validation-failed",
            title = "Validation Failed",
            status = 400,
            detail = detail,
        )
        return Response.status(400).entity(problem).build()
    }
}
