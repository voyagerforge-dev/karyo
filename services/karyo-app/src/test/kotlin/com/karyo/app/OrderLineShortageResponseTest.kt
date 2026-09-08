package com.karyo.app

import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.orders.service.OrderService
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
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private const val CLIENT_ID = 1L

/**
 * Pins the RAW reservation-shortfall semantics of `DeliveryOrderLineResponse.shortage`: it mirrors
 * the entity's `amount - reservedAmount` and is NEVER netted against picking-side substitution
 * coverage.
 *
 * **Why this exact fixture exists.** The defect-burndown-2 sprint briefly shipped
 * `shortage = line.shortage - substitutedAmount` to close a filed row claiming a short line
 * "later fully covered by substitution" keeps rendering "Short". The 2026-07-31 final gate proved
 * that premise unreachable: substitution is reservation-BACKED — `handleShortfall` releases the
 * parent pick's unpicked reservation and `coverWithFollowUps` reserves fresh stock, all inside the
 * pool the line already reserved — while `reservedAmount` is written only by reserve/cancel. So
 * `pickedAmount + substitutedAmount <= reservedAmount` always, and the reservation shortfall can
 * never be covered by a substitute. The netting therefore only ever fired where it was WRONG.
 *
 * This fixture is deliberately NON-COINCIDENT (the two shortfall magnitudes do not cancel) so it
 * exposes exactly that error. Two DISTINCT mechanisms are combined:
 *
 *  1. **Reservation shortfall** (entity [com.karyo.orders.domain.model.DeliveryOrderLine.shortage]):
 *     ordered 10, only 6 units of the primary exist at release → reserved 6, raw shortage a fixed
 *     4 for the rest of the order's life (picking never touches `reservedAmount`).
 *  2. **Picking-time short-pick**: of the 6 that WERE reserved, only 2 are physically confirmed;
 *     the same-item follow-up finds nothing and falls through to a registered substitute,
 *     confirming 4 there.
 *
 * Delivered = 2 primary + 4 substitute = 6 of 10 ordered ⇒ **4 units genuinely undelivered**, which
 * is exactly the raw shortage. Netting would have reported 0, suppressing the "Short" chip and the
 * copilot exception strip on a line that is materially short. Reservation shortfalls are not
 * resolved by substitution at all: [OrderService.reserveLine] never consults
 * [com.karyo.product.spi.SubstitutionLookup].
 */
@QuarkusTest
class OrderLineShortageResponseTest {

    @Inject lateinit var orderService: OrderService
    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickRepository: PickRepository
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

    private fun registerSubstitution(primary: Long, substitute: Long) {
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$primary,"substituteItemDataId":$substitute,"priority":1}""")
            .`when`().post("/api/v1/item-substitutions").then().statusCode(201)
    }

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

    /** Orders [amount] of [itemDataId] and releases — short order number, VARCHAR(40)-safe. */
    private fun releaseOrderFor(itemDataId: Long, amount: Double): Long {
        val orderNumber = "DO-${System.nanoTime().toString().takeLast(9)}"
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"orderNumber":"$orderNumber","customerName":"C","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `line shortage reports the raw reservation shortfall and is never netted by substitution`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s")
        val pNum = "P-$s"; val sNum = "S-$s"
        val primary = createProduct(pNum, iu)
        val substitute = createProduct(sNum, iu)
        registerSubstitution(primary, substitute)
        // Only 6 units of the primary item exist -- ordering 10 leaves a fixed reservation
        // shortfall of 4 (entity shortage never changes again; picking cannot touch reservedAmount).
        val ulP = createUnitLoad("ULP-$s"); createStock(ulP, primary, pNum, 6.0)
        val ulS = createUnitLoad("ULS-$s"); createStock(ulS, substitute, sNum, 50.0)
        val orderId = releaseOrderFor(primary, 10.0)
        tenantContext.clientId = CLIENT_ID

        // Baseline leg: BEFORE any picking, the response reports the raw reservation shortfall
        // (no substitution coverage exists yet) -- true under both semantics.
        val preLine = orderService.findById(orderId, CLIENT_ID).lines.single()
        assertThat(preLine.shortage).isEqualByComparingTo(BigDecimal(4))
        assertThat(preLine.substitutedAmount).isEqualByComparingTo(BigDecimal.ZERO)

        // Picking: only the RESERVED 6 was ever planned. Confirm just 2 of it (a picking-time
        // short-pick, independent of the reservation shortfall above) -- same-item follow-up
        // finds nothing else (the sole unit is already excluded) and falls through to the
        // registered substitute for the remaining 4.
        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val parentId = pickRepository.findByPickOrderId(pickOrder.id!!).single().id!!
        pickOrderService.confirmPick(parentId, BigDecimal(2), targetUnitLoadId = null)
        entityManager.clear()

        val followUp = pickRepository.findByPickOrderId(pickOrder.id!!).single { it.followUpForPickId == parentId }
        assertThat(followUp.substitutedItemDataId).isEqualTo(substitute)
        assertThat(followUp.plannedAmount).isEqualByComparingTo(BigDecimal(4))
        pickOrderService.confirmPick(followUp.id!!, followUp.plannedAmount, targetUnitLoadId = null)
        entityManager.clear()

        // Delivered 2 primary + 4 substitute = 6 of 10 ordered, so 4 units are genuinely
        // undelivered. The response must keep reporting 4 -- a `shortage - substitutedAmount`
        // netting would report 0 here and silently hide a short line.
        val line = orderService.findById(orderId, CLIENT_ID).lines.single()
        assertThat(line.pickedAmount).isEqualByComparingTo(BigDecimal(2))
        assertThat(line.substitutedAmount).isEqualByComparingTo(BigDecimal(4))
        assertThat(line.shortage).isEqualByComparingTo(BigDecimal(4))
    }
}
