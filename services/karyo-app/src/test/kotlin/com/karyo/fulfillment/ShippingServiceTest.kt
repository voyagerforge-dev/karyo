package com.karyo.fulfillment

import com.karyo.auth.config.SystemPropertyService
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.ShippingService
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.UnitLoadMover
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
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class ShippingServiceTest {

    @Inject lateinit var packingService: PackingService

    @Inject lateinit var shippingService: ShippingService

    @Inject lateinit var tenantContext: TenantContext

    @Inject lateinit var entityManager: EntityManager

    @Inject lateinit var journalRepository: InventoryJournalRepository

    @Inject lateinit var outboxEventRepository: OutboxEventRepository

    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository

    @Inject lateinit var systemPropertyService: SystemPropertyService

    @Inject lateinit var unitLoadMover: UnitLoadMover

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

    /** Same seeding as [seedShipStaging], but returns the dock's own location name so a test can
     * assert against it directly. */
    private fun seedShipStagingNamed(): String {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"SHIP-STG-${System.nanoTime()}","usages":["SHIP_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"SStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        val dockName = "SHIP-STG-LOC-${System.nanoTime()}"
        given().contentType(ContentType.JSON)
            .body("""{"name":"$dockName","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
        return dockName
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

    private fun labelIdOf(unitLoadId: Long): String =
        given().`when`().get("/api/v1/unit-loads/$unitLoadId")
            .then().statusCode(200).extract().jsonPath().getString("labelId")

    /** Releases the order to picking and confirms all picks at full amount via REST (order -> PICKED 600). */
    private fun pickToPicked(orderId: Long) {
        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        po.getList<Map<String, Any>>("[0].picks").forEach { pick ->
            val amt = (pick["plannedAmount"] as Number).toDouble()
            given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
        }
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `manifest then dispatch ships the order and finishes it`() {
        seedPackStaging()
        seedShipStaging()
        val orderId = seedAndReleaseOrderFor60()

        pickToPicked(orderId)

        // Prime tenant for direct service calls, then open + pack
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()

        val manifested = shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        assertThat(manifested.state).isEqualTo(ShipmentState.SHIPPING.code)
        assertThat(manifested.trackingNumber).isNotBlank()

        val dispatched = shippingService.dispatch(shipment.id!!)
        entityManager.clear()
        assertThat(dispatched.state).isEqualTo(ShipmentState.SHIPPED.code)

        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(700))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `manifest on a non-PACKED shipment is rejected`() {
        seedPackStaging()
        seedShipStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)

        // openPacking gives a PACKING(640) shipment; never pack it -> still not PACKED.
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        entityManager.clear()

        assertThatThrownBy { shippingService.manifest(shipment.id!!, "UPS", "GROUND", null) }
            .isInstanceOf(FulfillmentException.NotShippable::class.java)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `dispatch on a non-SHIPPING shipment is rejected`() {
        seedPackStaging()
        seedShipStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)

        // Drive to PACKED(650) but never manifest -> still not SHIPPING.
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()

        assertThatThrownBy { shippingService.dispatch(shipment.id!!) }
            .isInstanceOf(FulfillmentException.NotShippable::class.java)
    }

    /**
     * Pins the dispatch move-then-ship order (task-10, defect-burndown-4 fix round): the
     * container is moved to the SHIP_STAGING dock BEFORE its stock flips to SHIPPED, so
     * StockService.changeState's journal/outbox row for that flip is written against the dock's
     * location, not the container's pre-dispatch origin. Guards a silent flip back to the old
     * (ship-then-move) order, which would leave the SHIP event pointing at the wrong location.
     *
     * Runs under a DEDICATED clientId (9601), not the file's usual 1: `StagingLocationLookup`
     * resolves "the first location for this client whose area usages contain SHIP_STAGING", and
     * every other test in this file also seeds a fresh SHIP_STAGING location under clientId 1 with
     * no cleanup between tests, so asserting against "the dock THIS test created" would be
     * order-dependent (flaky) unless this test's dock is the ONLY one its clientId can see.
     */
    @Test
    @TestSecurity(
        user = "ff-dispatch-order",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9601"), Claim(key = "tenant_code", value = "SHIP-ORDER-TEST")])
    fun `dispatch's move-then-ship order makes the SHIP journal and outbox record the dock location`() {
        seedPackStaging()
        val dockName = seedShipStagingNamed()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)

        tenantContext.clientId = 9601L
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()
        shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        entityManager.clear()

        shippingService.dispatch(shipment.id!!)
        entityManager.clear()

        // The dock's location name is unique to this test (nanoTime-suffixed) AND this test's
        // dedicated clientId is the only one that can see it, so a CHANGED journal row landing
        // on it can only be the SHIP state-change this test drove.
        val journalRows = journalRepository.count(
            "toStorageLocation = ?1 and recordType = ?2",
            dockName, JournalRecordType.CHANGED.code,
        )
        assertThat(journalRows)
            .`as`("dispatch must journal the SHIP state-change against the dock, not the origin")
            .isGreaterThanOrEqualTo(1L)

        val shippedEvents = outboxEventRepository.find(
            "aggregateType = ?1 and eventType = ?2 and tenantId = ?3 order by created desc",
            "StockUnit", "StateChanged", 9601L,
        ).list()
        assertThat(shippedEvents)
            .`as`("a StockUnit StateChanged outbox row must carry the dock's location name")
            .anyMatch { it.payload.contains(dockName) }
    }

    /**
     * S6 (outbound-completion task-8), knob ON: `karyo.shipping.rename-unit-load` set true for
     * this test's dedicated client makes dispatch append "-" + the unit load's own id to its
     * labelId. Also pins idempotency: calling the SPI method a second time after dispatch (the
     * UL is already renamed, and by then DELETABLE) must not double-append the suffix.
     */
    @Test
    @TestSecurity(
        user = "ff-rename-on",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9602"), Claim(key = "tenant_code", value = "SHIP-RENAME-ON")])
    fun `dispatch renames the shipped unit load's labelId when the knob is on, idempotently`() {
        seedPackStaging()
        seedShipStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)

        tenantContext.clientId = 9602L
        systemPropertyService.set(9602L, "karyo.shipping.rename-unit-load", null, "true")

        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()
        shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        entityManager.clear()

        val unitLoadId = shippingUnitRepository.findByShipmentId(shipment.id!!).first().unitLoadId!!
        val originalLabel = labelIdOf(unitLoadId)

        shippingService.dispatch(shipment.id!!)
        entityManager.clear()

        val renamedLabel = labelIdOf(unitLoadId)
        assertThat(renamedLabel)
            .`as`("dispatch must append \"-\" + the unit load's own id to its labelId")
            .isEqualTo("$originalLabel-$unitLoadId")

        tenantContext.clientId = 9602L
        val secondCall = unitLoadMover.appendDispatchSuffix(unitLoadId)
        assertThat(secondCall)
            .`as`("a second rename call must be idempotent, not double-append the suffix")
            .isEqualTo(renamedLabel)
    }

    /**
     * S6 (outbound-completion task-8), knob OFF (default): with no `karyo.shipping.rename-unit-
     * load` row stored for this test's dedicated client, dispatch must leave the labelId
     * unchanged.
     */
    @Test
    @TestSecurity(
        user = "ff-rename-off",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9603"), Claim(key = "tenant_code", value = "SHIP-RENAME-OFF")])
    fun `dispatch leaves the unit load's labelId unchanged when the knob is off by default`() {
        seedPackStaging()
        seedShipStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)

        tenantContext.clientId = 9603L

        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()
        shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        entityManager.clear()

        val unitLoadId = shippingUnitRepository.findByShipmentId(shipment.id!!).first().unitLoadId!!
        val originalLabel = labelIdOf(unitLoadId)

        shippingService.dispatch(shipment.id!!)
        entityManager.clear()

        assertThat(labelIdOf(unitLoadId))
            .`as`("the knob defaults false, so dispatch must not touch the labelId")
            .isEqualTo(originalLabel)
    }
}
