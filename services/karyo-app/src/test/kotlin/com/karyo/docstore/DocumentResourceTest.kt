package com.karyo.docstore

import com.karyo.docstore.service.DocumentStoreService
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * REST surface for the documents archive. Mirrors ClientResourceTest's idiom: both `client_id`
 * and `principal_kind` are supplied on every request, and out-of-scope reads/writes answer 404
 * (never 403) so existence never leaks to a principal not entitled to know it.
 *
 * Seeding uses the injected [DocumentStoreService] directly (as an OPS principal, primed by
 * hand — mirrors [DocumentStoreServiceTest]) rather than a REST call, since there is no public
 * "create" endpoint: documents are only ever produced by `?store=true` on the 10 live document
 * endpoints (Task 2).
 */
@QuarkusTest
class DocumentResourceTest {

    @Inject
    lateinit var documentStoreService: DocumentStoreService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxEventRepository: OutboxEventRepository

    private fun nextEntityId() = System.nanoTime()

    /** Seeds one document under [ownerClientId], as an OPS principal (primed by hand). */
    private fun seed(
        ownerClientId: Long,
        entityType: String = "shipment",
        entityId: Long = nextEntityId(),
        documentType: String = "bol",
        fileName: String = "bol.pdf",
        mediaType: String = "application/pdf",
        content: ByteArray = "pdf-bytes".toByteArray(),
    ): Long {
        tenantContext.clientId = 0
        tenantContext.principalKind = PrincipalKind.OPS
        return documentStoreService.store(
            ownerClientId = ownerClientId,
            entityType = entityType,
            entityId = entityId,
            documentType = documentType,
            fileName = fileName,
            mediaType = mediaType,
            content = content,
        )
    }

    // ---- list: filters + paging -----------------------------------------------------------

    @Test
    @TestSecurity(user = "ops", roles = ["ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `list is filtered by entityType, entityId and documentType`() {
        val entityId = nextEntityId()
        seed(ownerClientId = 1, entityType = "shipment", entityId = entityId, documentType = "bol")
        seed(ownerClientId = 1, entityType = "shipment", entityId = entityId, documentType = "packing-slip")
        seed(ownerClientId = 1, entityType = "shipment", entityId = nextEntityId(), documentType = "bol")
        seed(ownerClientId = 1, entityType = "pick-order", entityId = entityId, documentType = "pick-ticket")

        given().`when`()
            .get("/api/v1/documents?entityType=shipment&entityId=$entityId&documentType=bol")
            .then().statusCode(200)
            .body("content.size()", org.hamcrest.Matchers.equalTo(1))
            .body("content[0].entityId", org.hamcrest.Matchers.equalTo(entityId))
            .body("content[0].documentType", org.hamcrest.Matchers.equalTo("bol"))
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `list supports paging`() {
        val entityId = nextEntityId()
        repeat(3) { seed(ownerClientId = 1, entityType = "paging-probe", entityId = entityId, documentType = "bol") }

        given().`when`()
            .get("/api/v1/documents?entityType=paging-probe&entityId=$entityId&size=2&page=0")
            .then().statusCode(200)
            .body("content.size()", org.hamcrest.Matchers.equalTo(2))
            .body("page.totalElements", org.hamcrest.Matchers.equalTo(3))
            .body("page.totalPages", org.hamcrest.Matchers.equalTo(2))
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `an oversized size param succeeds and is clamped, not an OOM-inviting echo`() {
        val entityId = nextEntityId()
        repeat(3) { seed(ownerClientId = 1, entityType = "size-clamp-probe", entityId = entityId, documentType = "bol") }

        given().`when`()
            .get("/api/v1/documents?entityType=size-clamp-probe&entityId=$entityId&size=100000")
            .then().statusCode(200)
            .body("content.size()", org.hamcrest.Matchers.lessThanOrEqualTo(200))
            .body("content.size()", org.hamcrest.Matchers.equalTo(3))
            .body("page.size", org.hamcrest.Matchers.lessThanOrEqualTo(200))
    }

    // ---- two-tenant isolation ---------------------------------------------------------------

    @Test
    @TestSecurity(user = "owner2", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal does not see another owner's documents in list`() {
        val entityId = nextEntityId()
        seed(ownerClientId = 1, entityType = "isolation-probe", entityId = entityId, documentType = "bol")

        given().`when`()
            .get("/api/v1/documents?entityType=isolation-probe&entityId=$entityId")
            .then().statusCode(200)
            .body("content.size()", org.hamcrest.Matchers.equalTo(0))
    }

    @Test
    @TestSecurity(user = "owner2", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal gets 404 downloading another owner's document`() {
        val id = seed(ownerClientId = 1)

        given().`when`().get("/api/v1/documents/$id/content").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "owner1", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal downloads its own document`() {
        val id = seed(ownerClientId = 1)

        given().`when`().get("/api/v1/documents/$id/content").then().statusCode(200)
    }

    // ---- content download: bytes + headers ---------------------------------------------------

    @Test
    @TestSecurity(user = "ops", roles = ["ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `content download returns the stored bytes, media type and a filename disposition`() {
        val id = seed(
            ownerClientId = 1,
            fileName = "bol-content-test.pdf",
            mediaType = "application/pdf",
            content = "%PDF-fake-content".toByteArray(),
        )

        given().`when`().get("/api/v1/documents/$id/content")
            .then().statusCode(200)
            .contentType("application/pdf")
            .header("Content-Disposition", org.hamcrest.Matchers.equalTo("attachment; filename=\"bol-content-test.pdf\""))
            .extract().asByteArray().let {
                assertThat(it).isEqualTo("%PDF-fake-content".toByteArray())
            }
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `content download for an unknown id returns 404`() {
        given().`when`().get("/api/v1/documents/99999999/content").then().statusCode(404)
    }

    // ---- delete: roles + outbox + out-of-scope ------------------------------------------------

    @Test
    @TestSecurity(user = "mgr", roles = ["MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `MANAGER deletes a document and it writes an outbox event`() {
        val id = seed(ownerClientId = 1)

        given().`when`().delete("/api/v1/documents/$id").then().statusCode(204)

        assertThat(
            outboxEventRepository.count("eventType = ?1 and aggregateId = ?2", "document.deleted", id),
        ).isEqualTo(1L)

        given().`when`().get("/api/v1/documents/$id/content").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `VIEWER cannot delete a document`() {
        val id = seed(ownerClientId = 1)

        given().`when`().delete("/api/v1/documents/$id").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "owner2-mgr", roles = ["MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2"), Claim(key = "principal_kind", value = "owner")])
    fun `deleting an out-of-scope document returns 404, not 403`() {
        val id = seed(ownerClientId = 1)

        given().`when`().delete("/api/v1/documents/$id").then().statusCode(404)
    }
}
