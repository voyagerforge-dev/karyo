package com.karyo.inventory

import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.StockService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Pins the audit-attribution rule for [InventoryJournal]: a journal row records
 * **whose goods moved** in `clientId`, and **who moved them** in `operatorName`.
 *
 * These two differ precisely in the case that matters operationally — operating-company
 * staff (an OPS principal, `client_id = 0` / SYS) physically handling a goods owner's
 * stock. Attributing the row to the acting principal files the movement under SYS, and
 * the goods owner's audit log then never shows it at all.
 */
@QuarkusTest
class JournalAttributionTest {

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun createUnitLoad(label: String, owner: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"clientId":$owner,"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$JA_LOCATION_ID,"storageLocationName":"JA-LOC"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, productNumber: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$JA_ITEM_DATA_ID,"itemDataNumber":"$productNumber",""" +
                    """"amount":$amount,"unitLoadId":$unitLoadId,"state":${StockState.ON_STOCK.code}}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Journal rows carry productNumber (= itemDataNumber), not a stock-unit id — query by that. */
    private fun journalRows(productNumber: String, recordType: JournalRecordType): List<InventoryJournal> =
        journalRepository
            .find("productNumber = ?1 and recordType = ?2", productNumber, recordType.code)
            .list()

    /** Persists a client-1-owned unit load + stock unit directly, bypassing the REST write path. */
    @Transactional
    fun seedOwnedStock(label: String, productNumber: String, amount: BigDecimal): Long {
        val ul = UnitLoad().apply {
            this.clientId = GOODS_OWNER
            this.labelId = label
            this.unitLoadType = unitLoadTypeRepository.listAll().first()
            this.storageLocationId = JA_LOCATION_ID
            this.storageLocationName = "JA-LOC"
        }
        unitLoadRepository.persist(ul)

        val su = StockUnit().apply {
            this.clientId = GOODS_OWNER
            this.itemDataId = JA_ITEM_DATA_ID
            this.itemDataNumber = productNumber
            this.amount = amount
            this.unitLoad = ul
            this.state = StockState.ON_STOCK.code
            this.strategyDate = Instant.now()
        }
        stockUnitRepository.persist(su)
        return su.id!!
    }

    // ── tests ───────────────────────────────────────────────────────────────

    /**
     * The core rule: an OPS principal (client 0) adjusting client 1's stock must produce a
     * journal row filed under client 1, while `operatorName` still names the ops user.
     * Asserting both together is what stops a future "simplification" collapsing the two
     * back onto the acting principal.
     */
    @Test
    @TestSecurity(user = "opsuser", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ]
    )
    fun `journal is attributed to the goods owner, not the acting ops principal`() {
        val suffix = System.nanoTime().toString().takeLast(6)
        val productNumber = "JA-$suffix"
        val unitLoadId = createUnitLoad("UL-JA-$suffix", owner = GOODS_OWNER)
        val stockUnitId = createStock(unitLoadId, productNumber, amount = 20.0)

        given().contentType(ContentType.JSON)
            .body("""{"newAmount":12,"activityCode":"JA-ADJUST"}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/adjust")
            .then().statusCode(200)

        val rows = journalRows(productNumber, JournalRecordType.CHANGED)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().clientId)
            .`as`("journal clientId must record WHOSE goods moved (owner $GOODS_OWNER), not who moved them (ops, client 0)")
            .isEqualTo(GOODS_OWNER)
        assertThat(rows.single().operatorName)
            .`as`("operatorName must still record the acting principal")
            .isEqualTo("opsuser")
    }

    /**
     * The user-visible consequence: because the row is filed under the goods owner, that
     * owner's audit log (`GET /api/v1/journals`, scoped to `clientId` for an OWNER
     * principal) actually shows the movement its staff never performed.
     */
    @Test
    @TestSecurity(user = "acme", roles = ["inventory-read"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
            Claim(key = "principal_kind", value = "owner"),
        ]
    )
    fun `goods owner sees the audit row for a movement performed by ops staff`() {
        val suffix = System.nanoTime().toString().takeLast(6)
        val productNumber = "JAV-$suffix"
        val stockUnitId = seedOwnedStock("UL-JAV-$suffix", productNumber, BigDecimal("20"))

        // Act as ops staff (SYS tenant) mutating owner 1's stock — the real-world case.
        tenantContext.clientId = 0L
        tenantContext.principalKind = PrincipalKind.OPS
        tenantContext.username = "opsuser"
        stockService.adjustAmount(stockUnitId, BigDecimal("7"), "JAV-ADJUST", tenantContext)

        // Read back over HTTP as the goods owner; TenantFilter re-primes scope from the claims.
        val entries = given()
            .`when`().get("/api/v1/journals?productNumber=$productNumber")
            .then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("$")

        assertThat(entries)
            .`as`("owner $GOODS_OWNER must see the movement ops staff performed on its goods")
            .hasSize(1)
    }

    /**
     * `product_number` is only `UNIQUE(client_id, number)` — pre-existing journal rows can
     * never be traced back to a specific stock unit. New rows self-identify via
     * `stock_unit_id`/`item_data_id` so this class of forensics never recurs (V108).
     */
    @Test
    @TestSecurity(user = "opsuser", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ]
    )
    fun `journal rows carry stock_unit_id and item_data_id`() {
        val suffix = System.nanoTime().toString().takeLast(6)
        val productNumber = "JAID-$suffix"
        val unitLoadId = createUnitLoad("UL-JAID-$suffix", owner = GOODS_OWNER)
        val stockUnitId = createStock(unitLoadId, productNumber, amount = 20.0)

        given().contentType(ContentType.JSON)
            .body("""{"newAmount":12,"activityCode":"JAID-ADJUST"}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/adjust")
            .then().statusCode(200)

        val rows = journalRows(productNumber, JournalRecordType.CHANGED)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().stockUnitId).isEqualTo(stockUnitId)
        assertThat(rows.single().itemDataId).isEqualTo(JA_ITEM_DATA_ID)
    }

    companion object {
        /** The goods owner (3PL customer) whose stock the ops principal handles. */
        private const val GOODS_OWNER = 1L

        /** Any existing location/item id — these tests assert attribution, not placement. */
        private const val JA_LOCATION_ID = 1L
        private const val JA_ITEM_DATA_ID = 1L
    }
}
