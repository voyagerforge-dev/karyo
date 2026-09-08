package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickWeightVolumeCalculator
import com.karyo.fulfillment.vo.PickState
import com.karyo.product.spi.ProductMeasures
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

/**
 * WORKLIST row 19: order-level weight/volume, computed on read — never a stored column (sprint
 * adjudication 5). Two layers:
 *
 * 1. Pure arithmetic against [PickWeightVolumeCalculator.compute] directly (no persistence) —
 *    hand-built [Pick]/[ProductMeasures] fixtures, hand-computed expected sums, pinned precisely.
 * 2. An end-to-end REST layer (real persisted `PickOrder`/`Pick` rows, real `ProductLookup`)
 *    proving [com.karyo.fulfillment.api.v1.PickOrderResource]'s single-get and list paths agree.
 *    Batching itself (ONE `findMeasuresByIds` call per response batch) is verified by code
 *    reading of `PickOrderResource.list`/`toResponse`, not by a query-count assertion here —
 *    per the sprint plan, query-count assertions are brittle; values-only equivalence is the
 *    honest signal that both paths compute from the same batched map.
 */
@QuarkusTest
class PickWeightVolumeTest {

    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var pickRepository: PickRepository

    // ---- Layer 1: pure calculator arithmetic (no DB) ----

    private fun pick(state: PickState, itemDataId: Long, planned: BigDecimal, picked: BigDecimal = BigDecimal.ZERO) =
        Pick().apply {
            this.state = state.code
            this.itemDataId = itemDataId
            this.itemDataNumber = "SKU-$itemDataId"
            this.plannedAmount = planned
            this.pickedAmount = picked
            this.sourceStockUnitId = 1L
            this.pickOrderId = 1L
            this.deliveryOrderLineId = 1L
        }

    @Test
    fun `mixed order sums picked-amount for PICKED, planned-amount for unpicked, excludes CANCELED`() {
        val picks = listOf(
            // PICKED short pick: pickedAmount (6) != plannedAmount (10) -- the ACTUAL amount is used.
            pick(PickState.PICKED, itemDataId = 1L, planned = BigDecimal("10"), picked = BigDecimal("6")),
            // RELEASED (unpicked): uses plannedAmount. Item 2 has a weight but no volume measure.
            pick(PickState.RELEASED, itemDataId = 2L, planned = BigDecimal("4")),
            // CANCELED: excluded entirely, even though its planned amount alone dwarfs everything else.
            pick(PickState.CANCELED, itemDataId = 1L, planned = BigDecimal("100")),
        )
        val measures = mapOf(
            1L to ProductMeasures(weight = BigDecimal("2.0"), volume = BigDecimal("3.0")),
            2L to ProductMeasures(weight = BigDecimal("5.0"), volume = null),
        )

        val result = PickWeightVolumeCalculator.compute(picks, measures)

        // weight = 6*2.0 (picked item 1) + 4*5.0 (unpicked item 2) + 0 (canceled, excluded) = 32.0
        assertThat(result.weight).isNotNull.isEqualByComparingTo(BigDecimal("32.0"))
        // volume = 6*3.0 (picked item 1) + 0 (item 2 has no volume measure) + 0 (canceled) = 18.0
        assertThat(result.volume).isNotNull.isEqualByComparingTo(BigDecimal("18.0"))
    }

    @Test
    fun `all products lack both measures -- weight and volume are null, never a fabricated zero`() {
        val picks = listOf(
            pick(PickState.PICKED, itemDataId = 9L, planned = BigDecimal("10"), picked = BigDecimal("10")),
            pick(PickState.RELEASED, itemDataId = 9L, planned = BigDecimal("5")),
        )

        val result = PickWeightVolumeCalculator.compute(picks, measuresByItemId = emptyMap())

        assertThat(result.weight).isNull()
        assertThat(result.volume).isNull()
    }

    @Test
    fun `zero-sum -- every pick CANCELED -- is null even though the products DO carry measures`() {
        val picks = listOf(
            pick(PickState.CANCELED, itemDataId = 1L, planned = BigDecimal("10")),
            pick(PickState.CANCELED, itemDataId = 1L, planned = BigDecimal("5")),
        )
        val measures = mapOf(1L to ProductMeasures(weight = BigDecimal("2.0"), volume = BigDecimal("3.0")))

        val result = PickWeightVolumeCalculator.compute(picks, measures)

        assertThat(result.weight).isNull()
        assertThat(result.volume).isNull()
    }

    @Test
    fun `no picks at all is null`() {
        val result = PickWeightVolumeCalculator.compute(emptyList(), emptyMap())
        assertThat(result.weight).isNull()
        assertThat(result.volume).isNull()
    }

