package com.karyo.inventory.service

import com.karyo.inventory.api.dto.StockSelectionResponse
import com.karyo.inventory.api.vo.PickingType
import com.karyo.inventory.api.vo.StockSelectionRequest
import com.karyo.inventory.domain.model.InactiveProduct
import com.karyo.inventory.repository.InactiveProductRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Exercises the 13-pass FIFO stock-selection algorithm directly through
 * [StockSelectionService.selectStock] — no REST round-trip. This used to go through the (now
 * deleted) `/api/internal/stock-selection` router; converted 1:1, same scenarios/assertions.
 * Seed data (unit loads, stock units) is still created via `/api/v1` REST endpoints since
 * that surface stays.
 *
 * The lot-targeting contract (`lotNumber` preference vs. the `enforceLot` strict-lot fence)
 * lives in the sibling [StockSelectionLotTest]: adding the new lot tests here pushed this class
 * past detekt's `LargeClass` ceiling, so the whole lot-contract group moved there, bringing this
 * class back under.
 */
@QuarkusTest
class StockSelectionServiceTest {

    @Inject
    lateinit var stockSelectionService: StockSelectionService

    @Inject
    lateinit var inactiveProductRepository: InactiveProductRepository

    @Transactional
    fun seedInactive(itemDataId: Long, clientId: Long) {
        inactiveProductRepository.persist(InactiveProduct().apply {
            this.itemDataId = itemDataId
            this.clientId = clientId
            this.deactivated = Instant.now()
        })
    }

    @Transactional
    fun clearInactive(itemDataId: Long, clientId: Long) {
        inactiveProductRepository.delete("itemDataId = ?1 and clientId = ?2", itemDataId, clientId)
    }

    /**
     * Creates a UnitLoad with a unique label. Returns the UL id.
     */
    private fun createUnitLoad(label: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /**
     * Creates a UnitLoad at an explicit (real) storage location — used by the L7 fix-assignment
     * tests below, which need a genuine `StorageLocation` row (unlike the plain [createUnitLoad]
     * above, whose hardcoded `storageLocationId=100` is a denormalized, unvalidated field —
     * fine for FIFO-only scenarios, but `FixAssignment.location` carries a real FK).
     */
    private fun createUnitLoadAt(label: String, locationId: Long, locationName: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":$locationId,"storageLocationName":"$locationName"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── L7 fix-assignment seeding helpers ──────────────────────────────────

    // FixAssignmentService.create validates the product via ProductLookup (must exist and be
    // ACTIVE) — unlike createStock's itemDataId, which is a denormalized, unvalidated field.
    // So every L7 test below uses a REAL product id (from these two helpers), not an arbitrary
    // constant like the other tests in this file.
    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"L7 Selection Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun seedActiveProduct(tag: String): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val itemUnitId = createItemUnit("IU-$tag-$ns")
        return createProduct("$tag-$ns", itemUnitId)
    }

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStorageLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Creates a real StorageLocation (location-type + area + location), returns its id. */
    private fun seedStorageLocation(tag: String): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-$tag-$ns")
        val areaId = createArea("AREA-$tag-$ns")
        return createStorageLocation("LOC-$tag-$ns", ltId, areaId)
    }

