package com.karyo.orders

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.StorageLocationRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test (REAL beans) for Row 10's destination location, senderName, and the derived
 * `documentUrl`/`labelUrl` document links on [com.karyo.orders.dto.DeliveryOrderResponse].
 */
@QuarkusTest
class DeliveryOrderDestinationTest {

    @Inject
    lateinit var storageLocationRepository: StorageLocationRepository

    @Inject
    lateinit var shipmentRepository: ShipmentRepository

    @Inject
    lateinit var shippingUnitRepository: ShippingUnitRepository

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Destination Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocation(name: String, typeId: Long, areaId: Long): io.restassured.path.json.JsonPath =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath()

    /** Directly retargets a location's goods owner -- REST always stamps the caller's own
     *  clientId, so a genuinely foreign row can only be set by direct entity mutation (mirrors
     *  LocationFinderStrategyOwnershipTest's `setLocationClient`). */
    @Transactional
    fun setLocationClient(id: Long, ownerClientId: Long) {
        val loc = storageLocationRepository.getEntityManager().find(StorageLocation::class.java, id)
        loc.clientId = ownerClientId
    }

    private fun seedProduct(): Long {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("DST-IU-${suffix.toString().takeLast(8)}")
        return createProduct("DST-SKU-$suffix", itemUnitId)
    }

    /** A shipment with no shipping unit yet (e.g. packing not started) -- the labelUrl-stays-null case. */
    @Transactional
    fun persistShipmentOnly(deliveryOrderId: Long, clientId: Long): Long {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-DST-${System.nanoTime()}"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "ORD-$deliveryOrderId"
            state = ShipmentState.PACKED.code
        }
        shipmentRepository.persist(shp)
        return shp.id!!
    }

    @Transactional
    fun persistShippingUnit(shipmentId: Long, clientId: Long): Long {
        val su = ShippingUnit().apply {
            this.clientId = clientId
            this.shipmentId = shipmentId
            shippingUnitNumber = "SU-DST-${System.nanoTime()}"
            type = "CARTON"
            weight = BigDecimal("1.000")
            state = ShipmentState.PACKED.code
        }
        shippingUnitRepository.persist(su)
        return su.id!!
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "order-read", "order-write", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a destination location id round-trips on create and appears with its name on the response`() {
        val suffix = System.nanoTime()
        val areaId = createArea("DST-AREA-$suffix")
        val typeId = createLocationType("DST-TYPE-$suffix")
        val loc = createLocation("DST-LOC-$suffix", typeId, areaId)
        val locId = loc.getLong("id")
        val locName = loc.getString("name")

        val pid = seedProduct()
        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Dst Customer","destinationLocationId":$locId,""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)
            .body("destinationLocationId", `is`(locId.toInt()))
            .body("destinationLocationName", `is`(locName))
            .extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("destinationLocationId", `is`(locId.toInt()))
            .body("destinationLocationName", `is`(locName))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an unknown destination location id is refused with 422`() {
        val pid = seedProduct()

        given().contentType(ContentType.JSON).body(
            """{"customerName":"Bad Dst","destinationLocationId":999999999,""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(422)
            .body("type", `is`("https://karyo.com/errors/invalid-destination-location"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a location owned by another client is refused with 422`() {
        val suffix = System.nanoTime()
        val areaId = createArea("FGN-AREA-$suffix")
        val typeId = createLocationType("FGN-TYPE-$suffix")
        val loc = createLocation("FGN-LOC-$suffix", typeId, areaId)
        val locId = loc.getLong("id")
        setLocationClient(locId, ownerClientId = 999L)

        val pid = seedProduct()
        given().contentType(ContentType.JSON).body(
            """{"customerName":"Foreign Dst","destinationLocationId":$locId,""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(422)
            .body("type", `is`("https://karyo.com/errors/invalid-destination-location"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `senderName round-trips on create and update`() {
        val pid = seedProduct()
        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Sender Test","senderName":"Origin Warehouse Co",""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)
            .body("senderName", `is`("Origin Warehouse Co"))
            .extract().jsonPath().getLong("id")

        given().contentType(ContentType.JSON).body("""{"senderName":"Updated Sender Inc"}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("senderName", `is`("Updated Sender Inc"))

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("senderName", `is`("Updated Sender Inc"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `senderName tri-state -- absent leaves it unchanged, explicit null clears it`() {
        val pid = seedProduct()
        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Sender Tri-State","senderName":"Origin Warehouse Co",""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        // Field ABSENT from the update body -- stays unchanged.
        given().contentType(ContentType.JSON).body("""{"customerName":"Sender Tri-State"}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("senderName", `is`("Origin Warehouse Co"))

        // Explicit JSON null -- clears it.
        given().contentType(ContentType.JSON).body("""{"senderName":null}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("senderName", nullValue())

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("senderName", nullValue())
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `destinationLocationId tri-state -- absent unchanged, unknown id still 422 on update, explicit null clears`() {
        val suffix = System.nanoTime()
        val areaId = createArea("TRI-AREA-$suffix")
        val typeId = createLocationType("TRI-TYPE-$suffix")
        val loc = createLocation("TRI-LOC-$suffix", typeId, areaId)
        val locId = loc.getLong("id")

        val pid = seedProduct()
        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Dst Tri-State","destinationLocationId":$locId,""" +
                """"lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        // Field ABSENT from the update body -- stays unchanged.
        given().contentType(ContentType.JSON).body("""{"customerName":"Dst Tri-State"}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("destinationLocationId", `is`(locId.toInt()))

        // Set-path still validates -- an unknown id on update is 422, same as create.
        given().contentType(ContentType.JSON).body("""{"destinationLocationId":999999999}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(422)
            .body("type", `is`("https://karyo.com/errors/invalid-destination-location"))

        // Explicit JSON null -- clears it (null is always valid, bypasses validation).
        given().contentType(ContentType.JSON).body("""{"destinationLocationId":null}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("destinationLocationId", nullValue())

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("destinationLocationId", nullValue())
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `documentUrl always points at this order's delivery note route`() {
        val pid = seedProduct()
        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Doc Url Test","lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("documentUrl", `is`("/api/v1/delivery-orders/$orderId/delivery-note.pdf"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write", "order-read", "order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `labelUrl is null until a shipping unit exists, even once a shipment does, then points at the label route`() {
        val pid = seedProduct()
        val orderId = given().contentType(ContentType.JSON).body(
            """{"customerName":"Label Url Test","lines":[{"itemDataId":$pid,"amount":1.0}]}""",
        ).`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("labelUrl", `is`(nullValue()))

        // Row 10's whole design point: the gate is shipping-UNIT existence, not shipment
        // existence -- a shipment with no shipping unit yet (packing not started) must still
        // yield a null labelUrl, never a link that would 404.
        val shipmentId = persistShipmentOnly(orderId, clientId = 1L)
        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("labelUrl", `is`(nullValue()))

        val suId = persistShippingUnit(shipmentId, clientId = 1L)
        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .body("labelUrl", `is`("/api/v1/shipping-units/$suId/label.zpl"))
    }
}