    // ---- Layer 2: end-to-end REST -- single-get vs list-path batching ----

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(
        number: String,
        itemUnitId: Long,
        weight: String? = null,
        height: String? = null,
        width: String? = null,
        depth: String? = null,
    ): Long {
        val measureFields = buildString {
            weight?.let { append(""","weight":$it""") }
            height?.let { append(""","height":$it""") }
            width?.let { append(""","width":$it""") }
            depth?.let { append(""","depth":$it""") }
        }
        return given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId$measureFields}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /**
     * Persists a PickOrder with 3 Picks directly -- bypassing `releaseToPicking`'s staging/
     * reservation machinery, which is irrelevant to weight/volume math -- so the exact pick
     * states/amounts are pinned precisely. Mirrors [PickRollupLookupTest]'s direct-persistence
     * pattern (a real parent PickOrder row, since `picks.pick_order_id` has an FK).
     */
    @Transactional
    fun seedMixedPickOrder(itemA: Long, itemB: Long, clientId: Long): Long {
        val po = PickOrder().apply {
            this.clientId = clientId
            this.pickOrderNumber = "WV-PO-${System.nanoTime()}"
            this.deliveryOrderId = 0
            this.deliveryOrderNumber = "WV-DO-${System.nanoTime()}"
            this.state = PickState.PICKED.code
        }
        pickOrderRepository.persist(po)
        val poId = po.id!!

        pickRepository.persist(
            Pick().apply {
                this.clientId = clientId
                this.pickOrderId = poId
                this.deliveryOrderLineId = 1L
                this.itemDataId = itemA
                this.itemDataNumber = "A"
                this.sourceStockUnitId = 1L
                this.plannedAmount = BigDecimal("10")
                this.pickedAmount = BigDecimal("6")
                this.state = PickState.PICKED.code
            },
        )
        pickRepository.persist(
            Pick().apply {
                this.clientId = clientId
                this.pickOrderId = poId
                this.deliveryOrderLineId = 2L
                this.itemDataId = itemB
                this.itemDataNumber = "B"
                this.sourceStockUnitId = 2L
                this.plannedAmount = BigDecimal("4")
                this.state = PickState.RELEASED.code
            },
        )
        pickRepository.persist(
            Pick().apply {
                this.clientId = clientId
                this.pickOrderId = poId
                this.deliveryOrderLineId = 3L
                this.itemDataId = itemA
                this.itemDataNumber = "A"
                this.sourceStockUnitId = 3L
                this.plannedAmount = BigDecimal("100")
                this.state = PickState.CANCELED.code
            },
        )
        return poId
    }

    @Test
    @TestSecurity(user = "wv", roles = ["product-read", "product-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `single-get and list-path agree on weight and volume for the same mixed order`() {
        val s = System.nanoTime()
        val iu = createItemUnit("WV-IU-${s.toString().takeLast(8)}")
        // item A: weight 2.0, dims 1x1x3 -> volume 3.0
        val itemA = createProduct("WV-A-$s", iu, weight = "2.0", height = "1", width = "1", depth = "3.0")
        // item B: weight 5.0, no dims -> volume null
        val itemB = createProduct("WV-B-$s", iu, weight = "5.0")

        val poId = seedMixedPickOrder(itemA, itemB, clientId = 1L)

        // weight = 6*2.0 (picked A) + 4*5.0 (unpicked B) + 0 (canceled A, excluded) = 32.0
        // volume = 6*3.0 (picked A) + 0 (B has no volume) + 0 (canceled) = 18.0
        val single = given().`when`().get("/api/v1/pick-orders/$poId").then().statusCode(200)
            .extract().jsonPath()
        assertThat(single.getFloat("weight")).isEqualTo(32.0f)
        assertThat(single.getFloat("volume")).isEqualTo(18.0f)

        val list = given().`when`().get("/api/v1/pick-orders").then().statusCode(200)
            .extract().jsonPath()
        assertThat(list.getFloat("find { it.id == $poId }.weight")).isEqualTo(32.0f)
        assertThat(list.getFloat("find { it.id == $poId }.volume")).isEqualTo(18.0f)
    }

    @Test
    @TestSecurity(user = "wv2", roles = ["product-read", "product-write", "fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an order whose products carry no measures at all renders null weight and volume over REST`() {
        val s = System.nanoTime()
        val iu = createItemUnit("WV-NUL-${s.toString().takeLast(8)}")
        val plain = createProduct("WV-NUL-$s", iu) // no weight/height/width/depth

        val poId = seedMixedPickOrder(plain, plain, clientId = 1L)

        given().`when`().get("/api/v1/pick-orders/$poId").then().statusCode(200)
            .body("weight", org.hamcrest.CoreMatchers.nullValue())
            .body("volume", org.hamcrest.CoreMatchers.nullValue())
    }
}
