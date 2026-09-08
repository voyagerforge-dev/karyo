package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.AdHocShippingUnitService
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.ShippingLifecycleService
import com.karyo.fulfillment.service.ShippingService
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * S5 (outbound-completion sprint, register row S5): ad-hoc shipping units attached to a shipment
 * from an ON_STOCK unit load with no pick order behind it. Mirrors
 * [ShippingLifecycleServiceTest]'s conventions -- direct service calls under `@TestSecurity`,
 * REST only for the seeding scaffolding that isn't the thing under test.
 */
@QuarkusTest
class AdHocShippingUnitServiceTest {

    @Inject lateinit var packingService: PackingService

    @Inject lateinit var lifecycleService: ShippingLifecycleService

    @Inject lateinit var shippingService: ShippingService

    @Inject lateinit var adHocService: AdHocShippingUnitService

    @Inject lateinit var tenantContext: TenantContext

    @Inject lateinit var entityManager: EntityManager

    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository

    @Inject lateinit var unitLoadRepository: UnitLoadRepository

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

    private fun createStockWithState(ulId: Long, itemDataId: Long, number: String, amount: Double, state: Int): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,
                    |"state":$state}""".trimMargin(),
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        createStockWithState(ulId, itemDataId, number, amount, StockState.ON_STOCK.code)

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

    private fun seedShipStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"SHIP-STG-${System.nanoTime()}","usages":["SHIP_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"SStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"SHIP-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun seedAndReleaseOrderFor60(): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "ORD-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":60.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    private fun pickToPicked(orderId: Long) {
        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        po.getList<Map<String, Any>>("[0].picks").forEach { pick ->
            val amt = (pick["plannedAmount"] as Number).toDouble()
            given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
        }
    }

    /** Opens (but does not pack) a shipment: PACKING state, zero shipping units, tenant primed to 1. */
    private fun packingShipmentId(): Long {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        entityManager.clear()
        return shipment.id!!
    }

    /** Opens + packs a shipment (state PACKED, one PACKOUT unit), tenant primed to 1. */
    private fun packedShipmentId(): Long {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()
        return shipment.id!!
    }

    /** Drives a shipment all the way to SHIPPED (680), for the past-PACKED guard test. */
    private fun shippedShipmentId(): Long {
        seedShipStaging()
        val shipmentId = packedShipmentId()
        shippingService.manifest(shipmentId, "UPS", "GROUND", null)
        entityManager.clear()
        shippingService.dispatch(shipmentId)
        entityManager.clear()
        return shipmentId
    }

    /** A fresh ON_STOCK unit load with one stock unit, unrelated to any order/pick. */
    private fun onStockUnitLoad(amount: Double = 15.0): Triple<Long, Long, String> {
        val s = System.nanoTime()
        val iu = createItemUnit("AU-${s.toString().takeLast(10)}"); val num = "AH-$s"
        val pid = createProduct(num, iu)
        val ulId = createUnitLoad("AH-UL-$s")
        createStock(ulId, pid, num, amount)
        return Triple(ulId, pid, num)
    }

    @Transactional
    fun reassignUnitLoadOwner(unitLoadId: Long, newOwner: Long) {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        ul.clientId = newOwner
    }

    @Transactional
    fun markUnitLoadDeletable(unitLoadId: Long) {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        ul.state = StockState.DELETABLE.code
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `attaching an ON_STOCK unit load to a PACKING shipment creates an AD_HOC unit whose lines mirror the stock`() {
        val shipmentId = packingShipmentId()
        val (ulId, pid, num) = onStockUnitLoad(15.0)

        val updated = adHocService.addAdHocUnit(shipmentId, ulId)
        entityManager.clear()

        assertThat(updated.id).isEqualTo(shipmentId)
        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        assertThat(units).hasSize(1)
        val unit = units.single()
        assertThat(unit.origin).isEqualTo(ShippingUnit.ORIGIN_AD_HOC)
        assertThat(unit.positionIndex).isEqualTo(1)
        assertThat(unit.unitLoadId).isEqualTo(ulId)

        val lines = shippingUnitRepository.findLinesByUnitId(unit.id!!)
        assertThat(lines).hasSize(1)
        val line = lines.single()
        assertThat(line.sourcePickId).isNull()
        assertThat(line.itemDataId).isEqualTo(pid)
        assertThat(line.itemDataNumber).isEqualTo(num)
        assertThat(line.amount).isEqualByComparingTo(BigDecimal("15.0"))

        val stockUnits = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        assertThat(stockUnits).isNotEmpty
        stockUnits.forEach { su -> assertThat(su["state"] as Int).isEqualTo(StockState.PACKED.code) }
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an ad-hoc unit on a shipment that already has a PACKOUT unit continues the positionIndex sequence`() {
        val shipmentId = packedShipmentId()
        val (ulId, _, _) = onStockUnitLoad(8.0)

        adHocService.addAdHocUnit(shipmentId, ulId)
        entityManager.clear()

        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        assertThat(units).hasSize(2)
        val adHocUnit = units.single { it.origin == ShippingUnit.ORIGIN_AD_HOC }
        assertThat(adHocUnit.positionIndex).isEqualTo(2)
        assertThat(adHocUnit.shippingUnitNumber).endsWith("-SU2")
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load owned by another tenant is a 404`() {
        val shipmentId = packingShipmentId()
        val (ulId, _, _) = onStockUnitLoad()
        reassignUnitLoadOwner(ulId, 2L)

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId, ulId) }
            .isInstanceOf(FulfillmentException.NotFound::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load with non-ON_STOCK stock is a 409`() {
        val shipmentId = packingShipmentId()
        val s = System.nanoTime()
        val iu = createItemUnit("AU-${s.toString().takeLast(10)}"); val num = "AH-$s"
        val pid = createProduct(num, iu)
        val ulId = createUnitLoad("AH-UL-$s")
        createStockWithState(ulId, pid, num, 15.0, StockState.PICKED.code)

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId, ulId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a DELETABLE unit load is a 409 even when its stock is still ON_STOCK`() {
        val shipmentId = packingShipmentId()
        val (ulId, _, _) = onStockUnitLoad()
        markUnitLoadDeletable(ulId)

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId, ulId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load already on a live shipment is a 409 for a second shipment`() {
        val shipmentId1 = packingShipmentId()
        val shipmentId2 = packingShipmentId()
        val (ulId, _, _) = onStockUnitLoad()

        adHocService.addAdHocUnit(shipmentId1, ulId)
        entityManager.clear()

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId2, ulId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a shipment past PACKED refuses an ad-hoc unit`() {
        val shipmentId = shippedShipmentId()
        val (ulId, _, _) = onStockUnitLoad()

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId, ulId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    /**
     * Post-review fix: "paused-is-parked" doctrine applies here too, matching pack/manifest/
     * dispatch's shared `requireShipmentNotPaused` guard -- attaching an ad-hoc unit mutates the
     * shipment's units, so a paused shipment must refuse it (409) the same way.
     */
    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses an ad-hoc unit`() {
        val shipmentId = packingShipmentId()
        val (ulId, _, _) = onStockUnitLoad()
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId, ulId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removeUnit on an ad-hoc unit restores its stock to ON_STOCK -- the round trip`() {
        val shipmentId = packingShipmentId()
        val (ulId, _, _) = onStockUnitLoad(15.0)

        adHocService.addAdHocUnit(shipmentId, ulId)
        entityManager.clear()

        val stockAfterAttach = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        stockAfterAttach.forEach { su -> assertThat(su["state"] as Int).isEqualTo(StockState.PACKED.code) }

        val unit = shippingUnitRepository.findByShipmentId(shipmentId).single { it.origin == ShippingUnit.ORIGIN_AD_HOC }
        val updated = lifecycleService.removeUnit(shipmentId, unit.id!!)
        entityManager.clear()

        assertThat(updated.state).isEqualTo(ShipmentState.PACKING.code)
        assertThat(shippingUnitRepository.findByShipmentId(shipmentId)).isEmpty()

        val stockAfterRemove = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        assertThat(stockAfterRemove).isNotEmpty
        stockAfterRemove.forEach { su -> assertThat(su["state"] as Int).isEqualTo(StockState.ON_STOCK.code) }
    }

    /**
     * CRITICAL 1 regression (final-review fix wave, outbound-completion sprint). Before the fix,
     * [com.karyo.fulfillment.service.PackingService.persistUnits] derived its numbering base from
     * `findByShipmentId().size` -- a per-shipment COUNT. [ShippingLifecycleService.removeUnit]
     * hard-deletes, so removing the pack unit (positionIndex 1) while the ad-hoc unit
     * (positionIndex 2) survives drops the count to 1; a second ad-hoc attach then recomputed
     * `1 + 1 = 2`, colliding with the STILL-LIVE unit already numbered `-SU2` --
     * `UNIQUE(client_id, shipping_unit_number)` -> 500. The fix bases numbering on the MAX
     * `positionIndex` ever issued (2 here), so the third unit lands on 3, never colliding.
     */
    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pack, ad-hoc attach, removeUnit the first, then a fresh ad-hoc attach succeeds with a number that was never freed`() {
        val shipmentId = packedShipmentId() // PACKOUT unit, positionIndex 1
        val (firstAdHocUl, _, _) = onStockUnitLoad(5.0)
        adHocService.addAdHocUnit(shipmentId, firstAdHocUl) // AD_HOC unit, positionIndex 2
        entityManager.clear()

        val packoutUnit = shippingUnitRepository.findByShipmentId(shipmentId)
            .single { it.origin == ShippingUnit.ORIGIN_PACKOUT }
        lifecycleService.removeUnit(shipmentId, packoutUnit.id!!) // count drops to 1, max positionIndex stays 2
        entityManager.clear()

        val (secondAdHocUl, _, _) = onStockUnitLoad(5.0)
        val updated = adHocService.addAdHocUnit(shipmentId, secondAdHocUl)
        entityManager.clear()

        assertThat(updated.id).isEqualTo(shipmentId)
        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        assertThat(units).hasSize(2)
        val newUnit = units.single { it.unitLoadId == secondAdHocUl }
        assertThat(newUnit.positionIndex).isEqualTo(3)
        assertThat(newUnit.shippingUnitNumber).endsWith("-SU3")
    }

    /**
     * IMPORTANT 4 (final-review fix wave, outbound-completion sprint): ON_STOCK alone isn't
     * enough for [AdHocShippingUnitService.requireAllOnStock] -- a stock unit can be ON_STOCK
     * and still carry a live reservation (`StockService.reserveStock` never touches `state`).
     * A real order-line reservation is the natural production path there is no direct REST
     * "reserve" endpoint: a delivery order for LESS than the full stock amount, released, claims
     * a partial `reservedAmount` on the same stock unit ([DefaultStockReserver] ->
     * [StockService.reserveStock] via [StockSelectionService]) while it stays ON_STOCK(300).
     */
    @Test
    @TestSecurity(user = "ff", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a unit load with a live reservation on its ON_STOCK stock is a 409`() {
        val shipmentId = packingShipmentId()
        val s = System.nanoTime()
        val iu = createItemUnit("RSV-${s.toString().takeLast(10)}"); val num = "RSV-$s"
        val pid = createProduct(num, iu)
        val ulId = createUnitLoad("RSV-UL-$s")
        createStock(ulId, pid, num, 15.0)
        val reservingOrderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"Reserver","lines":[{"itemDataId":$pid,"amount":5.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$reservingOrderId/release").then().statusCode(200)

        val stockAfterReserve = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        assertThat(stockAfterReserve.single()["state"] as Int).isEqualTo(StockState.ON_STOCK.code)
        assertThat((stockAfterReserve.single()["reservedAmount"] as Number).toDouble()).isGreaterThan(0.0)

        assertThatThrownBy { adHocService.addAdHocUnit(shipmentId, ulId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }
}
