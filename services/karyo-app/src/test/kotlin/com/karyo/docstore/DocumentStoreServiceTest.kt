package com.karyo.docstore

import com.karyo.docstore.dto.StoredDocumentResponse
import com.karyo.docstore.exception.DocumentStoreException
import com.karyo.docstore.repository.StoredDocumentRepository
import com.karyo.docstore.service.DocumentStoreService
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Direct-SPI-call tests for [DocumentStoreService] (the [com.karyo.documents.DocumentStore]
 * implementation). Mirrors ClientServiceTest's idiom: TenantContext is @RequestScoped and
 * populated by TenantFilter over REST, so a direct service call must prime it by hand.
 */
@QuarkusTest
class DocumentStoreServiceTest {

    @Inject
    lateinit var documentStoreService: DocumentStoreService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxEventRepository: OutboxEventRepository

    @Inject
    lateinit var storedDocumentRepository: StoredDocumentRepository

    @BeforeEach
    fun primeOpsPrincipal() {
        tenantContext.clientId = 0
        tenantContext.principalKind = PrincipalKind.OPS
    }

    private fun nextEntityId() = System.nanoTime()

    @Test
    fun `store persists a row attributed to the owner client, not the acting OPS principal`() {
        val entityId = nextEntityId()
        val ownerClientId = 2L

        val id = documentStoreService.store(
            ownerClientId = ownerClientId,
            entityType = "shipment",
            entityId = entityId,
            documentType = "bol",
            fileName = "bol-$entityId.pdf",
            mediaType = "application/pdf",
            content = "pdf-bytes".toByteArray(),
        )

        val stored = documentStoreService.findForRead(id)
        assertThat(stored.clientId)
            .`as`("stored document must be attributed to the owner client ($ownerClientId), not the acting OPS principal (0)")
            .isEqualTo(ownerClientId)
        assertThat(stored.entityType).isEqualTo("shipment")
        assertThat(stored.entityId).isEqualTo(entityId)
        assertThat(stored.documentType).isEqualTo("bol")
        assertThat(stored.sizeBytes).isEqualTo("pdf-bytes".toByteArray().size)
    }

    @Test
    fun `store refuses content larger than the size cap`() {
        val entityId = nextEntityId()
        val oversized = ByteArray(DocumentStoreService.MAX_CONTENT_BYTES + 1)

        assertThatThrownBy {
            documentStoreService.store(
                ownerClientId = 2L,
                entityType = "shipment",
                entityId = entityId,
                documentType = "bol",
                fileName = "big.pdf",
                mediaType = "application/pdf",
                content = oversized,
            )
        }.isInstanceOf(DocumentStoreException.ContentTooLarge::class.java)
    }

    @Test
    fun `store writes an outbox event under the owner client, not the acting OPS principal`() {
        val entityId = nextEntityId()
        val ownerClientId = 2L

        val id = documentStoreService.store(
            ownerClientId = ownerClientId,
            entityType = "shipment",
            entityId = entityId,
            documentType = "bol",
            fileName = "bol-$entityId.pdf",
            mediaType = "application/pdf",
            content = "pdf-bytes".toByteArray(),
        )

        val event = outboxEventRepository.find(
            "eventType = ?1 and aggregateId = ?2",
            "document.stored", id,
        ).firstResult()!!

        assertThat(event.tenantId)
            .`as`("outbox tenantId must be the owner client ($ownerClientId), not the acting OPS principal (0)")
            .isEqualTo(ownerClientId)
    }

    @Test
    fun `an owner principal cannot store a document under a different owner`() {
        tenantContext.clientId = 1
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThatThrownBy {
            documentStoreService.store(
                ownerClientId = 2L,
                entityType = "shipment",
                entityId = nextEntityId(),
                documentType = "bol",
                fileName = "bol.pdf",
                mediaType = "application/pdf",
                content = "pdf-bytes".toByteArray(),
            )
        }.isInstanceOf(DocumentStoreException.Forbidden::class.java)
    }

    @Test
    fun `an owner principal can store a document under its own client`() {
        tenantContext.clientId = 1
        tenantContext.principalKind = PrincipalKind.OWNER

        val id = documentStoreService.store(
            ownerClientId = 1L,
            entityType = "shipment",
            entityId = nextEntityId(),
            documentType = "bol",
            fileName = "bol.pdf",
            mediaType = "application/pdf",
            content = "pdf-bytes".toByteArray(),
        )

        assertThat(id).isNotNull()
    }

    @Test
    fun `deleteById removes the row and writes an outbox event under the owner client`() {
        val entityId = nextEntityId()
        val ownerClientId = 2L
        val id = documentStoreService.store(
            ownerClientId = ownerClientId,
            entityType = "shipment",
            entityId = entityId,
            documentType = "bol",
            fileName = "bol-$entityId.pdf",
            mediaType = "application/pdf",
            content = "pdf-bytes".toByteArray(),
        )

        documentStoreService.deleteById(id)

        assertThatThrownBy { documentStoreService.findForRead(id) }
            .isInstanceOf(DocumentStoreException.NotFound::class.java)

        val event = outboxEventRepository.find(
            "eventType = ?1 and aggregateId = ?2",
            "document.deleted", id,
        ).firstResult()!!
        assertThat(event.tenantId).isEqualTo(ownerClientId)
    }

    // ---- list: page-size clamp + content-free projection (final-review F1) ------------------

    @Test
    fun `list clamps an oversized requested size to the documented cap, never the raw request value`() {
        val entityId = nextEntityId()
        repeat(3) {
            documentStoreService.store(
                ownerClientId = 1L,
                entityType = "clamp-probe",
                entityId = entityId,
                documentType = "bol",
                fileName = "bol.pdf",
                mediaType = "application/pdf",
                content = "pdf-bytes".toByteArray(),
            )
        }
        tenantContext.clientId = 1
        tenantContext.principalKind = PrincipalKind.OWNER

        // GET /api/v1/documents?size=100000 must not ask Postgres for the whole archive: the
        // service clamps size to MAX_PAGE_SIZE regardless of what the caller asked for.
        val result = documentStoreService.list(
            entityType = "clamp-probe",
            entityId = entityId,
            documentType = null,
            page = 0,
            size = 100_000,
        )

        assertThat(result.content).hasSize(3)
        assertThat(result.page.size)
            .`as`("effective page size must be clamped to MAX_PAGE_SIZE, not echo the raw 100000 request")
            .isEqualTo(DocumentStoreService.MAX_PAGE_SIZE)
        assertThat(DocumentStoreService.MAX_PAGE_SIZE).isLessThanOrEqualTo(200)
    }

    @Test
    fun `list's underlying query projects onto StoredDocumentResponse, never selecting content`() {
        val entityId = nextEntityId()
        documentStoreService.store(
            ownerClientId = 1L,
            entityType = "projection-probe",
            entityId = entityId,
            documentType = "bol",
            fileName = "bol.pdf",
            mediaType = "application/pdf",
            content = "pdf-bytes".toByteArray(),
        )
        tenantContext.clientId = 1
        tenantContext.principalKind = PrincipalKind.OWNER

        // searchMetadata() is a Panache .project() query -- if it selected the full StoredDocument
        // entity (content included) this would still compile, but the returned rows would not be
        // StoredDocumentResponse instances. Pinning the runtime type pins the projection shape.
        val rows = storedDocumentRepository.searchMetadata(1L, "projection-probe", entityId, null).list()

        assertThat(rows).hasSize(1)
        assertThat(rows.first()).isInstanceOf(StoredDocumentResponse::class.java)
        assertThat(rows.first().entityType).isEqualTo("projection-probe")
    }
}
