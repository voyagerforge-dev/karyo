package com.karyo.inventory.service

import com.karyo.documents.DocumentRenderer
import com.karyo.documents.DocumentStore
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.time.Instant

/**
 * Renders the unit load content list PDF (D8) — a per-pallet manifest of everything riding a
 * unit load: SKU, qty, lot, best-before, serial per stock unit, plus the lock name whenever a
 * stock unit is individually locked (a quarantine list is exactly when this document earns
 * its keep). [StockState.DELETABLE] stock is excluded — a soft-deleted stock unit is no
 * longer "content" riding the load.
 *
 * Tenant scoping mirrors [com.karyo.inventory.api.v1.UnitLoadResource.getById]:
 * [UnitLoadService.findById] does the readScope check (404, never a foreign-tenant leak),
 * so the unit load lookup itself needs no extra scoping. The stock unit ROWS riding the load
 * are a separate scoping concern -- a unit load's stock units are not guaranteed to share the
 * unit load's owner (pre-D1 data, or an OPS-driven mixed-owner load) -- so this delegates to
 * [StockService.findByUnitLoad], the same per-row `readScope().permits(...)` filter the JSON
 * `GET /api/v1/stock-units?unitLoadId=` sibling uses, rather than reading the repository
 * directly.
 *
 * Read-only: doesn't mutate, so not `@Transactional` (mirrors
 * [com.karyo.fulfillment.service.PickDocumentService]).
 */
@ApplicationScoped
class UnitLoadDocumentService(
    private val unitLoadService: UnitLoadService,
    private val stockService: StockService,
    private val renderer: DocumentRenderer,
    private val documentStore: Instance<DocumentStore>,
) {
    fun contentListPdf(unitLoadId: Long, tenant: TenantContext, store: Boolean = false): ByteArray {
        val ul = unitLoadService.findById(unitLoadId, tenant)
        val rows = stockService.findByUnitLoad(ul.id!!, tenant)
            .filter { it.state != StockState.DELETABLE.code }
        val bytes = renderer.htmlToPdf(renderer.render("/templates/ul-content-list.html", contentData(ul, rows), ul.clientId))
        archive(store, ul, "content-list", "application/pdf", bytes)
        return bytes
    }

    /**
     * D11: Code128 barcode of [UnitLoad.labelId] plus human-readable labelId, unit-load-type
     * name and current location name. `unitLoadType` is a lazy `@ManyToOne` -- the resource
     * route calling this is `@Transactional` (mirrors
     * [com.karyo.inventory.api.v1.UnitLoadResource.getById]) so the proxy resolves.
     */
    fun labelZpl(unitLoadId: Long, tenant: TenantContext, store: Boolean = false): String {
        val ul = unitLoadService.findById(unitLoadId, tenant)
        val zpl = renderer.render("/templates/ul-label.zpl", labelData(ul), ul.clientId)
        archive(store, ul, "label", "text/plain; charset=utf-8", zpl.toByteArray(Charsets.UTF_8))
        return zpl
    }

    /**
     * Opt-in archive of a just-rendered unit-load document (`?store=true`, Task 2 of the
     * docstore-templates sprint) — a no-op unless [store] is set AND a [DocumentStore] bean is
     * actually wired (karyo-docstore). Owner is always [ul]'s own `clientId`, never the acting
     * principal's.
     */
    private fun archive(store: Boolean, ul: UnitLoad, documentType: String, mediaType: String, content: ByteArray) {
        if (store && documentStore.isResolvable) {
            documentStore.get().store(
                ownerClientId = ul.clientId,
                entityType = "unit-load",
                entityId = ul.id!!,
                documentType = documentType,
                fileName = "$documentType-${ul.id}.${if (mediaType == "application/pdf") "pdf" else "zpl"}",
                mediaType = mediaType,
                content = content,
            )
        }
    }

    private fun labelData(ul: UnitLoad): Map<String, Any?> = mapOf(
        "labelId" to ul.labelId,
        "unitLoadTypeName" to ul.unitLoadType.name,
        "storageLocationName" to ul.storageLocationName,
    )

    private fun contentData(ul: UnitLoad, rows: List<StockUnit>): Map<String, Any?> = mapOf(
        "labelId" to ul.labelId,
        "storageLocationName" to ul.storageLocationName,
        "generatedAt" to Instant.now().toString(),
        "stockUnits" to rows.map { su ->
            mapOf(
                "itemDataNumber" to su.itemDataNumber,
                "amount" to su.amount.toPlainString(),
                "lotNumber" to (su.lotNumber ?: "—"),
                "bestBefore" to (su.bestBefore?.toString() ?: "—"),
                "serialNumber" to (su.serialNumber ?: "—"),
                "lock" to if (su.lockType != LockType.UNLOCKED.code) LockType.fromCode(su.lockType).name else "—",
            )
        },
    )
}
