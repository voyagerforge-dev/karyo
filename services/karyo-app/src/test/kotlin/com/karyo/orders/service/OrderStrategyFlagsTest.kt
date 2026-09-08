package com.karyo.orders.service

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.ShippingService
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
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Row 8 (register row 8, WORKLIST): the five OrderStrategy flags -- sendToPacking, sendToShipping,
 * createShippingOrder, createTypeOrders, defaultDestinationLocationId. Full state-sequence
 * assertions (not a `>=` guard) per adjudication A9 -- see [PickOrderService.releaseToPicking] and
 * [com.karyo.orders.service.DefaultOrderProgressionPort]'s class KDoc for the mechanism these
 * tests pin. `createTypeOrders` gets its own [com.karyo.fulfillment.TypeOrderSplitTest].
 */
@QuarkusTest
class OrderStrategyFlagsTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var packingService: PackingService
    @Inject lateinit var shippingService: ShippingService
    @Inject lateinit var shipmentRepository: ShipmentRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var entityManager: EntityManager

    // ── REST seeding helpers ────────────────────────────────────────────

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

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocation(name: String, typeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")

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

    private fun createStrategy(
        name: String,
        sendToPacking: Boolean = false,
        sendToShipping: Boolean = false,
        createShippingOrder: Boolean = false,
        createTypeOrders: Boolean = false,
        defaultDestinationLocationId: Long? = null,
    ): Long {
        val destField = defaultDestinationLocationId?.let { ""","defaultDestinationLocationId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body(
                """{"name":"$name","sendToPacking":$sendToPacking,"sendToShipping":$sendToShipping,""" +
                    """"createShippingOrder":$createShippingOrder,"createTypeOrders":$createTypeOrders$destField}""",
            )
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Seeds a product with 100 units of stock and a released order for 60, optionally under [strategyId]. */
    private fun seedAndReleaseOrderFor60(strategyId: Long? = null, destinationLocationId: Long? = null): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("OSF-IU-${s.toString().takeLast(8)}")
        val num = "OSF-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("OSF-UL-$s")
        createStock(ul, pid, num, 100.0)
        val strategyField = strategyId?.let { ""","orderStrategyId":$it""" } ?: ""
        val destField = destinationLocationId?.let { ""","destinationLocationId":$it""" } ?: ""
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$pid,"amount":60.0}]$strategyField$destField}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    private fun orderState(orderId: Long): Int =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

    @Transactional
    fun persistBlockingShipment(deliveryOrderId: Long, clientId: Long) {
        val s = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-BLOCK-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            this.deliveryOrderNumber = "BLOCK"
            state = ShipmentState.PACKING.code
            started = Instant.now()
        }
        shipmentRepository.persist(s)
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "flags", roles = ["order-read", "order-write", "layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `all five flags round-trip through create update and response`() {
        val suffix = System.nanoTime()
        val areaId = createArea("RT-AREA-$suffix")
        val typeId = createLocationType("RT-TYPE-$suffix")
        val locId = createLocation("RT-LOC-$suffix", typeId, areaId)

        val strategyId = createStrategy(
            "RT-STRAT-$suffix",
            sendToPacking = true, sendToShipping = true,
            createShippingOrder = true, createTypeOrders = true,
            defaultDestinationLocationId = locId,
        )

        given().`when`().get("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200)
            .body("sendToPacking", `is`(true))
            .body("sendToShipping", `is`(true))
            .body("createShippingOrder", `is`(true))
            .body("createTypeOrders", `is`(true))
            .body("defaultDestinationLocationId", `is`(locId.toInt()))

        given().contentType(ContentType.JSON)
            .body(
                """{"sendToPacking":false,"sendToShipping":false,"createShippingOrder":false,""" +
                    """"createTypeOrders":false}""",
            )
            .`when`().put("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200)
            .body("sendToPacking", `is`(false))
            .body("sendToShipping", `is`(false))
            .body("createShippingOrder", `is`(false))
            .body("createTypeOrders", `is`(false))
            // defaultDestinationLocationId is tri-state (Patchable<Long>, :1457) -- ABSENT from
            // the update body, so it stays whatever create left it at.
            .body("defaultDestinationLocationId", `is`(locId.toInt()))

        // Explicit JSON null clears it.
        given().contentType(ContentType.JSON)
            .body("""{"defaultDestinationLocationId":null}""")
            .`when`().put("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200)
            .body("defaultDestinationLocationId", nullValue())

        given().`when`().get("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200)
            .body("defaultDestinationLocationId", nullValue())
    }

    @Test
    @TestSecurity(user = "flags", roles = ["order-read", "order-write", "layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `updating defaultDestinationLocationId to an unknown id is refused with 422`() {
        val strategyId = createStrategy("BADUPD-${System.nanoTime()}")

        given().contentType(ContentType.JSON)
            .body("""{"defaultDestinationLocationId":999999999}""")
            .`when`().put("/api/v1/order-strategies/$strategyId")
            .then().statusCode(422)
            .body("type", `is`("https://karyo.com/errors/invalid-destination-location"))
    }

    @Test
    @TestSecurity(user = "flags", roles = ["order-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the DEFAULT strategy leaves every flag off so existing behavior is unchanged`() {
        val strategies = given().`when`().get("/api/v1/order-strategies")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any?>>("")
        val default = strategies.first { it["name"] == "DEFAULT" }

        assertThat(default["sendToPacking"]).isEqualTo(false)
        assertThat(default["sendToShipping"]).isEqualTo(false)
        assertThat(default["createShippingOrder"]).isEqualTo(false)
        assertThat(default["createTypeOrders"]).isEqualTo(false)
        assertThat(default["defaultDestinationLocationId"]).isNull()
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `sendToPacking parks a picked order in PACKING and packing still moves it to PACKED`() {
        seedPackStaging()
        val strategyId = createStrategy("SP-${System.nanoTime()}", sendToPacking = true)
        val orderId = seedAndReleaseOrderFor60(strategyId)
        tenantContext.clientId = 1L

        val states = mutableListOf<Int>()

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        states += orderState(orderId) // STARTED(500)

        pickOrderService.picksOf(pickOrder.id!!).forEach {
            pickOrderService.confirmPick(it.id!!, it.plannedAmount, null)
        }
        entityManager.clear()
        states += orderState(orderId) // parked at PACKING(640), not PICKED(600)

        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("1.0"), "CARTON")
        entityManager.clear()
        states += orderState(orderId) // markPacked still moves it on to PACKED(650)

        assertThat(states).containsExactly(500, 640, 650)
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `sendToShipping parks a packed order in SHIPPING and dispatch still moves it to SHIPPED`() {
        seedPackStaging()
        seedShipStaging()
        val strategyId = createStrategy("SS-${System.nanoTime()}", sendToShipping = true)
        val orderId = seedAndReleaseOrderFor60(strategyId)
        tenantContext.clientId = 1L

        val states = mutableListOf<Int>()

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        states += orderState(orderId) // STARTED(500)

        pickOrderService.picksOf(pickOrder.id!!).forEach {
            pickOrderService.confirmPick(it.id!!, it.plannedAmount, null)
        }
        entityManager.clear()
        states += orderState(orderId) // sendToPacking is off -> straight to PICKED(600)

        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("1.0"), "CARTON")
        entityManager.clear()
        states += orderState(orderId) // markPacked, then parked at SHIPPING(670) -- 650 is never externally observable

        shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        shippingService.dispatch(shipment.id!!)
        entityManager.clear()
        // dispatch() chains markShipped THEN markFinished unconditionally (pre-existing, row-8-
        // independent behavior -- see ShippingServiceTest) -- markShipped DID run and move the
        // order past SHIPPING (progressIfBehind from 670), the observable end state is FINISHED.
        states += orderState(orderId)

        assertThat(states).containsExactly(500, 600, 670, 700)
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `with both flags off an order runs PICKED then PACKED then SHIPPED exactly as before`() {
        seedPackStaging()
        seedShipStaging()
        val orderId = seedAndReleaseOrderFor60() // DEFAULT strategy -- both flags off
        tenantContext.clientId = 1L

        val states = mutableListOf<Int>()

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        states += orderState(orderId) // STARTED(500)

        pickOrderService.picksOf(pickOrder.id!!).forEach {
            pickOrderService.confirmPick(it.id!!, it.plannedAmount, null)
        }
        entityManager.clear()
        states += orderState(orderId) // PICKED(600) -- no PACKING park

        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("1.0"), "CARTON")
        entityManager.clear()
        states += orderState(orderId) // PACKED(650) -- no SHIPPING park

        shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        shippingService.dispatch(shipment.id!!)
        entityManager.clear()
        states += orderState(orderId) // dispatch's existing markShipped+markFinished chain -> FINISHED(700)

        assertThat(states).containsExactly(500, 600, 650, 700)
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `createShippingOrder opens the shipment automatically when picking completes`() {
        seedPackStaging()
        val strategyId = createStrategy("CSO-${System.nanoTime()}", createShippingOrder = true)
        val orderId = seedAndReleaseOrderFor60(strategyId)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        pickOrderService.picksOf(pickOrder.id!!).forEach {
            pickOrderService.confirmPick(it.id!!, it.plannedAmount, null)
        }
        entityManager.clear()

        // No manual openPacking call -- the flag auto-opened it on pick completion.
        val shipment = shipmentRepository.findByDeliveryOrderId(orderId, 1L)
        assertThat(shipment).isNotNull
        assertThat(shipment!!.state).isEqualTo(ShipmentState.PACKING.code)
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a createShippingOrder auto-open that the packing service refuses does not fail the pick`() {
        seedPackStaging()
        val strategyId = createStrategy("CSO-REFUSE-${System.nanoTime()}", createShippingOrder = true)
        val orderId = seedAndReleaseOrderFor60(strategyId)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        // Pre-seed a shipment for this order so openPacking's "a shipment already exists" guard
        // refuses the auto-open -- proves the REQUIRES_NEW isolation in
        // PackingService.openPackingBestEffort actually holds under a real refusal, not just in
        // theory.
        persistBlockingShipment(orderId, 1L)

        pickOrderService.picksOf(pickOrder.id!!).forEach {
            pickOrderService.confirmPick(it.id!!, it.plannedAmount, null)
        }
        entityManager.clear()

        // The pick confirmation's own work was NOT rolled back despite the auto-open's refusal.
        given().`when`().get("/api/v1/pick-orders/${pickOrder.id}").then().statusCode(200).body("state", `is`(600))
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))

        // Still exactly the one pre-seeded shipment -- the refused auto-open created nothing.
        val shipment = shipmentRepository.findByDeliveryOrderId(orderId, 1L)
        assertThat(shipment).isNotNull
        assertThat(shipment!!.shipmentNumber).startsWith("SHP-BLOCK-")
    }

    @Test
    @TestSecurity(user = "flags", roles = ["order-read", "order-write", "layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an unknown defaultDestination location id is refused with 422`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"BADDEST-${System.nanoTime()}","defaultDestinationLocationId":999999999}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(422)
            .body("type", `is`("https://karyo.com/errors/invalid-destination-location"))
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the order's own destination wins over the strategy default`() {
        seedPackStaging()
        val suffix = System.nanoTime()
        val areaId = createArea("WIN-AREA-$suffix")
        val typeId = createLocationType("WIN-TYPE-$suffix")
        val strategyLocId = createLocation("WIN-STRAT-LOC-$suffix", typeId, areaId)
        val orderLocId = createLocation("WIN-ORDER-LOC-$suffix", typeId, areaId)
        val strategyId = createStrategy("WIN-$suffix", defaultDestinationLocationId = strategyLocId)
        val orderId = seedAndReleaseOrderFor60(strategyId, destinationLocationId = orderLocId)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        assertThat(pickOrder.destinationLocationId).isEqualTo(orderLocId)
    }

    @Test
    @TestSecurity(user = "flags", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write",
        "fulfillment-read", "fulfillment-write", "MANAGER",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the strategy default applies when the order has no destination`() {
        seedPackStaging()
        val suffix = System.nanoTime()
        val areaId = createArea("DEF-AREA-$suffix")
        val typeId = createLocationType("DEF-TYPE-$suffix")
        val strategyLocId = createLocation("DEF-STRAT-LOC-$suffix", typeId, areaId)
        val strategyId = createStrategy("DEF-$suffix", defaultDestinationLocationId = strategyLocId)
        val orderId = seedAndReleaseOrderFor60(strategyId) // no destinationLocationId of its own
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        assertThat(pickOrder.destinationLocationId).isEqualTo(strategyLocId)
    }
}
