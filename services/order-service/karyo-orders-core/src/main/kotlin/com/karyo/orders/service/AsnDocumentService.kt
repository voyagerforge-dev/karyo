package com.karyo.orders.service

import com.karyo.documents.DocumentRenderer
import com.karyo.documents.DocumentStore
import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnUlAdvice
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.AsnRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Renders the UL pre-advice ZPL label sheet (Karyo-native, see
 * [com.karyo.orders.domain.model.AsnUlAdvice]) — one `^XA...^XZ` block per advice
 * on the ASN, concatenated. Each block is rendered SEPARATELY with a flat
 * (non-nested, non-List) data map: [DocumentRenderer]'s ZPL sanitizer only strips
 * `^`/`~` from flat string values (and one level of nested Maps), not from values
 * inside a `List` — rendering once per advice, rather than handing the whole
 * advice list to a single template render, is how this label sheet stays inside
 * that sanitizer's actual coverage.
 *
 * Read-only: doesn't mutate, so not `@Transactional` (mirrors
 * [UnitLoadDocumentService][com.karyo.inventory.service.UnitLoadDocumentService] /
 * [OrderDocumentService]) — the calling REST route is `@Transactional` instead,
 * matching the `ul-label.zpl` precedent.
 */
@ApplicationScoped
class AsnDocumentService(
    private val asnRepository: AsnRepository,
    private val ulAdviceService: AsnUlAdviceService,
    private val renderer: DocumentRenderer,
    private val documentStore: Instance<DocumentStore>,
) {

    fun ulLabelsZpl(asnId: Long, clientId: Long, store: Boolean = false): String {
        val asn = asnRepository.findByIdAndClient(asnId, clientId)
            ?: throw OrderException.NotFound("Asn", "id=$asnId")
        val advices = ulAdviceService.listForAsn(asnId)
        val zpl = advices.joinToString(separator = "") { advice ->
            renderer.render("/templates/ul-advice-label.zpl", labelData(asn, advice), asn.clientId)
        }
        archive(store, asn, zpl)
        return zpl
    }

    /** Opt-in archive (`?store=true`), a no-op unless a [DocumentStore] bean is wired (karyo-docstore). */
    private fun archive(store: Boolean, asn: Asn, zpl: String) {
        if (store && documentStore.isResolvable) {
            documentStore.get().store(
                ownerClientId = asn.clientId,
                entityType = "asn",
                entityId = asn.id!!,
                documentType = "ul-advice-labels",
                fileName = "ul-advice-labels-${asn.id}.zpl",
                mediaType = "text/plain; charset=utf-8",
                content = zpl.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun labelData(asn: Asn, advice: AsnUlAdvice): Map<String, Any?> = mapOf(
        "labelId" to advice.labelId,
        "asnNumber" to asn.asnNumber,
        "itemDataNumber" to (advice.itemDataNumber ?: ""),
    )
}
