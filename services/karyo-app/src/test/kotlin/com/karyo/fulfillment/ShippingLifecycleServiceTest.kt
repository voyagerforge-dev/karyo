package com.karyo.fulfillment

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.ShippingLifecycleService
import com.karyo.fulfillment.service.ShippingService
import com.karyo.fulfillment.vo.ShipmentState
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
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * S3 (outbound-completion sprint): shipment claim/release/pause/resume lifecycle. Mirrors
 * [PickCancelServiceTest]'s conventions -- direct service calls under `@TestSecurity`, REST only
 * for the seeding scaffolding that isn't the thing under test.
 */
@QuarkusTest
class ShippingLifecycleServiceTest {

    @Inject lateinit var packingService: PackingService

    @Inject lateinit var shippingService: ShippingService

    @Inject lateinit var lifecycleService: ShippingLifecycleService

    @Inject lateinit var tenantContext: TenantContext

    @Inject lateinit var entityManager: EntityManager

    @Inject lateinit var inventoryJournalRepository: InventoryJournalRepository

    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository

    @Inject lateinit var outboxEventRepository: OutboxEventRepository

    @Inject lateinit var shipmentRepository: ShipmentRepository

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
        createStockWithState(ulId, itemDataId, number, amount, 300)

    private fun createStockWithState(ulId: Long, itemDataId: Long, number: String, amount: Double, state: Int): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,
                    |"state":$state}""".trimMargin(),
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /**
     * Seeds the shared-unit-load shape accepted by multi-box packout integrations. The free
     * ONE_TO_ONE strategy always yields one unit, so this creates [unitCount] units on a PACKED
     * shipment that all share [unitLoadId] directly through the fulfillment repositories.
     */
    @Transactional
    fun seedSharedUnitLoadShipment(sharedUnitLoadId: Long, unitCount: Int): Long {
        val s = Shipment().apply {
            clientId = 1L
            shipmentNumber = "SHIP-CTN-${System.nanoTime()}"
            deliveryOrderId = 1L
            deliveryOrderNumber = "DO-CTN"
            state = ShipmentState.PACKED.code
        }
        shipmentRepository.persist(s)
        (1..unitCount).forEach { idx ->
            val u = ShippingUnit().apply {
                clientId = 1L
                shipmentId = s.id!!
                positionIndex = idx
                shippingUnitNumber = "${s.shipmentNumber}-SU$idx"
                type = "CARTON"
                weight = BigDecimal("1.0")
                state = ShipmentState.PACKED.code
                unitLoadId = sharedUnitLoadId
                origin = ShippingUnit.ORIGIN_PACKOUT
            }
            shippingUnitRepository.persist(u)
        }
        return s.id!!
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

    /** Opens + packs a shipment (state PACKED, 650) via direct service calls, tenant primed to 1. */
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

    /** Drives a shipment all the way to SHIPPED (680) via manifest + dispatch, for the closed-state guard tests. */
    private fun shippedShipmentId(): Long {
        seedShipStaging()
        val shipmentId = packedShipmentId()
        shippingService.manifest(shipmentId, "UPS", "GROUND", null)
        entityManager.clear()
        shippingService.dispatch(shipmentId)
        entityManager.clear()
        return shipmentId
    }

    /** A two-line order (own product + stock unit each), released and driven to PICKED. */
    private fun seedAndReleaseTwoLineOrder(amount: Double): Long {
        val s = System.nanoTime()
        val iuA = createItemUnit("OUA-$s"); val numA = "PA-$s"
        val pidA = createProduct(numA, iuA); val ulA = createUnitLoad("ULA-$s")
        createStock(ulA, pidA, numA, amount)
        val iuB = createItemUnit("OUB-$s"); val numB = "PB-$s"
        val pidB = createProduct(numB, iuB); val ulB = createUnitLoad("ULB-$s")
        createStock(ulB, pidB, numB, amount)
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","lines":[{"itemDataId":$pidA,"amount":$amount},
                    |{"itemDataId":$pidB,"amount":$amount}]}""".trimMargin(),
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /** Opens + packs a shipment with ONE unit carrying TWO lines (ONE_TO_ONE packout: one unit, one line per pick). */
    private fun packedTwoLineShipmentId(): Long {
        seedPackStaging()
        val orderId = seedAndReleaseTwoLineOrder(50.0)
        pickToPicked(orderId)
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()
        return shipment.id!!
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claiming an unclaimed shipment stamps the operator`() {
        val shipmentId = packedShipmentId()

        val claimed = lifecycleService.claim(shipmentId, "alice")
        entityManager.clear()

        assertThat(claimed.operatorId).isEqualTo("alice")
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claiming an already-claimed shipment is a 409 conflict, even for the same operator`() {
        val shipmentId = packedShipmentId()
        lifecycleService.claim(shipmentId, "alice")
        entityManager.clear()

        assertThatThrownBy { lifecycleService.claim(shipmentId, "bob") }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
        assertThatThrownBy { lifecycleService.claim(shipmentId, "alice") }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release by the claiming operator clears the claim`() {
        val shipmentId = packedShipmentId()
        lifecycleService.claim(shipmentId, "alice")
        entityManager.clear()

        val released = lifecycleService.release(shipmentId, "alice", asManager = false)
        entityManager.clear()

        assertThat(released.operatorId).isNull()
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release by a non-owner without asManager is a 409, but a MANAGER can force-release`() {
        val shipmentId = packedShipmentId()
        lifecycleService.claim(shipmentId, "alice")
        entityManager.clear()

        assertThatThrownBy { lifecycleService.release(shipmentId, "bob", asManager = false) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)

        val released = lifecycleService.release(shipmentId, "bob", asManager = true)
        assertThat(released.operatorId).isNull()
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pause is claim-preserving -- the operator stays claimed and the state is untouched`() {
        val shipmentId = packedShipmentId()
        lifecycleService.claim(shipmentId, "alice")
        entityManager.clear()

        val paused = lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThat(paused.pausedAt).isNotNull()
        assertThat(paused.operatorId).isEqualTo("alice")
        assertThat(paused.state).isEqualTo(ShipmentState.PACKED.code)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `resume clears the pause stamp`() {
        val shipmentId = packedShipmentId()
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        val resumed = lifecycleService.resume(shipmentId)
        entityManager.clear()

        assertThat(resumed.pausedAt).isNull()
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `resuming a shipment that is not paused is a 409`() {
        val shipmentId = packedShipmentId()

        assertThatThrownBy { lifecycleService.resume(shipmentId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pausing an already-paused shipment is a 409`() {
        val shipmentId = packedShipmentId()
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { lifecycleService.pause(shipmentId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses claim`() {
        val shipmentId = packedShipmentId()
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { lifecycleService.claim(shipmentId, "alice") }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses pack`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderFor60()
        pickToPicked(orderId)
        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        entityManager.clear()

        lifecycleService.pause(shipment.id!!)
        entityManager.clear()

        assertThatThrownBy { packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON") }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses manifest`() {
        val shipmentId = packedShipmentId()
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { shippingService.manifest(shipmentId, "UPS", "GROUND", null) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses dispatch`() {
        seedPackStaging()
        seedShipStaging()
        val shipmentId = packedShipmentId()
        shippingService.manifest(shipmentId, "UPS", "GROUND", null)
        entityManager.clear()

        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { shippingService.dispatch(shipmentId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a SHIPPED (closed) shipment refuses claim`() {
        val shipmentId = shippedShipmentId()

        assertThatThrownBy { lifecycleService.claim(shipmentId, "alice") }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a SHIPPED (closed) shipment refuses pause`() {
        val shipmentId = shippedShipmentId()

        assertThatThrownBy { lifecycleService.pause(shipmentId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    // --- S4 (task 6): cancel + unit/line removal with origin-aware stock restoration ---

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling a PACKED shipment restores container stock to PICKED, journals UNPACK, and frees the order to repack`() {
        val shipmentId = packedShipmentId()
        // Not a Sprint C GROUP shipment -- deliveryOrderId is guaranteed non-null here.
        val orderId = packingService.getShipment(shipmentId).deliveryOrderId!!
        val unit = packingService.unitsOf(shipmentId).first()
        val ulId = unit.unitLoadId!!

        val outboxCountBefore = outboxEventRepository.count(
            "aggregateType = ?1 and eventType = ?2 and aggregateId = ?3",
            "Shipment", "ShipmentCanceled", shipmentId,
        )

        val canceled = lifecycleService.cancel(shipmentId)
        entityManager.clear()

        assertThat(canceled.state).isEqualTo(ShipmentState.CANCELED.code)

        val stockUnits = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        assertThat(stockUnits).isNotEmpty
        val stockUnitIds = stockUnits.map { (it["id"] as Number).toLong() }
        stockUnits.forEach { su -> assertThat(su["state"] as Int).isEqualTo(600) }

        val journalRows = inventoryJournalRepository.list("activityCode = ?1 and stockUnitId in ?2", "UNPACK", stockUnitIds)
        assertThat(journalRows).hasSize(stockUnitIds.size)

        // Binding requirement: exactly ONE ShipmentCanceled outbox row, under the shipment's clientId.
        val outboxCountAfter = outboxEventRepository.count(
            "aggregateType = ?1 and eventType = ?2 and aggregateId = ?3",
            "Shipment", "ShipmentCanceled", shipmentId,
        )
        assertThat(outboxCountAfter - outboxCountBefore).isEqualTo(1L)
        val canceledEvents = outboxEventRepository.find(
            "aggregateType = ?1 and eventType = ?2 and aggregateId = ?3",
            "Shipment", "ShipmentCanceled", shipmentId,
        ).list()
        assertThat(canceledEvents).hasSize(1)
        assertThat(canceledEvents.single().tenantId).isEqualTo(canceled.clientId)

        val reopened = packingService.openPacking(orderId)
        assertThat(reopened).isNotNull
        assertThat(reopened.state).isEqualTo(ShipmentState.PACKING.code)

        // CRITICAL 2 (final-review fix wave): the DeliveryOrder never left PACKED across the
        // cancel (only the SHIPMENT regressed), so this re-pack's completion calls
        // OrderProgressionPort.markPacked while the order is ALREADY at PACKED. Before the
        // DefaultOrderProgressionPort idempotency fix, that threw OrderException.InvalidTransition
        // and rolled back the whole re-pack, including the shipment flip -- this is the repro.
        val repacked = packingService.pack(reopened.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()
        assertThat(repacked.state).isEqualTo(ShipmentState.PACKED.code)
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(650))
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `canceling a post-manifest shipment is a 409`() {
        val shipmentId = shippedShipmentId()

        assertThatThrownBy { lifecycleService.cancel(shipmentId) }
            .isInstanceOf(FulfillmentException.NotCancelable::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removeUnit restores that unit's unit load and deletes unit+lines, shipment regresses to PACKING`() {
        val shipmentId = packedShipmentId()
        val unit = packingService.unitsOf(shipmentId).first()
        val ulId = unit.unitLoadId!!

        val updated = lifecycleService.removeUnit(shipmentId, unit.id!!)
        entityManager.clear()

        assertThat(updated.state).isEqualTo(ShipmentState.PACKING.code)
        assertThat(packingService.unitsOf(shipmentId)).isEmpty()

        val stockUnits = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        assertThat(stockUnits).isNotEmpty
        stockUnits.forEach { su -> assertThat(su["state"] as Int).isEqualTo(600) }

        // IMPORTANT 6 (final-review fix wave): removal must be visible in the outbox -- ONE
        // ShippingUnitRemoved row, under the shipment's clientId, carrying the resulting
        // (regressed) shipment state.
        val removedEvents = outboxEventRepository.find(
            "aggregateType = ?1 and eventType = ?2 and aggregateId = ?3",
            "Shipment", "ShippingUnitRemoved", shipmentId,
        ).list()
        assertThat(removedEvents).hasSize(1)
        assertThat(removedEvents.single().tenantId).isEqualTo(updated.clientId)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removeUnit on a post-manifest shipment is a 409`() {
        val shipmentId = shippedShipmentId()
        val unit = packingService.unitsOf(shipmentId).first()

        assertThatThrownBy { lifecycleService.removeUnit(shipmentId, unit.id!!) }
            .isInstanceOf(FulfillmentException.NotCancelable::class.java)
    }

    // --- CRITICAL 3 (final-review fix wave): sole-referent guard on removeUnit ---

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removeUnit refuses a unit whose unit load is shared with a live sibling unit -- cartonization-style multi-box pack`() {
        tenantContext.clientId = 1L
        val s = System.nanoTime()
        val iu = createItemUnit("CTN-${s.toString().takeLast(10)}"); val num = "CTN-SKU-$s"
        val pid = createProduct(num, iu); val ulId = createUnitLoad("CTN-UL-$s")
        createStockWithState(ulId, pid, num, 40.0, 600) // PICKED -- matches restoreUnit's PACKOUT target
        val shipmentId = seedSharedUnitLoadShipment(ulId, unitCount = 2)
        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        assertThat(units).hasSize(2)

        assertThatThrownBy { lifecycleService.removeUnit(shipmentId, units[0].id!!) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)

        // Refused before any mutation: both units and the original stock state survive untouched.
        assertThat(shippingUnitRepository.findByShipmentId(shipmentId)).hasSize(2)
        val stockUnits = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        stockUnits.forEach { su -> assertThat(su["state"] as Int).isEqualTo(600) }
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removeUnit still restores stock and succeeds when the unit is the sole referent of its unit load`() {
        tenantContext.clientId = 1L
        val s = System.nanoTime()
        val iu = createItemUnit("SOLE-${s.toString().takeLast(10)}"); val num = "SOLE-SKU-$s"
        val pid = createProduct(num, iu); val ulId = createUnitLoad("SOLE-UL-$s")
        createStockWithState(ulId, pid, num, 40.0, 600) // PICKED
        val shipmentId = seedSharedUnitLoadShipment(ulId, unitCount = 1)
        val unit = shippingUnitRepository.findByShipmentId(shipmentId).single()

        val updated = lifecycleService.removeUnit(shipmentId, unit.id!!)
        entityManager.clear()

        assertThat(updated.state).isEqualTo(ShipmentState.PACKING.code)
        assertThat(shippingUnitRepository.findByShipmentId(shipmentId)).isEmpty()
        val stockUnits = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        stockUnits.forEach { su -> assertThat(su["state"] as Int).isEqualTo(600) }
    }

    // --- Task 6 (row :1354, adjudication A3): packing consumption ledger ---

    private fun createStrategyWithTypeOrders(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","createTypeOrders":true}""")
            .`when`().post("/api/v1/order-strategies").then().statusCode(201).extract().jsonPath().getLong("id")

    /**
     * Two sibling PickOrders (COMPLETE + PICK picking types, `createTypeOrders`) under ONE
     * delivery order, packed via ONE_TO_ONE into two separate ShippingUnits on DIFFERENT
     * unitLoadIds (own container each) -- mirrors `TypeOrderSplitTest.seedMixedReleaseOrder` /
     * `packing a split release...`. The Task 6 witness (b) fixture: two REAL sibling units that
     * do not share a unit load, so removing one never trips [requireSoleReferent].
     */
    private fun twoContainerPackedShipmentId(): Long {
        seedPackStaging()
        val strategyId = createStrategyWithTypeOrders("T6-SPLIT-${System.nanoTime()}")
        val s = System.nanoTime()
        val iu = createItemUnit("T6-IU-${s.toString().takeLast(8)}")
        val numFull = "T6-FULL-$s"
        val pidFull = createProduct(numFull, iu)
        val ulFull = createUnitLoad("T6-ULF-$s")
        createStock(ulFull, pidFull, numFull, 100.0) // whole-unit draw -> COMPLETE
        val numPart = "T6-PART-$s"
        val pidPart = createProduct(numPart, iu)
        val ulPart = createUnitLoad("T6-ULP-$s")
        createStock(ulPart, pidPart, numPart, 100.0) // partial draw -> PICK
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"C","orderStrategyId":$strategyId,"lines":[""" +
                    """{"itemDataId":$pidFull,"amount":100.0},""" +
                    """{"itemDataId":$pidPart,"amount":40.0}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)

        val pickOrders = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
            .getList<Map<String, Any>>("")
        pickOrders.forEach { po ->
            @Suppress("UNCHECKED_CAST")
            val picks = po["picks"] as List<Map<String, Any>>
            picks.forEach { pick ->
                val amt = (pick["plannedAmount"] as Number).toDouble()
                given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                    .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
            }
        }
        entityManager.clear()

        tenantContext.clientId = 1L
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("4.0"), "CARTON")
        entityManager.clear()
        return shipment.id!!
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `repack after removeUnit re-selects exactly the freed quantity, sibling unit untouched`() {
        val shipmentId = twoContainerPackedShipmentId()
        val unitsBefore = shippingUnitRepository.findByShipmentId(shipmentId)
        assertThat(unitsBefore).hasSize(2)
        assertThat(unitsBefore.map { it.unitLoadId }.toSet()).hasSize(2)

        val toRemove = unitsBefore[0]
        val sibling = unitsBefore[1]
        val siblingWeightBefore = sibling.weight
        val siblingLinesBefore = shippingUnitRepository.findLinesByUnitId(sibling.id!!)
        val removedLine = shippingUnitRepository.findLinesByUnitId(toRemove.id!!).single()
        val freedAmount = removedLine.amount
        val freedPickId = removedLine.sourcePickId

        val afterRemove = lifecycleService.removeUnit(shipmentId, toRemove.id!!)
        entityManager.clear()
        assertThat(afterRemove.state).isEqualTo(ShipmentState.PACKING.code)
        assertThat(shippingUnitRepository.findByShipmentId(shipmentId)).hasSize(1)

        // Task 6 review fix (row :1354): the weight contract is "what's on the scale THIS call,"
        // not a running shipment total (see pack()'s KDoc) -- so the repack enters a DIFFERENT,
        // smaller weight (only the freed container's own weight), never the original 4.0 total.
        // Pre-fix, this call also re-selects every still-PICKED pick -- including the sibling's
        // already packed one -- and would duplicate its line. Post-fix: exactly the freed quantity.
        val repackWeight = BigDecimal("1.2")
        val repacked = packingService.pack(shipmentId, repackWeight, "CARTON")
        entityManager.clear()

        assertThat(repacked.state).isEqualTo(ShipmentState.PACKED.code)
        val unitsAfter = shippingUnitRepository.findByShipmentId(shipmentId)
        assertThat(unitsAfter).hasSize(2)

        val siblingLinesAfter = shippingUnitRepository.findLinesByUnitId(sibling.id!!)
        assertThat(siblingLinesAfter.map { it.id }).isEqualTo(siblingLinesBefore.map { it.id })
        // The surviving sibling's weight is untouched by the repack call -- it is not re-prorated
        // against the repack's weight, and definitely not re-summed with it.
        val siblingAfter = unitsAfter.single { it.id == sibling.id }
        assertThat(siblingAfter.weight).isEqualByComparingTo(siblingWeightBefore)

        val newUnit = unitsAfter.first { it.id != sibling.id }
        assertThat(newUnit.unitLoadId).isEqualTo(toRemove.unitLoadId)
        val newLines = shippingUnitRepository.findLinesByUnitId(newUnit.id!!)
        assertThat(newLines).hasSize(1)
        assertThat(newLines.single().sourcePickId).isEqualTo(freedPickId)
        assertThat(newLines.single().amount).isEqualByComparingTo(freedAmount)
        // The freed container is the ONLY sibling in this repack call (the other is fully
        // consumed and skipped), so proration's ratio is 1 -- the new unit's weight is EXACTLY
        // the repack call's weight, not a share of it and not the original 4.0 shipment total.
        assertThat(newUnit.weight).isEqualByComparingTo(repackWeight)
    }

    // --- IMPORTANT 5 (final-review fix wave): pause guard on removeUnit/removeLine ---

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses removeUnit`() {
        val shipmentId = packedShipmentId()
        val unit = packingService.unitsOf(shipmentId).first()
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { lifecycleService.removeUnit(shipmentId, unit.id!!) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused shipment refuses removeLine`() {
        val shipmentId = packedTwoLineShipmentId()
        val unit = packingService.unitsOf(shipmentId).first()
        val lineId = shippingUnitRepository.findLinesByUnitId(unit.id!!).first().id!!
        lifecycleService.pause(shipmentId)
        entityManager.clear()

        assertThatThrownBy { lifecycleService.removeLine(shipmentId, unit.id!!, lineId) }
            .isInstanceOf(FulfillmentException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `removeLine deletes only that line -- no stock change, sibling line and unit survive`() {
        val shipmentId = packedTwoLineShipmentId()
        val unit = packingService.unitsOf(shipmentId).first()
        val ulId = unit.unitLoadId!!
        val linesBefore = shippingUnitRepository.findLinesByUnitId(unit.id!!)
        assertThat(linesBefore).hasSize(2)
        val lineToRemove = linesBefore.first()
        val survivingLineId = linesBefore[1].id!!

        val updated = lifecycleService.removeLine(shipmentId, unit.id!!, lineToRemove.id!!)
        entityManager.clear()

        assertThat(updated.state).isEqualTo(ShipmentState.PACKED.code)
        assertThat(packingService.unitsOf(shipmentId)).hasSize(1)

        val remainingLines = shippingUnitRepository.findLinesByUnitId(unit.id!!)
        assertThat(remainingLines).hasSize(1)
        assertThat(remainingLines.single().id).isEqualTo(survivingLineId)

        val stockUnits = given().`when`().get("/api/v1/stock-units?unitLoadId=$ulId")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("content")
        assertThat(stockUnits).isNotEmpty
        stockUnits.forEach { su -> assertThat(su["state"] as Int).isEqualTo(650) }
    }

    /**
     * Repository-level check for [ShippingUnitRepository.findByUnitLoadIdOnLiveShipment] (Task 7's
     * "UL already on a live shipment" refusal reads this) -- the JPQL cross-entity query isn't
     * exercised by any Task 6 production path, so this pins it directly.
     */
    @Test
    @TestSecurity(user = "ff", roles = ["product-read", "product-write", "inventory-read", "inventory-write",
        "order-read", "order-write", "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findByUnitLoadIdOnLiveShipment finds the unit while live and stops finding it once canceled`() {
        val shipmentId = packedShipmentId()
        val unit = packingService.unitsOf(shipmentId).first()
        val ulId = unit.unitLoadId!!

        assertThat(shippingUnitRepository.findByUnitLoadIdOnLiveShipment(ulId, 1L)?.id).isEqualTo(unit.id)

        lifecycleService.cancel(shipmentId)
        entityManager.clear()

        assertThat(shippingUnitRepository.findByUnitLoadIdOnLiveShipment(ulId, 1L)).isNull()
    }
}
