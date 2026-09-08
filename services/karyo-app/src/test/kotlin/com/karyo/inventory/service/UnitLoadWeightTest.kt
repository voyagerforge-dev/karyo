package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemUnit
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

private const val ACME = 1L
private const val GLOBEX = 2L
private const val SEEDED_ITEM_UNIT_ID = 1L

/**
 * Row 16: [UnitLoadWeightCalculator] wired through [StockService]'s mutation points and read
 * back via `GET /api/v1/unit-loads/{id}` and [StockUnitLookup.grossWeightByLocationIds].
 *
 * Closes a live bug: nothing wrote [com.karyo.inventory.domain.model.UnitLoad.weight] before
 * this class existed, so `StockUnitRepository.findOnStockUnitLoadWeightByLocationIds` (and the
 * layout module's location-group lifting-capacity check that reads it) always summed zero. The
 * last test in this file is the regression pin for that bug.
 */
@QuarkusTest
class UnitLoadWeightTest {

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var grossWeightLookup: StockUnitLookup

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    // ── REST seeding helpers (existing services, matching sibling-test style) ────────────────

    private fun createUnitLoadType(weight: BigDecimal?): Long {
        val s = System.nanoTime()
        val weightField = if (weight != null) ""","weight":$weight""" else ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"WGT-ULT-$s"$weightField}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createUnitLoad(unitLoadTypeId: Long, locationId: Long = 900): Long {
        val s = System.nanoTime()
        return given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"UL-WGT-$s","unitLoadTypeId":$unitLoadTypeId,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"WGT-LOC"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createProduct(weight: BigDecimal?): Long {
        val s = System.nanoTime()
        val weightField = if (weight != null) ""","weight":$weight""" else ""
        return given().contentType(ContentType.JSON)
            .body("""{"number":"WGT-SKU-$s","name":"Weight Product","itemUnitId":1$weightField}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createStock(unitLoadId: Long, itemDataId: Long, amount: Double, state: Int = 300): Long {
        val s = System.nanoTime()
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"WGT-SKU-$s","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":$state}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun deleteStock(stockUnitId: Long) {
        given().`when`().delete("/api/v1/stock-units/$stockUnitId").then().statusCode(204)
    }

    /** Forward-only state change via the REST route, used here to reach PICKED(600). */
    private fun moveToState(stockUnitId: Long, state: Int) {
        given().contentType(ContentType.JSON).body("""{"state":$state}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/change-state").then().statusCode(200)
    }

    private fun setWeightMeasure(unitLoadId: Long, weightMeasure: BigDecimal?) {
        val body = if (weightMeasure != null) """{"weightMeasure":$weightMeasure}""" else """{"weightMeasure":null}"""
        given().contentType(ContentType.JSON).body(body)
            .`when`().put("/api/v1/unit-loads/$unitLoadId/weight-measure")
            .then().statusCode(200)
    }

    /** Named variant of [createUnitLoadType], for the tare-cascade test which needs the name
     * back to build a full-representation PUT body. */
    private fun createNamedUnitLoadType(weight: BigDecimal?): Pair<Long, String> {
        val name = "WGT-ULT-TARE-${System.nanoTime()}"
        val weightField = if (weight != null) ""","weight":$weight""" else ""
        val id = given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$weightField}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        return id to name
    }

    /** Full-representation PUT that only varies [weight] (the type's tare) -- row :1411. */
    private fun updateUnitLoadTypeWeight(unitLoadTypeId: Long, name: String, weight: BigDecimal) {
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","weight":$weight}""")
            .`when`().put("/api/v1/unit-load-types/$unitLoadTypeId")
            .then().statusCode(200)
    }

    data class UnitLoadWeights(val weight: BigDecimal?, val weightCalculated: BigDecimal?, val weightMeasure: BigDecimal?)

    private fun weightsOf(unitLoadId: Long): UnitLoadWeights {
        val json = given().`when`().get("/api/v1/unit-loads/$unitLoadId").then().statusCode(200).extract().jsonPath()
        return UnitLoadWeights(
            weight = json.getString("weight")?.let { BigDecimal(it) },
            weightCalculated = json.getString("weightCalculated")?.let { BigDecimal(it) },
            weightMeasure = json.getString("weightMeasure")?.let { BigDecimal(it) },
        )
    }

