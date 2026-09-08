package com.karyo.inventory.api.v1

import com.karyo.inventory.api.dto.*
import com.karyo.inventory.api.vo.LockType
import com.karyo.security.TenantContext
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.service.LabelPrintService
import com.karyo.inventory.service.UnitLoadDocumentService
import com.karyo.inventory.service.UnitLoadService
import com.karyo.inventory.service.UnitLoadWeightCalculator
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/unit-loads")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class UnitLoadResource(
    private val service: UnitLoadService,
    private val documentService: UnitLoadDocumentService,
    private val weightCalculator: UnitLoadWeightCalculator,
    private val labelPrintService: LabelPrintService,
) {
    @Inject
    lateinit var tenantContext: TenantContext

    @GET
    @RolesAllowed("inventory-read")
    @Transactional
    fun list(@QueryParam("locationId") locationId: Long?): List<UnitLoadResponse> {
        val results = if (locationId != null) {
            service.findByLocation(locationId, tenantContext)
        } else {
            throw com.karyo.inventory.exception.InventoryException.ValidationFailed("locationId query parameter is required")
        }
        return results.map { it.toResponse() }
    }

    @GET @Path("/{id}")
    @RolesAllowed("inventory-read")
    @Transactional
    fun getById(@PathParam("id") id: Long): UnitLoadResponse =
        service.findById(id, tenantContext).toResponse()

    @GET @Path("/by-label/{labelId}")
    @RolesAllowed("inventory-read")
    @Transactional
    fun getByLabel(@PathParam("labelId") labelId: String): UnitLoadResponse =
        service.findByLabelId(labelId, tenantContext).toResponse()

    // D8: unit load content list PDF — a per-pallet manifest, DELETABLE stock excluded.
    // `?store=true` opt-in archives via DocumentStore (Task 2, docstore-templates sprint).
    @GET @Path("/{id}/content-list.pdf")
    @Produces("application/pdf")
    @RolesAllowed("inventory-read")
    fun contentList(
        @PathParam("id") id: Long,
        @QueryParam("store") @DefaultValue("false") store: Boolean,
    ): ByteArray = documentService.contentListPdf(id, tenantContext, store)

    // D11: unit load ZPL barcode label — Code128 of labelId + type/location. @Transactional
    // (mirrors getById above) since labelZpl reads the lazy unitLoadType relation.
    @GET @Path("/{id}/label.zpl")
    @Produces("text/plain")
    @RolesAllowed("inventory-read")
    @Transactional
    fun label(
        @PathParam("id") id: Long,
        @QueryParam("store") @DefaultValue("false") store: Boolean,
    ): String = documentService.labelZpl(id, tenantContext, store)

    /** Pushes the UL's ZPL label to the configured printer (raw TCP :9100, fail-fast).
     *  Body is optional; a `printer` field is accepted and ignored (future printer registry). */
    @POST @Path("/{id}/label/print")
    @RolesAllowed("inventory-write")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    fun printLabel(@PathParam("id") id: Long): Response {
        labelPrintService.print(documentService.labelZpl(id, tenantContext, store = false))
        return Response.noContent().build()
    }

    @POST
    @RolesAllowed("inventory-write")
    @Transactional
    fun create(request: CreateUnitLoadRequest): Response {
        val ul = service.create(request, tenantContext)
        return Response.status(201).entity(ul.toResponse()).build()
    }

    @POST @Path("/{id}/transfer")
    @RolesAllowed("inventory-write")
    @Transactional
    fun transfer(@PathParam("id") id: Long, request: TransferUnitLoadRequest): UnitLoadResponse =
        service.transferToLocation(id, request.destinationLocationId, request.destinationLocationName, tenantContext).toResponse()

    @POST @Path("/{id}/change-client")
    @RolesAllowed("inventory-write")
    @Transactional
    fun changeClient(@PathParam("id") id: Long, request: ChangeClientRequest): UnitLoadResponse =
        service.changeClient(id, request.targetClientId, request.activityCode, tenantContext).toResponse()

    @POST @Path("/{id}/transfer-to-carrier")
    @RolesAllowed("inventory-write")
    @Transactional
    fun transferToCarrier(@PathParam("id") id: Long, request: TransferToCarrierRequest): UnitLoadResponse =
        service.transferToCarrier(id, request.carrierUnitLoadId, tenantContext).toResponse()

    @POST @Path("/{id}/carrier")
    @RolesAllowed("inventory-write")
    @Transactional
    fun setCarrier(@PathParam("id") id: Long, request: SetCarrierRequest): UnitLoadResponse =
        service.setCarrier(id, request.isCarrier, tenantContext).toResponse()

    @POST @Path("/{id}/lock")
    @RolesAllowed("inventory-write")
    @Transactional
    fun lock(@PathParam("id") id: Long, request: LockUnitLoadRequest): UnitLoadResponse =
        service.lock(id, request.lockType, request.note, tenantContext).toResponse()

    @POST @Path("/{id}/unlock")
    @RolesAllowed("inventory-write")
    @Transactional
    fun unlock(@PathParam("id") id: Long): UnitLoadResponse =
        service.unlock(id, tenantContext).toResponse()

    @POST @Path("/{id}/transfer-to-clearing")
    @RolesAllowed("inventory-write")
    @Transactional
    fun transferToClearing(@PathParam("id") id: Long, request: TransferToClearingRequest): UnitLoadResponse =
        service.transferToClearing(id, request.note, tenantContext).toResponse()

    /**
     * Row 16: sets (or, with a null [SetWeightMeasureRequest.weightMeasure], clears) the manual
     * weight override. Reuses [UnitLoadService.findByIdForWrite] rather than adding a new
     * function to [UnitLoadService] -- that class is already at detekt's 25-function ceiling.
     */
    @PUT @Path("/{id}/weight-measure")
    @RolesAllowed("inventory-write")
    @Transactional
    fun setWeightMeasure(@PathParam("id") id: Long, request: SetWeightMeasureRequest): UnitLoadResponse {
        val ul = service.findByIdForWrite(id, tenantContext)
        weightCalculator.applyMeasure(ul, request.weightMeasure)
        return ul.toResponse()
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("inventory-write")
    @Transactional
    fun delete(@PathParam("id") id: Long): Response {
        service.delete(id, tenantContext)
        return Response.noContent().build()
    }

    private fun UnitLoad.toResponse() = UnitLoadResponse(
        id = id!!, labelId = labelId, externalId = externalId,
        unitLoadTypeId = unitLoadType.id!!, unitLoadTypeName = unitLoadType.name,
        storageLocationId = storageLocationId, storageLocationName = storageLocationName,
        state = state, opened = opened, isCarrier = isCarrier, weight = weight,
        weightCalculated = weightCalculated, weightMeasure = weightMeasure,
        lockType = lockType, lockTypeName = LockType.fromCode(lockType).name,
        stockUnits = stockUnits.map {
            StockUnitSummary(id = it.id!!, itemDataNumber = it.itemDataNumber, amount = it.amount, lotNumber = it.lotNumber, state = it.state)
        },
        created = created,
    )
}
