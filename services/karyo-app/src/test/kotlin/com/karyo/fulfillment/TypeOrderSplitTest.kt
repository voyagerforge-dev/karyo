package com.karyo.fulfillment

import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickingType
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Row 8 (`createTypeOrders`): reuses the SAME full/COMPLETE-vs-PICK derivation
 * `PickOrderService.releaseToPicking` already computes when stamping each Pick's `pickingType`.
 * Public behavioral contract: `docs/functional/picking.md#23-pick-order-generation`. Split done
 * in `PickOrderService`, NOT a new `PickOrderGroupingStrategy` bean -- see the class KDoc on
 * `releaseToPicking` for why. `sendToPacking`/`sendToShipping`/`createShippingOrder`/the
 * destination resolution live in [com.karyo.orders.service.OrderStrategyFlagsTest].
 */
@QuarkusTest
class TypeOrderSplitTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var packingService: PackingService
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var entityManager: EntityManager

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun createStrategy(name: String, createTypeOrders: Boolean): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","createTypeOrders":$createTypeOrders}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /**
     * Two lines: one whose reservation consumes its source stock unit's ENTIRE amount (the
     * existing derivation stamps COMPLETE), one that consumes only PART of a different source
     * (stamps PICK) -- the exact mixed shape the brief specifies for building a mixed release.
     */
    private fun seedMixedReleaseOrder(strategyId: Long? = null): Long {
        val suffix = System.nanoTime()
        val iu = createItemUnit("TOS-IU-${suffix.toString().takeLast(8)}")

        val numFull = "TOS-FULL-$suffix"
        val pidFull = createProduct(numFull, iu)
        val ulFull = createUnitLoad("TOS-ULF-$suffix")
        createStock(ulFull, pidFull, numFull, 100.0) // whole-unit draw -> COMPLETE

        val numPart = "TOS-PART-$suffix"
        val pidPart = createProduct(numPart, iu)
        val ulPart = createUnitLoad("TOS-ULP-$suffix")
        createStock(ulPart, pidPart, numPart, 100.0) // partial draw -> PICK

        val strategyField = strategyId?.let { ""","orderStrategyId":$it""" } ?: ""
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","lines":[""" +
                    """{"itemDataId":$pidFull,"amount":100.0},""" +
                    """{"itemDataId":$pidPart,"amount":40.0}]$strategyField}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /** Same shape as [seedMixedReleaseOrder], but also returns the two source stock unit ids so a
     *  test can check their `reservedAmount` before/after an operation. */
    private data class MixedRelease(val orderId: Long, val fullStockUnitId: Long, val partStockUnitId: Long)

    private fun seedMixedReleaseOrderWithStockIds(strategyId: Long): MixedRelease {
        val suffix = System.nanoTime()
        val iu = createItemUnit("TOS2-IU-${suffix.toString().takeLast(8)}")

        val numFull = "TOS2-FULL-$suffix"
        val pidFull = createProduct(numFull, iu)
        val ulFull = createUnitLoad("TOS2-ULF-$suffix")
        val fullStockUnitId = createStock(ulFull, pidFull, numFull, 100.0) // whole-unit draw -> COMPLETE

        val numPart = "TOS2-PART-$suffix"
        val pidPart = createProduct(numPart, iu)
        val ulPart = createUnitLoad("TOS2-ULP-$suffix")
        val partStockUnitId = createStock(ulPart, pidPart, numPart, 100.0) // partial draw -> PICK

        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","orderStrategyId":$strategyId,"lines":[""" +
                    """{"itemDataId":$pidFull,"amount":100.0},""" +
                    """{"itemDataId":$pidPart,"amount":40.0}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return MixedRelease(orderId, fullStockUnitId, partStockUnitId)
    }

    private fun orderState(orderId: Long): Int =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

    private fun reservedAmountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("reservedAmount")

    private fun confirmAllPicks(pickOrderId: Long) {
        pickOrderService.picksOf(pickOrderId).forEach {
            pickOrderService.confirmPick(it.id!!, it.plannedAmount, null)
        }
    }

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `createTypeOrders splits a mixed release into one PickOrder per picking type`() {
        seedPackStaging()
        val strategyId = createStrategy("TOS-SPLIT-${System.nanoTime()}", createTypeOrders = true)
        val orderId = seedMixedReleaseOrder(strategyId)
        tenantContext.clientId = 1L

        val pickOrders = pickOrderService.releaseToPicking(orderId)
        assertThat(pickOrders).hasSize(2)

        val typesPerOrder = pickOrders.map { po ->
            pickOrderService.picksOf(po.id!!).map { it.pickingType }.toSet()
        }
        assertThat(typesPerOrder).containsExactlyInAnyOrder(
            setOf(PickingType.COMPLETE.name), setOf(PickingType.PICK.name),
        )
    }

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `with createTypeOrders off a mixed release stays a single PickOrder`() {
        seedPackStaging()
        val orderId = seedMixedReleaseOrder() // DEFAULT strategy -- createTypeOrders off
        tenantContext.clientId = 1L

        val pickOrders = pickOrderService.releaseToPicking(orderId)
        assertThat(pickOrders).hasSize(1)

        val types = pickOrderService.picksOf(pickOrders.single().id!!).map { it.pickingType }.toSet()
        assertThat(types).containsExactlyInAnyOrder(PickingType.COMPLETE.name, PickingType.PICK.name)
    }

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a release whose picks are all one type produces one PickOrder either way`() {
        seedPackStaging()
        val suffix = System.nanoTime()
        // createTypeOrders ON, but every planned pick derives to the SAME type (a single line,
        // whole-source draw -> COMPLETE only) -- groupBy collapses to one bucket either way.
        val strategyId = createStrategy("TOS-ONE-$suffix", createTypeOrders = true)
        val iu = createItemUnit("TOS1-IU-${suffix.toString().takeLast(8)}")
        val num = "TOS1-SKU-$suffix"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("TOS1-UL-$suffix")
        createStock(ul, pid, num, 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","orderStrategyId":$strategyId,""" +
                    """"lines":[{"itemDataId":$pid,"amount":100.0}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        tenantContext.clientId = 1L

        val pickOrders = pickOrderService.releaseToPicking(orderId)
        assertThat(pickOrders).hasSize(1)
        val types = pickOrderService.picksOf(pickOrders.single().id!!).map { it.pickingType }.toSet()
        assertThat(types).containsExactly(PickingType.COMPLETE.name)
    }

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the release endpoint returns a JSON array in every case`() {
        seedPackStaging()
        val orderId = seedMixedReleaseOrder() // DEFAULT strategy -- a single-PickOrder case
        val body = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders")
            .then().statusCode(201)
            .extract().jsonPath()
        val list = body.getList<Map<String, Any>>("")
        assertThat(list).hasSize(1)
    }

    // ── Task 8 review fix (register row 8, Critical): the three flows never adapted to
    // createTypeOrders splitting one release into more than one PickOrder -- pick completion,
    // packing, and delivery-order cancel. Each of these three tests fails against the pre-fix
    // code (verified by stashing the fix and re-running them). ──

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `confirming every pick in both pick orders of a split release advances the order to PICKED exactly once`() {
        seedPackStaging()
        val strategyId = createStrategy("TOS-CONFIRM-${System.nanoTime()}", createTypeOrders = true)
        val orderId = seedMixedReleaseOrder(strategyId)
        tenantContext.clientId = 1L

        val pickOrders = pickOrderService.releaseToPicking(orderId)
        assertThat(pickOrders).hasSize(2)

        // Pre-fix: confirmPick calls markPicked unconditionally on THIS pick order's own
        // completion. The first sibling to finish moves the order STARTED -> PICKED; the second
        // sibling's last confirmPick then calls markPicked a SECOND time, canAdvanceTo refuses the
        // same-state transition, InvalidTransition propagates uncaught out of confirmPick, and the
        // whole confirmation (including the stock move that already happened) rolls back. That
        // failure would surface here as an uncaught exception from confirmAllPicks, not as an
        // assertion failure -- the test fails outright rather than reporting a wrong value.
        val states = mutableListOf<Int>()
        pickOrders.forEach { po ->
            confirmAllPicks(po.id!!)
            states += orderState(orderId)
        }

        // The order sits at STARTED after the FIRST sibling finishes (not yet advanced -- the
        // second sibling isn't done), and reaches PICKED only after the SECOND sibling finishes.
        // A bare "isEqualTo(600)" on the final value would not distinguish "advanced exactly once,
        // at the right moment" from "advanced early and something silently compensated."
        assertThat(states).containsExactly(500, 600)
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))
    }

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packing a split release moves both containers' stock into shipping units, neither stranded`() {
        seedPackStaging()
        val strategyId = createStrategy("TOS-PACK-${System.nanoTime()}", createTypeOrders = true)
        val orderId = seedMixedReleaseOrder(strategyId)
        tenantContext.clientId = 1L

        val pickOrders = pickOrderService.releaseToPicking(orderId)
        assertThat(pickOrders).hasSize(2)
        pickOrders.forEach { confirmAllPicks(it.id!!) }
        entityManager.clear()

        // Pre-fix: openPacking/pack both read pickOrderRepository.findByDeliveryOrderId, an
        // arbitrary firstResult() over the two siblings. The container belonging to whichever
        // sibling was NOT picked has no path to a ShippingUnit -- its picked stock is stranded in
        // its pick bin.
        val shipment = packingService.openPacking(orderId)
        val packed = packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()

        assertThat(packed.state).isEqualTo(ShipmentState.PACKED.code)

        val units = packingService.unitsOf(shipment.id!!)
        val packedUnitLoadIds = units.map { it.unitLoadId }.toSet()
        val containerIds = pickOrders.map { it.targetUnitLoadId }.toSet()
        // Two containers -> two ShippingUnits (ONE_TO_ONE, one per pick container). Asserting
        // equality against the ACTUAL container ids (not just a size) is what catches "packed one
        // container, stranded the other, but happened to also produce 2 units some other way."
        assertThat(packedUnitLoadIds).isEqualTo(containerIds)

        // Final-review wave (CRITICAL 1): the entered weight must be PRORATED across sibling
        // containers, not repeated whole -- summing every ShippingUnit's weight must reproduce
        // the entered 2.5, and no single unit may carry the full amount. This assertion FAILS
        // against the pre-fix code (every sibling got the full 2.5, so the sum was 5.0 and each
        // unit's weight was NOT less than 2.5).
        val totalWeight = units.fold(BigDecimal.ZERO) { acc, u -> acc + u.weight }
        assertThat(totalWeight).isEqualByComparingTo(BigDecimal("2.5"))
        units.forEach { assertThat(it.weight).isLessThan(BigDecimal("2.5")) }
    }

    @Test
    @TestSecurity(user = "tos", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling a split release releases both siblings' reservations`() {
        seedPackStaging()
        val strategyId = createStrategy("TOS-CANCEL-${System.nanoTime()}", createTypeOrders = true)
        val seeded = seedMixedReleaseOrderWithStockIds(strategyId)
        tenantContext.clientId = 1L

        val pickOrders = pickOrderService.releaseToPicking(seeded.orderId)
        assertThat(pickOrders).hasSize(2)

        // Both source stock units are reserved by the release, before any cancellation.
        assertThat(reservedAmountOf(seeded.fullStockUnitId)).isEqualTo(100.0)
        assertThat(reservedAmountOf(seeded.partStockUnitId)).isEqualTo(40.0)

        // Pre-fix: DefaultPickCancelPort.cancelOpenWorkForDeliveryOrder reads
        // pickOrderRepository.findByDeliveryOrderId, an arbitrary firstResult() over the two
        // siblings -- the sibling it does not find keeps its live reservation and its PickOrder
        // stays open, contradicting the cancel-restock guarantee.
        given().`when`().post("/api/v1/delivery-orders/${seeded.orderId}/cancel").then().statusCode(200)
            .body("state", `is`(800))

        // Both siblings were force-finished (CANCELED), not just one.
        pickOrders.forEach { po ->
            given().`when`().get("/api/v1/pick-orders/${po.id}").then().statusCode(200).body("state", `is`(800))
        }

        // Both source stock units' reservations were released, not just one.
        assertThat(reservedAmountOf(seeded.fullStockUnitId)).isEqualTo(0.0)
        assertThat(reservedAmountOf(seeded.partStockUnitId)).isEqualTo(0.0)
    }
}
