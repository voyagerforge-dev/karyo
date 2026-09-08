package com.karyo.inventory

import com.karyo.inventory.api.spi.StockCountingPort
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration tests for [StockCountingPort] reconciliation writes:
 * [StockCountingPort.applyCount] and [StockCountingPort.recordMatchCounted].
 *
 * Seeding pattern mirrors [StockCountingPortEnumLockTest]: REST helpers (authed via
 * @TestSecurity + @OidcSecurity) create a unit load then an ON_STOCK stock unit.
 * TenantContext.clientId is primed directly so port tenant-filter matches.
 *
 * clientId 4402 / 4403 are reserved for this suite; no other suite uses these ranges.
 *
 * Journal rows denormalise productNumber (= itemDataNumber) rather than stockUnitId,
 * so [journalCount] queries by productNumber + recordType + clientId. Each seed call
 * generates a unique itemDataNumber suffix so counts are per-stock-unit, not per-item.
 */
@QuarkusTest
class StockCountingPortReconcileTest {

    @Inject
    lateinit var port: StockCountingPort

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    // ── seed helpers ────────────────────────────────────────────────────────

    /** Extended seed context that also carries the unique itemDataNumber for journal queries. */
    data class SeedCtx(val locationId: Long, val unitLoadId: Long, val stockUnitId: Long, val itemDataNumber: String)

    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, itemDataNumber: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemDataNumber",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /**
     * Seeds one location → one unit load → one stock unit at the given amount.
     * The itemDataNumber is unique per call (8-char nanoTime suffix, ≤20 chars total).
     */
    private fun seedStockAtLocation(clientId: Long, locationId: Long, amount: Double): SeedCtx {
        val suffix = System.nanoTime().toString().takeLast(8)
        val itemDataNumber = "REC-$suffix"          // 12 chars max — well under @Size(max=20)
        val ulId = createUnitLoad("UL-REC-$suffix", locationId)
        val suId = createStock(ulId, clientId * 1000L, itemDataNumber, amount)
        tenantContext.clientId = clientId
        return SeedCtx(locationId, ulId, suId, itemDataNumber)
    }

    /** Reads the current amount of a stock unit via REST. */
    private fun amountOf(stockUnitId: Long): BigDecimal =
        BigDecimal(
            given().`when`().get("/api/v1/stock-units/$stockUnitId")
                .then().statusCode(200).extract().jsonPath().getString("amount")
        )

    /**
     * Counts InventoryJournal rows matching [itemDataNumber] + [recordType] + [clientId].
     * Journal rows use productNumber (= itemDataNumber) as the per-stock-unit key.
     */
    private fun journalCount(itemDataNumber: String, clientId: Long, recordType: JournalRecordType): Long =
        journalRepository.count(
            "productNumber = ?1 and clientId = ?2 and recordType = ?3",
            itemDataNumber, clientId, recordType.code
        )

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4402"), Claim(key = "tenant_code", value = "ACME")])
    fun `applyCount adjusts amount and writes COUNTED and CHANGED journal entries`() {
        val ctx = seedStockAtLocation(clientId = 4402L, locationId = 44020L, amount = 25.0)

        port.applyCount(ctx.stockUnitId, BigDecimal("18"), "ST 999")

        assertThat(amountOf(ctx.stockUnitId))
            .`as`("amount should be adjusted to counted value")
            .isEqualByComparingTo("18")
        assertThat(journalCount(ctx.itemDataNumber, 4402L, JournalRecordType.COUNTED))
            .`as`("applyCount must write a COUNTED journal entry")
            .isGreaterThanOrEqualTo(1)
        assertThat(journalCount(ctx.itemDataNumber, 4402L, JournalRecordType.CHANGED))
            .`as`("adjustAmount (called by applyCount) must write a CHANGED journal entry")
            .isGreaterThanOrEqualTo(1)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4403"), Claim(key = "tenant_code", value = "ACME")])
    fun `recordMatchCounted writes COUNTED entry without changing the amount`() {
        val ctx = seedStockAtLocation(clientId = 4403L, locationId = 44030L, amount = 25.0)

        port.recordMatchCounted(ctx.stockUnitId, "ST 1000")

        assertThat(amountOf(ctx.stockUnitId))
            .`as`("amount must remain unchanged after recordMatchCounted")
            .isEqualByComparingTo("25")
        assertThat(journalCount(ctx.itemDataNumber, 4403L, JournalRecordType.COUNTED))
            .`as`("recordMatchCounted must write a COUNTED journal entry")
            .isGreaterThanOrEqualTo(1)
    }
}