    /**
     * CRITICAL-fix regression fixture (defect-burndown-5, task-3 review): direct entity
     * persistence for a SECOND client's product + unit load + stock on a SHARED unit load type
     * -- mirrors [DefaultStockUnitLookupTest.seedForeignStock]'s pattern. Needed because REST in
     * this test class is bound to the fixed ACME(1) `@TestSecurity`/`@OidcSecurity` principal; a
     * genuinely different-client row (and the item that must survive the OTHER client's ambient
     * tenant scope) needs bypassing REST entirely.
     */
    @Transactional
    fun seedForeignTareCascadeFixture(unitLoadTypeId: Long, foreignClientId: Long, itemWeight: BigDecimal): Long {
        val em = stockUnitRepository.getEntityManager()
        val itemUnit = em.find(ItemUnit::class.java, SEEDED_ITEM_UNIT_ID)
        val product = ItemData().apply {
            this.clientId = foreignClientId
            number = "WGT-FOREIGN-SKU-${System.nanoTime()}"
            name = "Foreign Weight Product"
            this.itemUnit = itemUnit
            weight = itemWeight
        }
        em.persist(product)
        val ult = em.find(UnitLoadType::class.java, unitLoadTypeId)
        val ul = UnitLoad().apply {
            labelId = "UL-FOREIGN-TARE-${System.nanoTime()}"
            unitLoadType = ult
            storageLocationId = 900
            storageLocationName = "WGT-LOC-FOREIGN"
            this.clientId = foreignClientId
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = foreignClientId
            itemDataId = requireNotNull(product.id)
            itemDataNumber = product.number
            amount = BigDecimal.ONE
            unitLoad = ul
            state = 300
        }
        em.persist(su)
        return requireNotNull(ul.id)
    }

    /** Direct entity read of a unit load's weight fields -- for a foreign-client unit load the
     * fixed ACME `@TestSecurity` REST principal in this class cannot [weightsOf] (that route is
     * owner-scoped, so a cross-client GET would 404). */
    @Transactional
    fun weightsOfEntity(unitLoadId: Long): UnitLoadWeights {
        val ul = requireNotNull(stockUnitRepository.getEntityManager().find(UnitLoad::class.java, unitLoadId))
        return UnitLoadWeights(weight = ul.weight, weightCalculated = ul.weightCalculated, weightMeasure = ul.weightMeasure)
    }

    // ── Tests ──────────────────────────────────────────────────────────────────────────────

    /**
     * Row :1411 (defect-burndown-5): before [UnitLoadTypeService.update] called
     * [UnitLoadWeightCalculator.recalculateAll], editing a type's tare left every existing unit
     * load of that type reading its OLD [UnitLoadWeights.weightCalculated] forever -- nothing
     * ever re-derived it off a type mutation, only off a stock mutation. This pins the fix
     * across TWO unit loads of the same type (proving it is a real batch, not "the first one
     * only") and checks the operator override on one of them survives the cascade unchanged.
     */
    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `changing a unit load type's tare batch-recomputes every unit load of that type, preserving an operator override`() {
        val (ultId, ultName) = createNamedUnitLoadType(BigDecimal("5.000"))
        val productId = createProduct(BigDecimal("2.000"))
        val ulA = createUnitLoad(ultId)
        val ulB = createUnitLoad(ultId)
        createStock(ulA, productId, amount = 1.0)
        createStock(ulB, productId, amount = 1.0)
        assertThat(weightsOf(ulA).weightCalculated).isEqualByComparingTo(BigDecimal("7.000")) // 5 tare + 2 content
        assertThat(weightsOf(ulB).weightCalculated).isEqualByComparingTo(BigDecimal("7.000"))

        setWeightMeasure(ulB, BigDecimal("500.000")) // operator override on ulB only

        updateUnitLoadTypeWeight(ultId, ultName, BigDecimal("20.000")) // tare 5 -> 20

        val a = weightsOf(ulA)
        assertThat(a.weightCalculated)
            .`as`("ulA's calculated weight must reflect the new tare")
            .isEqualByComparingTo(BigDecimal("22.000")) // 20 tare + 2 content
        assertThat(a.weight).isEqualByComparingTo(BigDecimal("22.000"))

        val b = weightsOf(ulB)
        assertThat(b.weightCalculated)
            .`as`("ulB's calculated weight must also reflect the new tare -- this is a batch, not a single-UL recompute")
            .isEqualByComparingTo(BigDecimal("22.000"))
        assertThat(b.weight)
            .`as`("the operator override on ulB must survive a tare-driven batch recompute")
            .isEqualByComparingTo(BigDecimal("500.000"))
    }