    private fun createFixAssignment(locationId: Long, itemDataId: Long, maxPickAmount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId,"maxPickAmount":$maxPickAmount}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /**
     * Creates a StockUnit on the given UnitLoad with ON_STOCK state (300) by default.
     * Uses a unique itemDataId per test scenario to avoid cross-test interference.
     */
    private fun createStock(
        unitLoadId: Long,
        itemDataId: Long,
        itemNumber: String,
        amount: Double,
        state: Int = 300,
        lotNumber: String? = null,
    ): Long {
        val lotField = if (lotNumber != null) ""","lotNumber":"$lotNumber"""" else ""
        return given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,"unitLoadId":$unitLoadId,"state":$state$lotField}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    /**
     * Direct call into [StockSelectionService.selectStock], replacing the deleted
     * `GET /api/internal/stock-selection` call with the same parameter values the query
     * string used to pass. Defaults mirror the ones the deleted resource applied.
     */
    private fun selectStock(
        itemDataId: Long,
        amount: Number,
        clientId: Long = 1L,
        lotNumber: String? = null,
        useLockedStock: Boolean = false,
        preferComplete: Boolean = true,
        preferMatching: Boolean = false,
        completeHandling: Int = 0,
        enforceLot: Boolean = false,
    ): StockSelectionResponse = stockSelectionService.selectStock(
        StockSelectionRequest(
            itemDataId = itemDataId,
            amount = BigDecimal(amount.toString()),
            clientId = clientId,
            lotNumber = lotNumber,
            useLockedStock = useLockedStock,
            preferComplete = preferComplete,
            preferMatching = preferMatching,
            completeHandling = completeHandling,
            enforceLot = enforceLot,
        )
    )

