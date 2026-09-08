package com.karyo.work.exception

import com.karyo.common.exception.ProblemDetail
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class WorkClaimConflictMapper : ExceptionMapper<WorkClaimConflictException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(e: WorkClaimConflictException): Response {
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/work-claim-conflict",
            title = "Work claim conflict",
            status = 409,
            detail = e.message ?: "Already claimed",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(Response.Status.CONFLICT).entity(problem).build()
    }
}

/** Maps [UnknownWorkingAreaException] (Task 8's `?workingAreaId=` filter) to 400. */
@Provider
class UnknownWorkingAreaMapper : ExceptionMapper<UnknownWorkingAreaException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(e: UnknownWorkingAreaException): Response {
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/unknown-working-area",
            title = "Unknown working area",
            status = 400,
            detail = e.message ?: "Unknown working area",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(Response.Status.BAD_REQUEST).entity(problem).build()
    }
}
