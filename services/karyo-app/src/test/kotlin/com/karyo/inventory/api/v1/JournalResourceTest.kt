package com.karyo.inventory.api.v1

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.service.JournalService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class JournalResourceTest {

    @Inject
    lateinit var repository: InventoryJournalRepository

    @Inject
    lateinit var journalService: JournalService

    private fun seedJournal(product: String, from: String?, to: String?, amount: Double, clientId: Long = 1) {
        QuarkusTransaction.requiringNew().run {
            repository.persist(InventoryJournal().apply {
                this.clientId = clientId
                recordType = JournalRecordType.PICKED.code
                productNumber = product
                this.amount = BigDecimal(amount)
                fromStorageLocation = from
                toStorageLocation = to
            })
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `filters journals by location on either side, composable with productNumber`() {
        seedJournal("JX-1", from = "BIN-A", to = null, amount = 5.0)   // out of BIN-A
        seedJournal("JX-2", from = null, to = "BIN-A", amount = 9.0)   // into BIN-A
        seedJournal("JX-3", from = "BIN-B", to = null, amount = 3.0)   // unrelated

        // location matches either from or to
        given().`when`().get("/api/v1/journals?location=BIN-A")
            .then().statusCode(200)
            .body("productNumber", hasItems("JX-1", "JX-2"))
            .body("productNumber", not(hasItem("JX-3")))

        // location + productNumber narrows to item×location
        given().`when`().get("/api/v1/journals?location=BIN-A&productNumber=JX-2")
            .then().statusCode(200)
            .body("size()", `is`(1))
            .body("[0].productNumber", `is`("JX-2"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "10")])
    fun `regular client only sees its own client_id's journals`() {
        seedJournal("SA-10", from = "BIN-C", to = null, amount = 1.0, clientId = 10)
        seedJournal("SA-11", from = "BIN-C", to = null, amount = 2.0, clientId = 11)

        given().`when`().get("/api/v1/journals?location=BIN-C")
            .then().statusCode(200)
            .body("productNumber", hasItem("SA-10"))
            .body("productNumber", not(hasItem("SA-11")))
    }

    @Test
    @TestSecurity(user = "system-admin", roles = ["inventory-read", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "999"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `ops principal sees journals across every client_id`() {
        seedJournal("SA-20", from = "BIN-D", to = null, amount = 1.0, clientId = 20)
        seedJournal("SA-21", from = "BIN-D", to = null, amount = 2.0, clientId = 21)

        given().`when`().get("/api/v1/journals?location=BIN-D")
            .then().statusCode(200)
            .body("productNumber", hasItems("SA-20", "SA-21"))
    }

    /**
     * The ADMIN realm role on its own no longer widens scope. It did between 2026-07-19 and the
     * realm rollout, via a transitional bridge on this endpoint; that bridge is gone, so an
     * ADMIN-role principal without `principal_kind=ops` is owner-scoped like anyone else.
     * This pins the removal — if the bridge is ever reintroduced, this fails.
     */
    @Test
    @TestSecurity(user = "role-only-admin", roles = ["inventory-read", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "999")])
    fun `ADMIN role without the ops claim is owner-scoped, not unscoped`() {
        seedJournal("SB-20", from = "BIN-E", to = null, amount = 1.0, clientId = 20)
        seedJournal("SB-21", from = "BIN-E", to = null, amount = 2.0, clientId = 21)

        given().`when`().get("/api/v1/journals?location=BIN-E")
            .then().statusCode(200)
            .body("productNumber", not(hasItem("SB-20")))
            .body("productNumber", not(hasItem("SB-21")))
    }

    /**
     * SC19 fromCode landmine guard: `JournalResource.list` calls `JournalRecordType.fromCode`
     * per row and fromCode THROWS on unknown codes — so a stored auth-event row (record types
     * 10-12) must render cleanly through the endpoint, ipAddress included, or the whole
     * listing 500s for everyone.
     */
    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `auth-event rows with the new record types list cleanly, ipAddress included`() {
        journalService.recordAuthEvent(
            clientId = 1,
            username = "kc-audit-user",
            recordType = JournalRecordType.LOGIN,
            activityCode = "LOGIN",
            correlationId = "sess-jrt-fromcode",
            ipAddress = "10.1.2.3",
            occurredAt = java.time.Instant.now().minusSeconds(3600),
        )

        given().`when`().get("/api/v1/journals?recordType=10&correlationId=sess-jrt-fromcode")
            .then().statusCode(200)
            .body("size()", `is`(1))
            .body("[0].recordTypeName", `is`("LOGIN"))
            .body("[0].activityCode", `is`("LOGIN"))
            .body("[0].operatorName", `is`("kc-audit-user"))
            .body("[0].ipAddress", `is`("10.1.2.3"))
    }
}
