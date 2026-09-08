package com.karyo.inventory.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.paginatedResponse
import com.karyo.documents.CsvWriter
import com.karyo.inventory.api.dto.*
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.security.TenantContext
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.service.ReservationTransferService
import com.karyo.inventory.service.StockService
import com.karyo.orders.spi.GoodsReceiptLookup
import com.karyo.orders.vo.ReceiptSummary
import com.karyo.product.spi.ProductLookup
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/stock-units")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StockUnitResource(
    private val stockService: StockService,
    private val reservationTransferService: ReservationTransferService,
    private val productLookup: ProductLookup,
    private val goodsReceiptLookup: GoodsReceiptLookup,
) {

    @Inject
    lateinit var tenantContext: TenantContext

    @GET
    @RolesAllowed("inventory-read")
    @Transactional
    fun list(
        @QueryParam("itemDataId") itemDataId: Long?,
        @QueryParam("unitLoadId") unitLoadId: Long?,
        @BeanParam pagination: PaginationParams,
    ): PaginatedResponse<StockUnitResponse> {
        val result = when {
            itemDataId != null -> stockService.findByItemDataPaginated(itemDataId, tenantContext, pagination)
            unitLoadId != null -> stockService.findByUnitLoadPaginated(unitLoadId, tenantContext, pagination)
            else -> stockService.findAllPaginated(tenantContext, pagination)
        }
        val names = productLookup.findNamesByIds(result.content.map { it.itemDataId }.toSet())
        val receipts = goodsReceiptLookup.findByStockUnitIds(result.content.mapNotNull { it.id }.toSet())
        return paginatedResponse(
            result.content.map { it.toResponse(names[it.itemDataId], receipts[it.id]) },
            pagination.page,
            pagination.size,
            result.totalElements,
        )
    }

    /**
     * Σ on-hand amount of one item at one location.
     *
     * Declared before `/{id}`: a literal segment must be matched ahead of the template, or
     * "amount" is captured as an id and fails Long conversion.
     */
    @GET
    @Path("/amount")
    @RolesAllowed("inventory-read")
    @Transactional
    fun readAmount(
        @QueryParam("itemDataId") itemDataId: Long,
        @QueryParam("locationId") locationId: Long,
    ): StockAmountResponse = StockAmountResponse(
        itemDataId = itemDataId,
        locationId = locationId,
        amount = stockService.readAmount(itemDataId, locationId, tenantContext),
    )

    // D12: CSV export -- SAME role + SAME itemDataId/unitLoadId filters as list, delegating to
    // the identical scoped StockService.find*Paginated calls (never a hand-rolled query) so
    // tenant scoping is inherited rather than re-implemented. Declared before `/{id}` (like
    // `/amount` above): a literal segment must be matched ahead of the `{id}` template, or
    // "export.csv" gets captured as an id and fails Long conversion.
    @GET
    @Path("/export.csv")
    @Produces("text/csv")
    @RolesAllowed("inventory-read")
    @Transactional
    fun exportCsv(
        @QueryParam("itemDataId") itemDataId: Long?,
        @QueryParam("unitLoadId") unitLoadId: Long?,
    ): ByteArray {
        val pagination = PaginationParams().apply { size = CsvWriter.EXPORT_MAX_ROWS }
        val result = when {
            itemDataId != null -> stockService.findByItemDataPaginated(itemDataId, tenantContext, pagination)
            unitLoadId != null -> stockService.findByUnitLoadPaginated(unitLoadId, tenantContext, pagination)
            else -> stockService.findAllPaginated(tenantContext, pagination)
        }
        val names = productLookup.findNamesByIds(result.content.map { it.itemDataId }.toSet())
        val receipts = goodsReceiptLookup.findByStockUnitIds(result.content.mapNotNull { it.id }.toSet())
        val responses = result.content.map { it.toResponse(names[it.itemDataId], receipts[it.id]) }
        val rows = responses.map { su ->
            listOf(
                su.id.toString(), su.itemDataNumber, su.amount.toPlainString(), su.reservedAmount.toPlainString(),
                su.lotNumber, su.bestBefore?.toString(), su.serialNumber, su.stateName, su.lockTypeName,
                su.unitLoadLabel, su.locationName,
            )
        }
        return CsvWriter.write(headers = EXPORT_HEADERS, rows = rows, totalElements = result.totalElements)
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("inventory-read")
    @Transactional
    fun getById(@PathParam("id") id: Long): StockUnitResponse {
        val su = stockService.findById(id, tenantContext)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        val receipt = goodsReceiptLookup.findByStockUnitIds(setOf(id))[id]
        return su.toResponse(name, receipt)
    }

    @POST
    @RolesAllowed("inventory-write")
    @Transactional
    fun create(request: CreateStockUnitRequest): Response {
        val su = stockService.createStock(request, tenantContext)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        return Response.status(201).entity(su.toResponse(name, receipt = null)).build()
    }

    @POST
    @Path("/{id}/adjust")
    @RolesAllowed("inventory-write")
    @Transactional
    fun adjust(@PathParam("id") id: Long, request: AdjustStockRequest): StockUnitResponse {
        val su = stockService.adjustAmount(id, request.newAmount, request.activityCode, tenantContext)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        val receipt = goodsReceiptLookup.findByStockUnitIds(setOf(id))[id]
        return su.toResponse(name, receipt)
    }

    @POST
    @Path("/{id}/lock")
    @RolesAllowed("inventory-write")
    @Transactional
    fun setLock(@PathParam("id") id: Long, request: SetLockRequest): StockUnitResponse {
        val su = stockService.setLock(id, request.lockType, tenantContext, request.reason)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        val receipt = goodsReceiptLookup.findByStockUnitIds(setOf(id))[id]
        return su.toResponse(name, receipt)
    }

    @POST
    @Path("/{id}/change-packaging-unit")
    @RolesAllowed("inventory-write")
    @Transactional
    fun changePackagingUnit(@PathParam("id") id: Long, request: ChangePackagingUnitRequest): StockUnitResponse {
        val su = stockService.changePackagingUnit(id, request.packagingUnitId, tenantContext)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        val receipt = goodsReceiptLookup.findByStockUnitIds(setOf(id))[id]
        return su.toResponse(name, receipt)
    }

    /** Row 13: [ReservationTransferService.transferReservation], the atomic reservation move. */
    @POST
    @Path("/{id}/transfer-reservation")
    @RolesAllowed("inventory-write")
    @Transactional
    fun transferReservation(@PathParam("id") id: Long, request: TransferReservationRequest): StockUnitResponse {
        val su = reservationTransferService.transferReservation(id, request.targetStockUnitId, request.amount, tenantContext)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        val receipt = goodsReceiptLookup.findByStockUnitIds(setOf(su.id!!))[su.id]
        return su.toResponse(name, receipt)
    }

    @POST
    @Path("/{id}/change-state")
    @RolesAllowed("inventory-write")
    @Transactional
    fun changeState(@PathParam("id") id: Long, body: Map<String, Int>): StockUnitResponse {
        val newState = body["state"]
            ?: throw InventoryException.ValidationFailed("state required")
        val su = stockService.changeState(id, newState, tenantContext)
        val name = productLookup.findNamesByIds(setOf(su.itemDataId))[su.itemDataId]
        val receipt = goodsReceiptLookup.findByStockUnitIds(setOf(id))[id]
        return su.toResponse(name, receipt)
    }

    /**
     * A deliberate stock deletion is the "this pallet is retired" signal
     * [StockService.deleteStock] itself deliberately does NOT assume (see its KDoc) -- so this
     * REST caller is one of the two that explicitly checks whether the parent unit load just
     * went empty too (task-10, defect-burndown-4, row 14).
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed("inventory-write")
    @Transactional
    fun delete(@PathParam("id") id: Long): Response {
        val su = stockService.deleteStock(id, tenantContext)
        stockService.trashUnitLoadIfEmpty(su, tenantContext, activityCode = null)
        return Response.noContent().build()
    }

    private fun StockUnit.toResponse(itemDataName: String? = null, receipt: ReceiptSummary? = null) = StockUnitResponse(
        id = id!!,
        itemDataId = itemDataId,
        itemDataNumber = itemDataNumber,
        itemDataName = itemDataName,
        amount = amount,
        reservedAmount = reservedAmount,
        availableAmount = availableAmount,
        serialNumber = serialNumber,
        lotNumber = lotNumber,
        packagingUnitId = packagingUnitId,
        bestBefore = bestBefore,
        state = state,
        stateName = StockState.fromCode(state).name,
        lockType = lockType,
        lockTypeName = LockType.fromCode(lockType).name,
        strategyDate = strategyDate,
        unitLoadId = unitLoad.id!!,
        unitLoadLabel = unitLoad.labelId,
        locationId = unitLoad.storageLocationId,
        locationName = unitLoad.storageLocationName,
        created = created,
        modified = modified,
        supplierName = receipt?.supplierName,
        sourceAsn = receipt?.asnNumber,
        receivedAt = receipt?.receivedAt?.toString(),
        aggregateStocks = unitLoad.unitLoadType.aggregateStocks,
    )

    private companion object {
        val EXPORT_HEADERS = listOf(
            "id", "itemDataNumber", "amount", "reservedAmount", "lotNumber", "bestBefore",
            "serialNumber", "stateName", "lockTypeName", "unitLoadLabel", "locationName",
        )
    }
}
