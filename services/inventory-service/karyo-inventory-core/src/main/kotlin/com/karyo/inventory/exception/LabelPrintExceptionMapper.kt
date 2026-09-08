package com.karyo.inventory.exception

import com.karyo.common.exception.ProblemDetail
import com.karyo.inventory.service.LabelPrintService
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Maps [LabelPrintService]'s two failure modes to RFC 7807. Separate from
 * [InventoryExceptionMapper]/[InventoryException]: these are plain runtime exceptions raised by
 * a service outside the sealed [InventoryException] hierarchy, so they get their own mappers,
 * mirroring `LicenseRequiredExceptionMapper` in `libs/karyo-license`.
 */
@Provider
class NoPrinterConfiguredExceptionMapper : ExceptionMapper<LabelPrintService.NoPrinterConfigured> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: LabelPrintService.NoPrinterConfigured): Response {
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/no-printer-configured",
            title = "No printer configured",
            status = 503,
            detail = "set KARYO_PRINT_URL to host:port of a ZPL printer",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(problem.status).entity(problem).build()
    }
}

@Provider
class PrinterUnreachableExceptionMapper : ExceptionMapper<LabelPrintService.PrinterUnreachable> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: LabelPrintService.PrinterUnreachable): Response {
        val problem = ProblemDetail(
            type = "https://karyo.com/errors/printer-unreachable",
            title = "Printer unreachable",
            status = 502,
            detail = exception.message ?: "printer unreachable",
            instance = uriInfo.requestUri.path,
        )
        return Response.status(problem.status).entity(problem).build()
    }
}
