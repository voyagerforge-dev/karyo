package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
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
 * Integration test (real beans + DB) for [DefaultStockUnitLookup.occupancyByLocationIds] —
 * the batched occupancy read added for the locations-layout sprint's Task 3 (the layout
 * module's putaway `useAreaStrategyDate`/`useItemDataArea` StorageArea logic).
 *
 * No layout entities are involved: `locationId` here is just the `StockUnit.unitLoad.
 * storageLocationId` value, an unenforced cross-module reference in this module's own terms
 * (mirrors [DefaultStockPickerTest]'s hardcoded `storageLocationId` fixtures).
 */
@QuarkusTest
class DefaultStockUnitLookupTest {

    @Inject
    lateinit var lookup: StockUnitLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockUnitRepository: com.karyo.inventory.repository.StockUnitRepository

    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":$locationId,"storageLocationName":"OCC-LOC"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, amount: Double, state: Int): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"OCC-SKU","amount":$amount,"unitLoadId":$ulId,"state":$state}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Direct entity persistence for a foreign tenant's stock -- REST is bound to the fixed
     * test principal (client 1), so a genuinely foreign-owned row needs this (mirrors
     * `LocationFinderStrategyOwnershipTest.createForeignStrategy`'s direct-entity pattern). */
    @Transactional
    fun seedForeignStock(locationId: Long, itemDataId: Long, amount: BigDecimal, foreignClientId: Long) {
        val em = stockUnitRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-FOREIGN-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = "OCC-LOC-FOREIGN"
            this.clientId = foreignClientId
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = foreignClientId
            this.itemDataId = itemDataId
            itemDataNumber = "OCC-FOREIGN-SKU"
            this.amount = amount
            unitLoad = ul
            state = 300
            strategyDate = Instant.now()
        }
        em.persist(su)
    }

    /** Direct entity persistence of an ON_STOCK unit load carrying [weight] (nullable, for the
     * degradation-path test) at [locationId] -- mirrors [seedForeignStock]'s pattern. REST has
     * no way to set `UnitLoad.weight` at creation ([createUnitLoad] can't cover this). Backs
     * [StockUnitLookup.grossWeightByLocationIds] (locations-layout sprint Task 7, L4). */
    @Transactional
    fun seedWeightedUnitLoad(locationId: Long, itemDataId: Long, weight: BigDecimal?, clientId: Long, state: Int = 300): Long {
        val em = stockUnitRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-WGT-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = "WGT-LOC"
            this.clientId = clientId
            this.weight = weight
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = clientId
            this.itemDataId = itemDataId
            itemDataNumber = "WGT-SKU"
            amount = BigDecimal.ONE
            unitLoad = ul
            this.state = state
        }
        em.persist(su)
        return ul.id!!
    }

    /** A second stock-unit row on an EXISTING unit load (different SKU) -- for the
     * mixed-SKU-pallet double-count regression test. */
    @Transactional
    fun addStockUnit(unitLoadId: Long, itemDataId: Long, clientId: Long) {
        val em = stockUnitRepository.getEntityManager()
        val ul = em.find(UnitLoad::class.java, unitLoadId)
        val su = StockUnit().apply {
            this.clientId = clientId
            this.itemDataId = itemDataId
            itemDataNumber = "WGT-SKU-2"
            amount = BigDecimal.ONE
            unitLoad = ul
            state = 300
        }
        em.persist(su)
    }

    private fun suffix() = System.nanoTime()

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `occupancyByLocationIds batches on-hand rows across multiple locations in one call`() {
        val s = suffix()
        val itemDataId = 40_000_000L + (s % 1_000_000)
        val locA = 50_000_000L + (s % 1_000_000)
        val locB = locA + 1
        val locUntouched = locA + 2

        val ulA = createUnitLoad("UL-OCC-A-$s", locA)
        val ulB = createUnitLoad("UL-OCC-B-$s", locB)
        createStock(ulA, itemDataId, 5.0, state = 300)
        createStock(ulB, itemDataId, 7.0, state = 300)

        tenantContext.clientId = 1L
        val result = lookup.occupancyByLocationIds(setOf(locA, locB, locUntouched))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.locationId }).containsExactlyInAnyOrder(locA, locB)
        assertThat(result).allMatch { it.itemDataId == itemDataId }
        assertThat(result.first { it.locationId == locA }.amount).isEqualByComparingTo("5.0")
        assertThat(result.first { it.locationId == locB }.amount).isEqualByComparingTo("7.0")
        assertThat(result.none { it.locationId == locUntouched }).isTrue()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `occupancyByLocationIds excludes INCOMING and PICKED-plus, includes only ON_STOCK`() {
        val s = suffix()
        val itemDataId = 40_100_000L + (s % 1_000_000)
        val loc = 50_100_000L + (s % 1_000_000)

        val ulIncoming = createUnitLoad("UL-OCC-INC-$s", loc)
        val ulOnStock = createUnitLoad("UL-OCC-ONS-$s", loc)
        val ulPicked = createUnitLoad("UL-OCC-PICK-$s", loc)
        createStock(ulIncoming, itemDataId, 1.0, state = 100) // INCOMING -- not yet on hand
        val onStockId = createStock(ulOnStock, itemDataId, 2.0, state = 300)
        createStock(ulPicked, itemDataId, 3.0, state = 600) // PICKED -- already departed

        tenantContext.clientId = 1L
        val result = lookup.occupancyByLocationIds(setOf(loc))

        assertThat(result).hasSize(1)
        assertThat(result.single().amount).isEqualByComparingTo("2.0")
        // Sanity: confirm we picked up the ON_STOCK row specifically, not by coincidence of count.
        assertThat(onStockId).isNotNull()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `occupancyByLocationIds excludes a foreign tenant's stock`() {
        val s = suffix()
        val itemDataId = 40_200_000L + (s % 1_000_000)
        val loc = 50_200_000L + (s % 1_000_000)
        val foreignClientId = s

        seedForeignStock(loc, itemDataId, BigDecimal.TEN, foreignClientId)

        tenantContext.clientId = 1L
        val result = lookup.occupancyByLocationIds(setOf(loc))

        assertThat(result).isEmpty()
    }

    // ── grossWeightByLocationIds (locations-layout sprint Task 7, L4) ────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `grossWeightByLocationIds batches unit-load weight across multiple locations in one call`() {
        val s = suffix()
        val itemDataId = 41_000_000L + (s % 1_000_000)
        val locA = 51_000_000L + (s % 1_000_000)
        val locB = locA + 1
        val locUntouched = locA + 2

        seedWeightedUnitLoad(locA, itemDataId, BigDecimal("120.500"), clientId = 1L)
        seedWeightedUnitLoad(locB, itemDataId, BigDecimal("75"), clientId = 1L)

        tenantContext.clientId = 1L
        val result = lookup.grossWeightByLocationIds(setOf(locA, locB, locUntouched))

        assertThat(result).hasSize(2)
        assertThat(result[locA]).isEqualByComparingTo("120.500")
        assertThat(result[locB]).isEqualByComparingTo("75")
        assertThat(result).doesNotContainKey(locUntouched)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `grossWeightByLocationIds sums per unit load, not per stock-unit row`() {
        val s = suffix()
        val itemDataId1 = 41_100_000L + (s % 1_000_000)
        val itemDataId2 = itemDataId1 + 1
        val loc = 51_100_000L + (s % 1_000_000)

        val ulId = seedWeightedUnitLoad(loc, itemDataId1, BigDecimal("120"), clientId = 1L)
        addStockUnit(ulId, itemDataId2, clientId = 1L) // second SKU, SAME unit load/weight

        tenantContext.clientId = 1L
        val result = lookup.grossWeightByLocationIds(setOf(loc))

        // 120, never 240 -- a mixed-SKU pallet's weight is not doubled by its second stock-unit row.
        assertThat(result[loc]).isEqualByComparingTo("120")
    }

    /**
     * A5 ruling (row :1436, defect-burndown-5): widened from ON_STOCK(300)-exact to
     * `state not in (SHIPPED(680), DELETABLE(1000))` -- the same window
     * [UnitLoadWeightCalculator] already computes off, since a PACKED or PICKED container still
     * physically rests on the rack and must count toward the lifting-capacity cap this method
     * backs. Previously this test pinned the OPPOSITE (INCOMING/PICKED excluded, ON_STOCK-only)
     * -- that assertion is what A5 flips. Six states, one per unit load: INCOMING/ON_STOCK/
     * PICKED/PACKED all count now; only SHIPPED and DELETABLE are excluded.
     */
    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `grossWeightByLocationIds counts everything still physically on the rack, excludes only SHIPPED and DELETABLE`() {
        val s = suffix()
        val itemDataId = 41_200_000L + (s % 1_000_000)
        val loc = 51_200_000L + (s % 1_000_000)

        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("10"), clientId = 1L, state = 100) // INCOMING -- now counts
        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("20"), clientId = 1L, state = 300) // ON_STOCK -- counts
        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("30"), clientId = 1L, state = 600) // PICKED -- now counts
        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("40"), clientId = 1L, state = 650) // PACKED -- now counts
        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("50"), clientId = 1L, state = 680) // SHIPPED -- excluded
        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("60"), clientId = 1L, state = 1000) // DELETABLE -- excluded

        tenantContext.clientId = 1L
        val result = lookup.grossWeightByLocationIds(setOf(loc))

        // 10 + 20 + 30 + 40 = 100 -- SHIPPED(50) and DELETABLE(60) dropped.
        assertThat(result[loc]).isEqualByComparingTo("100")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `grossWeightByLocationIds DELIBERATELY counts a foreign tenant's weighted stock - rack load is physical, not per-owner (final-review F3)`() {
        val s = suffix()
        val itemDataId = 41_300_000L + (s % 1_000_000)
        val loc = 51_300_000L + (s % 1_000_000)
        val foreignClientId = s

        seedWeightedUnitLoad(loc, itemDataId, BigDecimal("999"), clientId = foreignClientId)

        // Unlike occupancyByLocationIds (product-scoped, genuinely per-owner), gross weight
        // is a lifting-capacity safety read: another tenant's pallet on the same physical
        // group still presses down on it. Pinned UNSCOPED per the final review's F3 fix --
        // this test previously asserted the opposite (excluded), which under-counted real
        // weight for an OWNER-scoped caller.
        tenantContext.clientId = 1L
        val result = lookup.grossWeightByLocationIds(setOf(loc))

        assertThat(result[loc]).isEqualByComparingTo("999")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `grossWeightByLocationIds treats an unweighed unit load as zero`() {
        val s = suffix()
        val itemDataId = 41_400_000L + (s % 1_000_000)
        val loc = 51_400_000L + (s % 1_000_000)

        seedWeightedUnitLoad(loc, itemDataId, weight = null, clientId = 1L)

        tenantContext.clientId = 1L
        val result = lookup.grossWeightByLocationIds(setOf(loc))

        assertThat(result[loc]).isEqualByComparingTo("0")
    }
}
