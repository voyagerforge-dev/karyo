package com.karyo.inventory.api.v1

import com.karyo.inventory.api.dto.JournalEntryResponse
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.security.TenantContext
import com.karyo.security.TenantScope
import com.karyo.security.readScope
import com.karyo.inventory.repository.InventoryJournalRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
class JournalResource(
    private val repository: InventoryJournalRepository,
) {

    @Inject
    lateinit var tenantContext: TenantContext

    @GET
    @RolesAllowed("inventory-read")
    fun list(
        @QueryParam("productNumber") productNumber: String?,
        @QueryParam("correlationId") correlationId: String?,
        @QueryParam("recordType") recordType: Int?,
        @QueryParam("location") location: String?,
    ): List<JournalEntryResponse> {
        // Ops staff see every journal event across the instance (this powers the
        // Admin -> Audit log page); a goods-owner principal sees only its own rows.
        //
        // This endpoint carried a transitional ADMIN-role bridge from 2026-07-19 until the
        // `principal_kind` claim went live on real tokens. Unlike the other converted sites --
        // whose bypass tested a lowercase "admin" role and so never matched a real token --
        // this one's bypass was alive, so it could not simply be dropped when the model
        // changed. The claim is now issued by the realm, so scoping is uniform again: this
        // reads exactly like every other converted site, with no role special-case.
        val scope = tenantContext.readScope()
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        var idx = 1

        if (scope is TenantScope.Owner) {
            conditions.add("clientId = ?${idx++}")
            params.add(scope.clientId)
        }
        productNumber?.let {
            conditions.add("productNumber = ?${idx++}"); params.add(it)
        }
        correlationId?.let {
            conditions.add("correlationId = ?${idx++}"); params.add(it)
        }
        recordType?.let {
            conditions.add("recordType = ?${idx++}"); params.add(it)
        }
        location?.let {
            conditions.add("(fromStorageLocation = ?$idx or toStorageLocation = ?$idx)")
            params.add(it); idx++
        }

        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" and ") + " "
        return repository.list("${where}order by created desc", *params.toTypedArray())
            .map { j ->
                JournalEntryResponse(
                    id = j.id!!,
                    recordType = j.recordType,
                    recordTypeName = JournalRecordType.fromCode(j.recordType).name,
                    productNumber = j.productNumber,
                    productName = j.productName,
                    amount = j.amount,
                    stockUnitAmount = j.stockUnitAmount,
                    fromUnitLoad = j.fromUnitLoad,
                    toUnitLoad = j.toUnitLoad,
                    fromStorageLocation = j.fromStorageLocation,
                    toStorageLocation = j.toStorageLocation,
                    lotNumber = j.lotNumber,
                    activityCode = j.activityCode,
                    operatorName = j.operatorName,
                    correlationId = j.correlationId,
                    created = j.created,
                    ipAddress = j.ipAddress,
                )
            }
    }
}