    @AfterEach
    fun resetReorderFilter() {
        TestReorderFilter.reverse = false
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `honors SPI filter reordering`() {
        val itemDataId = 2099L
        val ul1 = createUnitLoad("UL-SEL-REORDER-${System.nanoTime()}")
        val ul2 = createUnitLoad("UL-SEL-REORDER-${System.nanoTime()}")
        // s1 is FIFO-first (created first); s2 second.
        createStock(ul1, itemDataId, "SEL-REORDER-001", 50.0)
        val s2 = createStock(ul2, itemDataId, "SEL-REORDER-001", 30.0)

        // Pure FIFO picks s1 (50) first; the reverse filter flips the candidate order so the
        // first pick is s2 — proving the core honors filter reordering, not just vetoes.
        TestReorderFilter.reverse = true
        val response = selectStock(itemDataId = itemDataId, amount = 70)

        assertThat(response.stocks).hasSize(2)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(s2)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `selects available stock in FIFO order`() {
        val itemDataId = 2001L
        val ul1 = createUnitLoad("UL-SEL-FIFO-${System.nanoTime()}")
        val ul2 = createUnitLoad("UL-SEL-FIFO-${System.nanoTime()}")

        createStock(ul1, itemDataId, "SEL-FIFO-001", 50.0)
        createStock(ul2, itemDataId, "SEL-FIFO-001", 30.0)

        val response = selectStock(itemDataId = itemDataId, amount = 70)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(2)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `returns partial when insufficient stock`() {
        val itemDataId = 2002L
        val ul = createUnitLoad("UL-SEL-PARTIAL-${System.nanoTime()}")

        createStock(ul, itemDataId, "SEL-PARTIAL-002", 10.0)

        val response = selectStock(itemDataId = itemDataId, amount = 50)

        assertThat(response.fullyFulfilled).isFalse()
        assertThat(response.stocks).hasSize(1)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `skips non-ON_STOCK state`() {
        val itemDataId = 2003L
        val ul = createUnitLoad("UL-SEL-STATE-${System.nanoTime()}")

        // Create stock in UNDEFINED state (0) — should not be selected
        createStock(ul, itemDataId, "SEL-STATE-003", 50.0, state = 0)

        val response = selectStock(itemDataId = itemDataId, amount = 10)

        assertThat(response.stocks).isEmpty()
        assertThat(response.fullyFulfilled).isFalse()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `skips locked stock by default`() {
        val itemDataId = 2004L
        val ul = createUnitLoad("UL-SEL-LOCK-${System.nanoTime()}")

        val suId = createStock(ul, itemDataId, "SEL-LOCK-004", 50.0)

        // Lock the stock unit
        given()
            .contentType(ContentType.JSON)
            .body("""{"lockType":7}""")
            .`when`().post("/api/v1/stock-units/$suId/lock")
            .then().statusCode(200)

        // Without useLockedStock, locked stock should be excluded
        val response = selectStock(itemDataId = itemDataId, amount = 10)

        assertThat(response.stocks).isEmpty()
        assertThat(response.fullyFulfilled).isFalse()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `includes locked stock when useLockedStock is true`() {
        val itemDataId = 2005L
        val ul = createUnitLoad("UL-SEL-ULOCK-${System.nanoTime()}")

        val suId = createStock(ul, itemDataId, "SEL-ULOCK-005", 50.0)

        // Lock the stock unit
        given()
            .contentType(ContentType.JSON)
            .body("""{"lockType":7}""")
            .`when`().post("/api/v1/stock-units/$suId/lock")
            .then().statusCode(200)

        // With useLockedStock=true, locked stock should be found in passes 10-11
        val response = selectStock(itemDataId = itemDataId, amount = 10, useLockedStock = true)

        assertThat(response.stocks).hasSize(1)
        assertThat(response.fullyFulfilled).isTrue()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `returns empty when no matching item exists`() {
        val response = selectStock(itemDataId = 99999, amount = 10)

        assertThat(response.stocks).isEmpty()
        assertThat(response.fullyFulfilled).isFalse()
        assertThat(response.totalAvailable).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `excludes stock for inactive product`() {
        val itemDataId = 2099L
        val ul = createUnitLoad("UL-SEL-INACTIVE-${System.nanoTime()}")
        createStock(ul, itemDataId, "INACTIVE-PROD-099", 50.0)

        // Mark product as inactive
        seedInactive(itemDataId, 1L)

        try {
            val response = selectStock(itemDataId = itemDataId, amount = 10)

            assertThat(response.stocks).isEmpty()
            assertThat(response.fullyFulfilled).isFalse()
        } finally {
            clearInactive(itemDataId, 1L)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `preferMatching picks the exact-amount unit over an older oversized one`() {
        val itemDataId = 2120L
        val ulOld = createUnitLoad("UL-PM-${System.nanoTime()}")
        val ulExact = createUnitLoad("UL-PM-${System.nanoTime()}")
        // ulOld created first => FIFO-older. Default preferComplete would pick the older 120.
        createStock(ulOld, itemDataId, "PM-001", 120.0)
        val sExact = createStock(ulExact, itemDataId, "PM-001", 100.0)

        val response = selectStock(itemDataId = itemDataId, amount = 100, preferMatching = true)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(sExact)   // the exact 100, not the older 120
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling AMOUNT_FIRST_MATCH takes the exact full pallet`() {
        val itemDataId = 2150L
        val ulA = createUnitLoad("UL-CH1-${System.nanoTime()}")
        val ulB = createUnitLoad("UL-CH1-${System.nanoTime()}")
        createStock(ulA, itemDataId, "CH1-001", 60.0)
        val sExact = createStock(ulB, itemDataId, "CH1-001", 100.0)

        val response = selectStock(itemDataId = itemDataId, amount = 100, completeHandling = 1)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(sExact)
        assertThat(response.stocks[0].pickingType).isEqualTo(PickingType.COMPLETE)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling returns nothing when no complete UL covers the demand`() {
        val itemDataId = 2151L
        val ul = createUnitLoad("UL-CH2-${System.nanoTime()}")
        createStock(ul, itemDataId, "CH2-001", 40.0)   // only a partial; no complete UL covers 100

        val response = selectStock(itemDataId = itemDataId, amount = 100, completeHandling = 2) // AMOUNT_FIRST_PLUS

        assertThat(response.fullyFulfilled).isFalse()
        assertThat(response.stocks).isEmpty()      // complete-or-nothing: NOT the partial 40
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling AMOUNT_MATCH combines complete ULs to the exact total`() {
        val itemDataId = 2160L
        val ulA = createUnitLoad("UL-CH3-${System.nanoTime()}")
        val ulB = createUnitLoad("UL-CH3-${System.nanoTime()}")
        val ulC = createUnitLoad("UL-CH3-${System.nanoTime()}")
        createStock(ulA, itemDataId, "CH3-001", 30.0)
        createStock(ulB, itemDataId, "CH3-001", 40.0)
        createStock(ulC, itemDataId, "CH3-001", 25.0)
        // demand 70 == 30 + 40 exactly (two complete ULs); 25 is left out.
        val response = selectStock(itemDataId = itemDataId, amount = 70, completeHandling = 3)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(2)
        assertThat(response.stocks.sumOf { it.suggestedPickAmount ?: BigDecimal.ZERO })
            .isEqualByComparingTo(BigDecimal("70.0"))
    }

    // -------------------------------------------------------------------------
    // T1 — FIX 1 regression: completeHandling overrides preferMatching
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling overrides preferMatching - strict complete wins`() {
        // UL-A has TWO stock units of the same item → multi-stock → NOT strict-complete.
        // The 100-unit on UL-A is an exact amount match for the demand of 100.
        // UL-B (created AFTER UL-A, so FIFO-later) has a single 100-unit → strict-complete.
        // With preferMatching=true AND completeHandling=1 (AMOUNT_FIRST_MATCH),
        // completeHandling must win: the non-complete UL-A unit is skipped; UL-B is picked.
        // Before FIX 1 this test FAILS (preferMatching short-circuits with UL-A's 100-unit).
        val itemDataId = 2180L
        val ulA = createUnitLoad("UL-T1-A-${System.nanoTime()}")
        val ulB = createUnitLoad("UL-T1-B-${System.nanoTime()}")

        // Make UL-A multi-stock (exact-amount match but NOT strict-complete)
        createStock(ulA, itemDataId, "T1-ITEM", 100.0)
        createStock(ulA, itemDataId, "T1-ITEM", 5.0)

        // UL-B: single-stock, strict-complete candidate
        val sBId = createStock(ulB, itemDataId, "T1-ITEM", 100.0)

        val response = selectStock(
            itemDataId = itemDataId,
            amount = 100,
            preferMatching = true,
            completeHandling = 1, // AMOUNT_FIRST_MATCH
        )

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(sBId)   // UL-B's strict-complete unit
        assertThat(response.stocks[0].pickingType).isEqualTo(PickingType.COMPLETE)
    }

    // -------------------------------------------------------------------------
    // T2a — completeHandling AMOUNT_SMALLEST_DIFF picks closest complete combo
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling AMOUNT_SMALLEST_DIFF picks the closest complete combination`() {
        // Three single-stock complete ULs: 30, 45, 80. Demand 50, mode=4 (AMOUNT_SMALLEST_DIFF).
        // Closest single: 45 (|45-50|=5). Closest pair: 30+45=75 (|75-50|=25) or 30+80=110 (60).
        // Optimizer should pick 45.
        val itemDataId = 2181L
        val ul30 = createUnitLoad("UL-T2A-30-${System.nanoTime()}")
        val ul45 = createUnitLoad("UL-T2A-45-${System.nanoTime()}")
        val ul80 = createUnitLoad("UL-T2A-80-${System.nanoTime()}")
        createStock(ul30, itemDataId, "T2A-ITEM", 30.0)
        createStock(ul45, itemDataId, "T2A-ITEM", 45.0)
        createStock(ul80, itemDataId, "T2A-ITEM", 80.0)

        val response = selectStock(itemDataId = itemDataId, amount = 50, completeHandling = 4) // AMOUNT_SMALLEST_DIFF

        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks.sumOf { it.suggestedPickAmount ?: BigDecimal.ZERO })
            .isEqualByComparingTo(BigDecimal("45.0"))
    }

    // -------------------------------------------------------------------------
    // T2b — completeHandling AMOUNT_SMALLEST_PLUS picks smallest at-or-above demand
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling AMOUNT_SMALLEST_PLUS picks smallest combination at or above demand`() {
        // Single-stock complete ULs: 30, 45, 80. Demand 50, mode=5 (AMOUNT_SMALLEST_PLUS).
        // Smallest at-or-above 50: single 80 (80≥50) or combo 30+45=75≥50.
        // 75 < 80 so 30+45 wins.
        val itemDataId = 2182L
        val ul30 = createUnitLoad("UL-T2B-30-${System.nanoTime()}")
        val ul45 = createUnitLoad("UL-T2B-45-${System.nanoTime()}")
        val ul80 = createUnitLoad("UL-T2B-80-${System.nanoTime()}")
        createStock(ul30, itemDataId, "T2B-ITEM", 30.0)
        createStock(ul45, itemDataId, "T2B-ITEM", 45.0)
        createStock(ul80, itemDataId, "T2B-ITEM", 80.0)

        val response = selectStock(itemDataId = itemDataId, amount = 50, completeHandling = 5) // AMOUNT_SMALLEST_PLUS

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks.sumOf { it.suggestedPickAmount ?: BigDecimal.ZERO })
            .isEqualByComparingTo(BigDecimal("75.0"))
    }

    // -------------------------------------------------------------------------
    // T3 — completeHandling AMOUNT_FIRST_PLUS takes the whole oversized pallet
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling AMOUNT_FIRST_PLUS takes the whole oversized pallet`() {
        // One single-stock complete UL of 120. Demand 100, mode=2 (AMOUNT_FIRST_PLUS).
        // The whole pallet (120) is picked even though it exceeds demand — complete-pick contract.
        val itemDataId = 2183L
        val ul = createUnitLoad("UL-T3-${System.nanoTime()}")
        val sId = createStock(ul, itemDataId, "T3-ITEM", 120.0)

        val response = selectStock(itemDataId = itemDataId, amount = 100, completeHandling = 2) // AMOUNT_FIRST_PLUS

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(sId)
        assertThat(response.stocks[0].suggestedPickAmount).isEqualByComparingTo(BigDecimal("120.0"))
        assertThat(response.stocks[0].pickingType).isEqualTo(PickingType.COMPLETE)
    }

    // -------------------------------------------------------------------------
    // T4 — completeHandling rejects a non-strict-complete unit (multi-stock UL)
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling skips a non-strict-complete exact unit`() {
        // ONE multi-stock UL: 100-unit + 5-unit of the same item.
        // The 100-unit exactly matches demand=100 but the UL is multi-stock → NOT strict-complete.
        // completeHandling=1 (AMOUNT_FIRST_MATCH) must skip it → empty result (complete-or-nothing).
        val itemDataId = 2184L
        val ul = createUnitLoad("UL-T4-${System.nanoTime()}")
        createStock(ul, itemDataId, "T4-ITEM", 100.0)
        createStock(ul, itemDataId, "T4-ITEM", 5.0)   // makes UL multi-stock

        val response = selectStock(itemDataId = itemDataId, amount = 100, completeHandling = 1) // AMOUNT_FIRST_MATCH

        assertThat(response.stocks).isEmpty()
        assertThat(response.fullyFulfilled).isFalse()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `includes stock once product is re-activated`() {
        val itemDataId = 2098L
        val ul = createUnitLoad("UL-SEL-REACTIVATE-${System.nanoTime()}")
        createStock(ul, itemDataId, "REACTIVATE-PROD-098", 50.0)

        // Mark product as inactive
        seedInactive(itemDataId, 1L)

        // Should return 0 candidates while inactive
        val whileInactive = selectStock(itemDataId = itemDataId, amount = 10)
        assertThat(whileInactive.stocks).isEmpty()
        assertThat(whileInactive.fullyFulfilled).isFalse()

        // Re-activate the product
        clearInactive(itemDataId, 1L)

        // Should now return candidates
        val afterReactivate = selectStock(itemDataId = itemDataId, amount = 10)
        assertThat(afterReactivate.stocks).hasSize(1)
        assertThat(afterReactivate.fullyFulfilled).isTrue()
    }

    // -------------------------------------------------------------------------
    // L7 — FixAssignment.maxPickAmount: soft per-pick ceiling on a fixed slot
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `over-ceiling fix-location stock is skipped, next candidate is selected`() {
        val itemDataId = seedActiveProduct("L7A")
        val fixLocationId = seedStorageLocation("L7A-FIX")
        val otherLocationId = seedStorageLocation("L7A-OTHER")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 10.0)

        val fixUl = createUnitLoadAt("UL-L7A-FIX-${System.nanoTime()}", fixLocationId, "LOC-L7A-FIX")
        createStock(fixUl, itemDataId, "L7A-ITEM", 50.0)
        val otherUl = createUnitLoadAt("UL-L7A-OTHER-${System.nanoTime()}", otherLocationId, "LOC-L7A-OTHER")
        val otherStockId = createStock(otherUl, itemDataId, "L7A-ITEM", 50.0)

        // Request (20) exceeds the fixed slot's ceiling (10) — it must be skipped entirely,
        // falling through to the other location's stock.
        val response = selectStock(itemDataId = itemDataId, amount = 20)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(otherStockId)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `ceiling at or above requested amount selects the fix stock normally`() {
        val itemDataId = seedActiveProduct("L7B")
        val fixLocationId = seedStorageLocation("L7B-FIX")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 10.0)

        val fixUl = createUnitLoadAt("UL-L7B-${System.nanoTime()}", fixLocationId, "LOC-L7B-FIX")
        val fixStockId = createStock(fixUl, itemDataId, "L7B-ITEM", 50.0)

        // Requested amount (10) equals the ceiling — NOT "< ceiling", so the fix slot is
        // selected normally (this is the only candidate; a wrong skip would zero the result).
        val response = selectStock(itemDataId = itemDataId, amount = 10)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(fixStockId)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `fix ceiling for a different product on the same location has no effect`() {
        val requestedItemDataId = seedActiveProduct("L7C-REQ")
        val fixedItemDataId = seedActiveProduct("L7C-FIX")
        val locationId = seedStorageLocation("L7C")
        // Ceiling of 5 is set for a DIFFERENT product on this location.
        createFixAssignment(locationId, fixedItemDataId, maxPickAmount = 5.0)

        val ul = createUnitLoadAt("UL-L7C-${System.nanoTime()}", locationId, "LOC-L7C")
        val stockId = createStock(ul, requestedItemDataId, "L7C-ITEM", 50.0)

        // Requesting 20 of the UN-fixed product at the same location must not be capped by the
        // other product's ceiling.
        val response = selectStock(itemDataId = requestedItemDataId, amount = 20)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(stockId)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `no fix row means selection is unchanged - regression pin`() {
        val itemDataId = seedActiveProduct("L7D")
        val locationId = seedStorageLocation("L7D")
        // No FixAssignment created at all for this location/product.

        val ul = createUnitLoadAt("UL-L7D-${System.nanoTime()}", locationId, "LOC-L7D")
        val stockId = createStock(ul, itemDataId, "L7D-ITEM", 50.0)

        val response = selectStock(itemDataId = itemDataId, amount = 20)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(stockId)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `over-ceiling fix stock with no other source shorts the pick without erroring`() {
        val itemDataId = seedActiveProduct("L7E")
        val fixLocationId = seedStorageLocation("L7E")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 10.0)

        val fixUl = createUnitLoadAt("UL-L7E-${System.nanoTime()}", fixLocationId, "LOC-L7E")
        createStock(fixUl, itemDataId, "L7E-ITEM", 50.0)   // plenty available, but capped by the fix ceiling

        // Only candidate is the over-ceiling fix stock; no other source exists. The request
        // must short (not error) exactly like today's "insufficient stock" shortage path.
        val response = selectStock(itemDataId = itemDataId, amount = 20)

        assertThat(response.fullyFulfilled).isFalse()
        assertThat(response.stocks).isEmpty()
        assertThat(response.totalAvailable).isEqualByComparingTo(BigDecimal.ZERO)
    }

    // -------------------------------------------------------------------------
    // L7 fix round 1 — task-9 review Finding 1 (Major): the ceiling was only enforced in the
    // main 13-pass loop. preferMatching (exactMatchCandidate) and completeHandling
    // (selectCompleteHandling) each query stock independently and must honor the ceiling too —
    // the digest's rule carries no selection-mode qualifier.
    // -------------------------------------------------------------------------

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `preferMatching skips an over-ceiling exact match and falls back to normal FIFO`() {
        val itemDataId = seedActiveProduct("L7F")
        val fixLocationId = seedStorageLocation("L7F-FIX")
        val otherLocationId = seedStorageLocation("L7F-OTHER")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 10.0)

        // Exact-amount candidate on the fixed slot — preferMatching would normally take the
        // WHOLE 20 from this one unit in a single line, defeating the ceiling of 10.
        val fixUl = createUnitLoadAt("UL-L7F-FIX-${System.nanoTime()}", fixLocationId, "LOC-L7F-FIX")
        createStock(fixUl, itemDataId, "L7F-ITEM", 20.0)
        // Fallback stock elsewhere, also enough to fully satisfy the request via normal FIFO.
        val otherUl = createUnitLoadAt("UL-L7F-OTHER-${System.nanoTime()}", otherLocationId, "LOC-L7F-OTHER")
        val otherStockId = createStock(otherUl, itemDataId, "L7F-ITEM", 20.0)

        val response = selectStock(itemDataId = itemDataId, amount = 20, preferMatching = true)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(otherStockId)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `preferMatching selects the exact match normally when ceiling meets the target`() {
        val itemDataId = seedActiveProduct("L7G")
        val fixLocationId = seedStorageLocation("L7G")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 20.0)

        val fixUl = createUnitLoadAt("UL-L7G-${System.nanoTime()}", fixLocationId, "LOC-L7G")
        val fixStockId = createStock(fixUl, itemDataId, "L7G-ITEM", 20.0)

        // Ceiling (20) equals the request (20) — NOT "< ceiling", so preferMatching still wins;
        // this is the only candidate, so a wrong skip would zero the result.
        val response = selectStock(itemDataId = itemDataId, amount = 20, preferMatching = true)

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(fixStockId)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling skips an over-ceiling strict-complete candidate and falls back`() {
        val itemDataId = seedActiveProduct("L7H")
        val fixLocationId = seedStorageLocation("L7H-FIX")
        val otherLocationId = seedStorageLocation("L7H-OTHER")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 10.0)

        // Exact-amount, single-stock, unopened UL on the fixed slot — strict-complete AND an
        // exact match for AMOUNT_FIRST_MATCH, but over the ceiling of 10.
        val fixUl = createUnitLoadAt("UL-L7H-FIX-${System.nanoTime()}", fixLocationId, "LOC-L7H-FIX")
        createStock(fixUl, itemDataId, "L7H-ITEM", 20.0)
        // Same shape elsewhere, no fix — the "next-best" fallback candidate.
        val otherUl = createUnitLoadAt("UL-L7H-OTHER-${System.nanoTime()}", otherLocationId, "LOC-L7H-OTHER")
        val otherStockId = createStock(otherUl, itemDataId, "L7H-ITEM", 20.0)

        val response = selectStock(itemDataId = itemDataId, amount = 20, completeHandling = 1) // AMOUNT_FIRST_MATCH

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(otherStockId)
        assertThat(response.stocks[0].pickingType).isEqualTo(PickingType.COMPLETE)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `completeHandling selects the strict-complete candidate normally when ceiling meets the target`() {
        val itemDataId = seedActiveProduct("L7I")
        val fixLocationId = seedStorageLocation("L7I")
        createFixAssignment(fixLocationId, itemDataId, maxPickAmount = 20.0)

        val fixUl = createUnitLoadAt("UL-L7I-${System.nanoTime()}", fixLocationId, "LOC-L7I")
        val fixStockId = createStock(fixUl, itemDataId, "L7I-ITEM", 20.0)

        val response = selectStock(itemDataId = itemDataId, amount = 20, completeHandling = 1) // AMOUNT_FIRST_MATCH

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks).hasSize(1)
        assertThat(response.stocks[0].stockUnitId).isEqualTo(fixStockId)
    }
}
