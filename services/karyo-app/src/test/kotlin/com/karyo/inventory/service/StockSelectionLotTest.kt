package com.karyo.inventory.service

import com.karyo.inventory.api.dto.StockSelectionResponse
import com.karyo.inventory.api.vo.StockSelectionRequest
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
 * The lot-targeting contract of [StockSelectionService]. Adding the lot tests below to the
 * sibling [StockSelectionServiceTest] pushed that class past detekt's `LargeClass` ceiling;
 * housing the whole lot-contract group here instead brought it back under.
 *
 * Two rules, per [`docs/functional/stock-selection.md`] sections 3 and 5:
 *
 *  1. `lotNumber` alone is a **preference with cross-lot fallback** - the any-lot passes
 *     (2/4/5/7/9/11/13) top up from another lot when the requested one runs short.
 *  2. `enforceLot` turns a *requested* lot into a **fence**: every any-lot pass is skipped
 *     across the whole baseline sequence, including the locked tier, and selection returns
 *     short rather than crossing lots. With no lot requested it gates nothing.
 *
 * Rule 2's second half is the one a 2026-09-06 backlog record alleged was broken
 * ("`enforceLot` short-circuits every any-lot pass"). It did not reproduce; the tests below
 * pin both halves so the claim cannot be re-derived from reading the guard alone.
 */
@QuarkusTest
class StockSelectionLotTest {

    @Inject
    lateinit var stockSelectionService: StockSelectionService

    /**
     * A product id unique to this test instance (JUnit's default per-method lifecycle gives each
     * test its own). The Dev Services Postgres container is reused across Gradle invocations, so a
     * fixed id would accumulate stock from every previous local run and quietly turn a
     * "returns short" assertion green - these lot tests assert on exact available amounts, so they
     * must own their product outright. Same reason `createUnitLoad` labels carry a nanoTime suffix.
     */
    private val itemDataId = System.nanoTime()

    /** Creates a UnitLoad with a unique label. Returns the UL id. */
    private fun createUnitLoad(label: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Creates an ON_STOCK StockUnit on [unitLoadId]. Returns the stock unit id. */
    private fun createStock(
        unitLoadId: Long,
        itemDataId: Long,
        itemNumber: String,
        amount: Double,
        lotNumber: String,
    ): Long =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300,"lotNumber":"$lotNumber"}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun lock(stockUnitId: Long) {
        given()
            .contentType(ContentType.JSON)
            .body("""{"lockType":7}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/lock")
            .then().statusCode(200)
    }

    private fun selectStock(
        itemDataId: Long,
        amount: Number,
        lotNumber: String? = null,
        useLockedStock: Boolean = false,
        enforceLot: Boolean = false,
    ): StockSelectionResponse = stockSelectionService.selectStock(
        StockSelectionRequest(
            itemDataId = itemDataId,
            amount = BigDecimal(amount.toString()),
            clientId = 1L,
            lotNumber = lotNumber,
            useLockedStock = useLockedStock,
            enforceLot = enforceLot,
        )
    )

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `enforceLot does not cross into other lots`() {
        val ul1 = createUnitLoad("UL-ENFLOT-${System.nanoTime()}")
        val ul2 = createUnitLoad("UL-ENFLOT-${System.nanoTime()}")
        createStock(ul1, itemDataId, "ENFLOT-001", 30.0, lotNumber = "LOT-7")
        createStock(ul2, itemDataId, "ENFLOT-001", 100.0, lotNumber = "LOT-9")

        // Demand 50 of LOT-7 (only 30 exists). enforceLot must NOT pull from LOT-9.
        val response = selectStock(
            itemDataId = itemDataId,
            amount = 50,
            lotNumber = "LOT-7",
            enforceLot = true,
        )

        assertThat(response.fullyFulfilled).isFalse()
        assertThat(response.stocks).hasSize(1)           // only the LOT-7 unit
        assertThat(response.stocks[0].availableAmount).isEqualByComparingTo(BigDecimal("30.0"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `without enforceLot, selection crosses into other lots`() {
        // Control case for the enforceLot test: same shape, but lot stays a preference.
        val ul1 = createUnitLoad("UL-XLOT-${System.nanoTime()}")
        val ul2 = createUnitLoad("UL-XLOT-${System.nanoTime()}")
        createStock(ul1, itemDataId, "XLOT-001", 30.0, lotNumber = "LOT-7")
        createStock(ul2, itemDataId, "XLOT-001", 100.0, lotNumber = "LOT-9")

        // Demand 50 of LOT-7: with no enforceLot, preferComplete crosses to LOT-9 (covers 50),
        // so the demand IS fully filled - proving lot is a preference, not a fence.
        val response = selectStock(itemDataId = itemDataId, amount = 50, lotNumber = "LOT-7")

        assertThat(response.fullyFulfilled).isTrue()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `enforceLot without a requested lot gates no pass`() {
        // enforceLot fences a REQUESTED lot; it is not a standalone switch. With no lotNumber
        // there is nothing to enforce, so all 13 passes still run. Kills the mutation where the
        // guard drops its `request.lotNumber != null` conjunct: every pass would then resolve a
        // null lot filter, return nothing, and this selection would come back empty.
        val ul1 = createUnitLoad("UL-ENFLOT-NOLOT-${System.nanoTime()}")
        val ul2 = createUnitLoad("UL-ENFLOT-NOLOT-${System.nanoTime()}")
        createStock(ul1, itemDataId, "ENFLOT-NOLOT-001", 30.0, lotNumber = "LOT-7")
        createStock(ul2, itemDataId, "ENFLOT-NOLOT-001", 30.0, lotNumber = "LOT-9")

        // No single unit covers 50, so passes 1-5 yield nothing and the partial passes
        // accumulate 30 + 20 across both lots.
        val response = selectStock(itemDataId = itemDataId, amount = 50, enforceLot = true)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(2)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `enforceLot fences the locked any-lot pass as well`() {
        // "Skip every any-lot fallback" spans the whole baseline sequence: the locked tier
        // (pass 11) is an any-lot pass too, so opting into locked stock must not reopen the
        // cross-lot door the flag exists to close.
        val ulWanted = createUnitLoad("UL-ENFLOT-LOCK-${System.nanoTime()}")
        val ulOther = createUnitLoad("UL-ENFLOT-LOCK-${System.nanoTime()}")
        val suWanted = createStock(ulWanted, itemDataId, "ENFLOT-LOCK-001", 30.0, lotNumber = "LOT-7")
        val suOther = createStock(ulOther, itemDataId, "ENFLOT-LOCK-001", 100.0, lotNumber = "LOT-9")
        lock(suOther)

        // Demand 50 of LOT-7 (only 30 exists). Pass 11 (locked, any lot) would cover the
        // shortfall from the locked LOT-9 unit; enforceLot must keep it out.
        val response = selectStock(
            itemDataId = itemDataId,
            amount = 50,
            lotNumber = "LOT-7",
            useLockedStock = true,
            enforceLot = true,
        )

        assertThat(response.fullyFulfilled).isFalse()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(suWanted)
        assertThat(response.stocks[0].availableAmount).isEqualByComparingTo(BigDecimal("30.0"))
    }
}