    /**
     * CRITICAL fix (defect-burndown-5, task-3 review): [UnitLoadType] is a shared catalog row
     * with no tenant of its own, so [UnitLoadRepository.findByUnitLoadTypeId] -- the query
     * feeding this PUT's batch recompute -- can return unit loads across MULTIPLE clients in one
     * call. Before the fix, [UnitLoadWeightCalculator.recalculateAll] fed the union of every
     * client's item ids into the ambient-`TenantContext`-scoped [ProductLookup.findMeasuresByIds]
     * overload: under ACME(1)'s own PUT, GLOBEX's item would be silently dropped from the
     * result map, and [UnitLoadWeightCalculator]'s "missing measure = unmeasured item,
     * contributes nothing" rule turned that into a real cross-tenant bug -- GLOBEX's unit load
     * would persist a TARE-ONLY [UnitLoadWeights.weightCalculated] (20.000), not its true total
     * including its own item's weight (23.000). ACME (the caller) making the PUT must not be
     * able to corrupt GLOBEX's stored weight.
     */
    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a shared unit load type's tare update by one client correctly recomputes another client's unit loads too`() {
        val (ultId, ultName) = createNamedUnitLoadType(BigDecimal("5.000"))

        // ACME(1)'s own unit load on the shared type, seeded via REST under this test's own
        // ambient TestSecurity/OidcSecurity principal.
        val acmeProductId = createProduct(BigDecimal("2.000"))
        val acmeUlId = createUnitLoad(ultId)
        createStock(acmeUlId, acmeProductId, amount = 1.0)

        // GLOBEX(2)'s unit load + product on the SAME shared type -- direct entity persistence,
        // since REST in this test is bound to the fixed ACME principal.
        val globexUlId = seedForeignTareCascadeFixture(ultId, foreignClientId = GLOBEX, itemWeight = BigDecimal("3.000"))
        assertThat(weightsOfEntity(globexUlId).weightCalculated)
            .`as`("sanity: GLOBEX's unit load has never been recomputed before this test's PUT")
            .isNull()

        // ACME's OWNER principal makes the PUT -- the tare change on the SHARED type.
        updateUnitLoadTypeWeight(ultId, ultName, BigDecimal("20.000")) // tare 5 -> 20

        val acme = weightsOf(acmeUlId)
        assertThat(acme.weightCalculated).isEqualByComparingTo(BigDecimal("22.000")) // 20 tare + 2 content

        val globex = weightsOfEntity(globexUlId)
        assertThat(globex.weightCalculated)
            .`as`("GLOBEX's own item weight (3.000) must be included -- not silently dropped by " +
                "ACME's ambient tenant scope, and not a tare-only 20.000")
            .isEqualByComparingTo(BigDecimal("23.000")) // 20 tare + 3 content
        assertThat(globex.weight).isEqualByComparingTo(BigDecimal("23.000"))
    }

    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load's weight is its type tare plus the weight of the stock on it`() {
        val ultId = createUnitLoadType(BigDecimal("10.000"))
        val productId = createProduct(BigDecimal("2.500"))
        val ulId = createUnitLoad(ultId)

        createStock(ulId, productId, amount = 3.0)

        val weights = weightsOf(ulId)
        assertThat(weights.weightCalculated).isEqualByComparingTo(BigDecimal("17.500"))
        assertThat(weights.weight).isEqualByComparingTo(BigDecimal("17.500"))
    }

    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `adding stock to a unit load raises its calculated weight`() {
        val ultId = createUnitLoadType(BigDecimal("5.000"))
        val ulId = createUnitLoad(ultId)
        val productA = createProduct(BigDecimal("2.000"))
        val productB = createProduct(BigDecimal("3.000"))

        createStock(ulId, productA, amount = 1.0)
        val afterFirst = weightsOf(ulId).weightCalculated!!

        createStock(ulId, productB, amount = 1.0)
        val afterSecond = weightsOf(ulId).weightCalculated!!

        assertThat(afterSecond).isGreaterThan(afterFirst)
        assertThat(afterSecond).isEqualByComparingTo(BigDecimal("10.000"))
    }

    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removing the last stock leaves the unit load at its bare tare weight`() {
        val ultId = createUnitLoadType(BigDecimal("8.000"))
        val productId = createProduct(BigDecimal("4.000"))
        val ulId = createUnitLoad(ultId)
        val stockUnitId = createStock(ulId, productId, amount = 2.0)

        assertThat(weightsOf(ulId).weightCalculated).isEqualByComparingTo(BigDecimal("16.000"))

        deleteStock(stockUnitId)

        assertThat(weightsOf(ulId).weightCalculated).isEqualByComparingTo(BigDecimal("8.000"))
    }

    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a manual weight measure overrides the calculation and survives a recalculation`() {
        val ultId = createUnitLoadType(BigDecimal("6.000"))
        val productId = createProduct(BigDecimal("2.000"))
        val ulId = createUnitLoad(ultId)
        createStock(ulId, productId, amount = 1.0)
        assertThat(weightsOf(ulId).weightCalculated).isEqualByComparingTo(BigDecimal("8.000"))

        setWeightMeasure(ulId, BigDecimal("100.000"))
        val overridden = weightsOf(ulId)
        assertThat(overridden.weightMeasure).isEqualByComparingTo(BigDecimal("100.000"))
        assertThat(overridden.weight).isEqualByComparingTo(BigDecimal("100.000"))

        // Trigger a fresh full recalculation via another mutation point.
        createStock(ulId, productId, amount = 1.0)

        val afterRecalc = weightsOf(ulId)
        assertThat(afterRecalc.weightCalculated).isEqualByComparingTo(BigDecimal("10.000"))
        assertThat(afterRecalc.weight)
            .`as`("the manual override must survive a recalculation triggered by an unrelated mutation")
            .isEqualByComparingTo(BigDecimal("100.000"))
    }

    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an item with no weight measure contributes nothing and does not null the total`() {
        val ultId = createUnitLoadType(BigDecimal("12.000"))
        val unweighedProductId = createProduct(weight = null)
        val ulId = createUnitLoad(ultId)

        createStock(ulId, unweighedProductId, amount = 5.0)

        val weights = weightsOf(ulId)
        assertThat(weights.weightCalculated)
            .`as`("an unmeasured item must not null the whole total")
            .isEqualByComparingTo(BigDecimal("12.000"))
        assertThat(weights.weight).isEqualByComparingTo(BigDecimal("12.000"))
    }

    /** Regression pin for the live bug: goes through the SPI, not the entity. */
    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `gross weight by location reports a real number rather than zero`() {
        val locationId = System.nanoTime() % 1_000_000_000L
        val ultId = createUnitLoadType(BigDecimal("9.000"))
        val productId = createProduct(BigDecimal("3.000"))
        val ulId = createUnitLoad(ultId, locationId)
        createStock(ulId, productId, amount = 2.0, state = 300)

        val result = grossWeightLookup.grossWeightByLocationIds(setOf(locationId))

        assertThat(result[locationId])
            .`as`("gross weight must be a real number, not the zero the pre-fix code always produced")
            .isNotNull()
            .isNotEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result[locationId]).isEqualByComparingTo(BigDecimal("15.000"))
    }

    /**
     * Fix round 1 pin: [StockService.changeState]'s bulk callers (here,
     * [DefaultStockPicker.packContainer]) opt out of the per-item recompute and do one
     * [UnitLoadWeightCalculator.recalculate] after their loop instead. Two DISTINCT SKUs on the
     * load make sure a bug that dropped or double-counted either item's contribution -- the
     * shape of mistake a broken "recompute once" refactor could introduce -- would show up in
     * the total. This asserts the resulting number is correct; it does not assert the recompute
     * ran exactly once rather than N times, since doing that here would need swapping in a spy
     * bean for [com.karyo.product.spi.ProductLookup] or [StockUnitRepository] via a CDI
     * alternative, which this integration-style suite (real beans throughout, no mocking
     * framework in play) has no existing pattern for -- adding one for a single assertion would
     * be the kind of contortion the brief asked not to write.
     */
    @Test
    @TestSecurity(user = "wgt", roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packContainer with two distinct SKUs recomputes the whole load's weight once, correctly`() {
        val ultId = createUnitLoadType(BigDecimal("7.000"))
        val productA = createProduct(BigDecimal("1.000"))
        val productB = createProduct(BigDecimal("2.000"))
        val ulId = createUnitLoad(ultId)
        val stockA = createStock(ulId, productA, amount = 3.0)
        val stockB = createStock(ulId, productB, amount = 2.0)
        moveToState(stockA, 600) // ON_STOCK -> PICKED
        moveToState(stockB, 600)

        // TenantContext is @RequestScoped (populated by TenantFilter only during REST requests);
        // for the direct SPI call, set clientId to match the REST seeding above (matches the
        // DefaultStockPicker*Test sibling suites' pattern).
        tenantContext.clientId = ACME

        val flipped = stockPicker.packContainer(ulId)

        assertThat(flipped).isEqualTo(2)
        val weights = weightsOf(ulId)
        // tare 7.000 + (A: 1.000 x 3) + (B: 2.000 x 2) = 7.000 + 3.000 + 4.000 = 14.000
        assertThat(weights.weightCalculated).isEqualByComparingTo(BigDecimal("14.000"))
        assertThat(weights.weight).isEqualByComparingTo(BigDecimal("14.000"))
    }
}
