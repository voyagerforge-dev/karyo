package com.karyo.security

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.repository.InventoryJournalRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The Admin -> Audit log page relies on an ops principal seeing every journal row.
 * A goods-owner principal must see only its own.
 *
 * These are characterization tests for [com.karyo.security.readScope] as consumed by
 * JournalResource's ADMIN transitional bridge (see JournalResource.list) -- they seed rows
 * under two distinct clientIds and assert on response body content, not just HTTP status, so a
 * regression that silently narrows or widens the visible set is actually caught.
 */
@QuarkusTest
class JournalScopeTest {

    @Inject
    lateinit var repository: InventoryJournalRepository

    private fun seedJournal(product: String, clientId: Long) {
        QuarkusTransaction.requiringNew().run {
            repository.persist(InventoryJournal().apply {
                this.clientId = clientId
                recordType = JournalRecordType.PICKED.code
                productNumber = product
                amount = BigDecimal.ONE
            })
        }
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `ops principal sees journals across every client_id`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val productA = "SCOPE-A-$suffix"
        val productB = "SCOPE-B-$suffix"
        seedJournal(productA, clientId = 30)
        seedJournal(productB, clientId = 31)

        given().`when`().get("/api/v1/journals")
            .then().statusCode(200)
            .body("productNumber", hasItems(productA, productB))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-read", "MANAGER"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "32"),
            Claim(key = "tenant_code", value = "ACME"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `owner principal only sees its own client_id's journals`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val ownProduct = "SCOPE-C-$suffix"
        val otherProduct = "SCOPE-D-$suffix"
        seedJournal(ownProduct, clientId = 32)
        seedJournal(otherProduct, clientId = 33)

        given().`when`().get("/api/v1/journals")
            .then().statusCode(200)
            .body("productNumber", hasItem(ownProduct))
            .body("productNumber", not(hasItem(otherProduct)))
    }

    @Test
    @TestSecurity(user = "legacy", roles = ["inventory-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "34"), Claim(key = "tenant_code", value = "ACME")])
    fun `principal with no principal_kind claim and no ADMIN role is owner-scoped`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        val ownProduct = "SCOPE-E-$suffix"
        val otherProduct = "SCOPE-F-$suffix"
        seedJournal(ownProduct, clientId = 34)
        seedJournal(otherProduct, clientId = 35)

        given().`when`().get("/api/v1/journals")
            .then().statusCode(200)
            .body("productNumber", hasItem(ownProduct))
            .body("productNumber", not(hasItem(otherProduct)))
    }
}
