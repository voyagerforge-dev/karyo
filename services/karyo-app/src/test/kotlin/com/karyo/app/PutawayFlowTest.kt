package com.karyo.app

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

private const val DOCK_LOCATION_ID = 900L
private const val DOCK_LOCATION_NAME = "DOCK-PUT"

/**
 * End-to-end integration test (REAL beans, no mocks) for the closing v1.2 loop:
 *
 *   seed product + layout (storage location)
 *     → receive stock (creates UL at the dock, fires GoodsReceiptLineReceivedEvent)
 *     → a PUTAWAY TransportOrder is auto-created RELEASED with a finder-chosen storage dest
 *     → assign → start → complete
 *     → the UL is now at the storage location (inventory), layout allocation updated,
 *       TransportOrderCompleted + state-change outbox rows written, reservation released.
 *
 * Plus: a QA-held received line creates NO putaway task.
 */
@QuarkusTest
class PutawayFlowTest {

    @Inject
    lateinit var outboxRepository: OutboxEventRepository

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    @Inject
    lateinit var unitLoadLookup: UnitLoadLookup

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Seeding helpers ──────────────────────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Putaway Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Closing-round resolver fallback tests: a product whose `defaultStorageStrategyId`
     * points at [defaultStrategyId] -- `resolveStrategy`'s rung-2 fallback source. */
    private fun createProductWithDefaultStrategy(number: String, itemUnitId: Long, defaultStrategyId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"number":"$number","name":"Putaway Product","itemUnitId":$itemUnitId,""" +
                    """"defaultStorageStrategyId":$defaultStrategyId}"""
            )
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createArea(name: String, usages: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["$usages"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Suppress("LongParameterList")
    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long? = null, clusterId: Long? = null): JsonPath {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        val clusterPart = clusterId?.let { ""","locationClusterId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart$clusterPart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath()
    }

    // ── F1 producer-path test helpers (locations-layout sprint area/strategy fixtures,
    // mirroring LocationFinderAreaTest's REST helpers of the same names) ──

    private fun createZone(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/zones")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStorageArea(name: String, clusterIds: List<Long>): Long {
        val ids = clusterIds.joinToString(",")
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":[$ids]}""")
            .`when`().post("/api/v1/storage-areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createStrategy(name: String, body: String = "{}"): Long {
        val extra = if (body == "{}") "" else ",${body.trim('{', '}')}"
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$extra}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun putStrategyAreas(strategyId: Long, orderedAreaIds: List<Long>) {
        val ids = orderedAreaIds.joinToString(",")
        given().contentType(ContentType.JSON)
            .body("[$ids]")
            .`when`().put("/api/v1/storage-strategies/$strategyId/areas")
            .then().statusCode(200)
    }

    /** Directly set base allocation (no REST setter) — mirrors the sprint's other finder test
     * siblings ([LocationFinderAreaTest.setAllocation] etc). */
    @Transactional
    fun setAllocation(id: Long, allocation: BigDecimal) {
        val loc = reservationRepository.getEntityManager().find(StorageLocation::class.java, id)
        loc.allocation = allocation
    }

    /** Direct entity persistence of a background ON_STOCK occupant — NOT the thing under
     * test (that's [unitLoadLookup]'s resolution of the REAL, REST-received INCOMING unit
     * load below); this is just the older same-product stock the FIFO hiding compares
     * against, mirroring [LocationFinderAreaTest.seedStock]'s identical pattern. */
    @Suppress("LongParameterList")
    @Transactional
    fun seedOlderStock(locationId: Long, locationName: String, itemDataId: Long, strategyDateValue: Instant): Long {
        val em = reservationRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-F1PP-OLD-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = locationName
            clientId = 1L
        }
        em.persist(ul)
        val su = StockUnit().apply {
            clientId = 1L
            this.itemDataId = itemDataId
            itemDataNumber = "F1PP-OLD-SKU"
            amount = BigDecimal.TEN
            unitLoad = ul
            state = 300
            strategyDate = strategyDateValue
        }
        em.persist(su)
        return su.id!!
    }

    private fun createBlindReceipt(receiptType: Int? = null): Long {
        val typeField = receiptType?.let { ""","receiptType":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL"$typeField}""")
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** [lockType] = inventory LockType code (e.g. QUALITY_FAULT 103); null = no lock. */
    private fun receiveLine(receiptId: Long, productId: Long, amount: Double, lockType: Int?, label: String): JsonPath {
        val lockField = lockType?.let { """"lockType":$it,""" } ?: ""
        val body = """{"itemDataId":$productId,"amount":$amount,""" +
            """"locationId":$DOCK_LOCATION_ID,"locationName":"$DOCK_LOCATION_NAME",""" +
            """"unitLoadLabel":"$label",$lockField"allowOverReceipt":false}"""
        return given().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(201).extract().jsonPath()
    }

    private fun finishReceipt(receiptId: Long) {
        given().`when`().post("/api/v1/goods-receipts/$receiptId/finish").then().statusCode(200)
    }

    private fun listTasks(state: Int? = null): JsonPath {
        val q = state?.let { "?state=$it" } ?: ""
        return given().`when`().get("/api/v1/transport-orders$q")
            .then().statusCode(200).extract().jsonPath()
    }

    private fun getTask(id: Long): JsonPath =
        given().`when`().get("/api/v1/transport-orders/$id")
            .then().statusCode(200).extract().jsonPath()

    private fun getLocation(id: Long): JsonPath =
        given().`when`().get("/api/v1/locations/$id")
            .then().statusCode(200).extract().jsonPath()

    private fun getUnitLoad(id: Long): JsonPath =
        given().`when`().get("/api/v1/unit-loads/$id")
            .then().statusCode(200).extract().jsonPath()

    private fun getStockUnit(id: Long): JsonPath =
        given().`when`().get("/api/v1/stock-units/$id")
            .then().statusCode(200).extract().jsonPath()

    private fun completedOutboxCount(taskId: Long): Long =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "TransportOrder", taskId, "TransportOrderCompleted",
        ).count()

    private fun stateChangeOutboxCount(taskId: Long): Long =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "TransportOrder", taskId, "TransportOrderStateChanged",
        ).count()

    private fun findTaskIdForLabel(label: String): Long {
        val tasks = listTasks().getList<Map<String, Any>>("content")
        val match = tasks.first { it["unitLoadLabel"] == label }
        return (match["id"] as Number).toLong()
    }

    private fun reverseLine(receiptId: Long, lineId: Long, expectedStatus: Int = 200): JsonPath =
        given()
            .`when`().delete("/api/v1/goods-receipts/$receiptId/lines/$lineId")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun getReceipt(receiptId: Long): JsonPath =
        given().`when`().get("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200).extract().jsonPath()

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `receive to putaway complete - UL ends at finder-chosen storage with allocation + outbox + reservation released`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PU-${s.toString().takeLast(10)}")
        val product = createProduct("PUT-SKU-$s", itemUnit)

        val storageArea = createArea("PUT-ST-$s", "STORAGE")
        val type = createLocationType("PUT-LT-$s")
        val storage = createLocation("PUT-LOC-$s", type, storageArea)
        val storageId = storage.getLong("id")
        val storageName = storage.getString("name")
        assertThat(storage.getDouble("allocation")).isEqualTo(0.0)

        // Receive (blind) → UL at the dock, fires the line-received event → auto putaway.
        val label = "UL-PUT-$s"
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        val unitLoadId = received.getLong("unitLoadId")
        finishReceipt(receiptId)

        // A PUTAWAY task was auto-created, RELEASED, suggesting the seeded storage location.
        val taskId = findTaskIdForLabel(label)
        getTask(taskId).let {
            assertThat(it.getString("transportType")).isEqualTo("PUTAWAY")
            assertThat(it.getInt("state")).isEqualTo(100) // RELEASED
            assertThat(it.getLong("sourceLocationId")).isEqualTo(DOCK_LOCATION_ID)
            assertThat(it.getLong("suggestedLocationId")).isEqualTo(storageId)
            assertThat(it.getString("suggestedLocationName")).isEqualTo(storageName)
        }

        // The finder soft-reserved the suggested location.
        assertThat(reservationRepository.findByTransportOrderId(taskId)).hasSize(1)

        // assign → start → complete (accept the suggestion).
        given().contentType(ContentType.JSON).body("""{"operatorId":"op-1"}""")
            .`when`().post("/api/v1/transport-orders/$taskId/assign").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(400))
        given().`when`().post("/api/v1/transport-orders/$taskId/start").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(500))
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/v1/transport-orders/$taskId/complete").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(700)) // FINISHED
            .body("destinationLocationId", org.hamcrest.CoreMatchers.`is`(storageId.toInt()))

        // UL is now physically at the storage location (inventory).
        assertThat(getUnitLoad(unitLoadId).getLong("storageLocationId")).isEqualTo(storageId)

        // Layout allocation updated at the destination (+100% per UL).
        assertThat(getLocation(storageId).getDouble("allocation")).isEqualTo(100.0)

        // Outbox: completion + several state-change rows.
        assertThat(completedOutboxCount(taskId)).isEqualTo(1)
        assertThat(stateChangeOutboxCount(taskId)).isGreaterThanOrEqualTo(4) // CREATED,RELEASED,RESERVED,STARTED,FINISHED

        // Reservation released on completion.
        assertThat(reservationRepository.findByTransportOrderId(taskId)).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `QA-held received line creates no putaway task`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PUQ-${s.toString().takeLast(10)}")
        val product = createProduct("PUTQ-SKU-$s", itemUnit)
        val storageArea = createArea("PUTQ-ST-$s", "STORAGE")
        val type = createLocationType("PUTQ-LT-$s")
        createLocation("PUTQ-LOC-$s", type, storageArea)

        val label = "UL-PUTQ-$s"
        val receiptId = createBlindReceipt()
        receiveLine(receiptId, product, amount = 5.0, lockType = 103, label = label)
        finishReceipt(receiptId)

        // No task references the QA-held unit load.
        val tasks = listTasks().getList<Map<String, Any>>("content")
        assertThat(tasks.none { it["unitLoadLabel"] == label }).isTrue()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `RETOUR line without explicit lock defaults to QA hold and creates no putaway task`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PUR-${s.toString().takeLast(10)}")
        val product = createProduct("PUTR-SKU-$s", itemUnit)
        val storageArea = createArea("PUTR-ST-$s", "STORAGE")
        val type = createLocationType("PUTR-LT-$s")
        createLocation("PUTR-LOC-$s", type, storageArea)

        // RETOUR receipt (receiptType 1): a line received with NO lockType defaults to
        // QUALITY_FAULT(103) at the service — the derived qaHold then skips putaway
        // exactly like an explicit QA hold, with zero putaway-side changes.
        val label = "UL-PUTR-$s"
        val receiptId = createBlindReceipt(receiptType = 1)
        receiveLine(receiptId, product, amount = 3.0, lockType = null, label = label)
        finishReceipt(receiptId)

        // No task references the return's unit load.
        val tasks = listTasks().getList<Map<String, Any>>("content")
        assertThat(tasks.none { it["unitLoadLabel"] == label }).isTrue()
    }

    // ── B3: reversing a received line cancels its pending putaway task ───

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reversing a line cancels its pending putaway task in the same transaction`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PUC-${s.toString().takeLast(10)}")
        val product = createProduct("PUTC-SKU-$s", itemUnit)
        val storageArea = createArea("PUTC-ST-$s", "STORAGE")
        val type = createLocationType("PUTC-LT-$s")
        createLocation("PUTC-LOC-$s", type, storageArea)

        val label = "UL-PUTC-$s"
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        val stockUnitId = received.getLong("stockUnitId")
        val grLineId = received.getLong("lineId")

        // Auto-putaway created the task RELEASED with a reservation.
        val taskId = findTaskIdForLabel(label)
        assertThat(getTask(taskId).getInt("state")).isEqualTo(100) // RELEASED
        assertThat(reservationRepository.findByTransportOrderId(taskId)).hasSize(1)

        // Reverse the line -> same transaction cancels the task and releases its reservation.
        val reversed = reverseLine(receiptId, grLineId)
        assertThat(reversed.getBoolean("lines[0].reversed")).isTrue()

        assertThat(getTask(taskId).getInt("state")).isEqualTo(800) // CANCELED
        assertThat(reservationRepository.findByTransportOrderId(taskId)).isEmpty()
        assertThat(getStockUnit(stockUnitId).getInt("state")).isEqualTo(1000) // DELETABLE
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reversal refuses when the putaway task is already STARTED`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PUS-${s.toString().takeLast(10)}")
        val product = createProduct("PUTS-SKU-$s", itemUnit)
        val storageArea = createArea("PUTS-ST-$s", "STORAGE")
        val type = createLocationType("PUTS-LT-$s")
        createLocation("PUTS-LOC-$s", type, storageArea)

        val label = "UL-PUTS-$s"
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        val stockUnitId = received.getLong("stockUnitId")
        val grLineId = received.getLong("lineId")

        val taskId = findTaskIdForLabel(label)
        given().contentType(ContentType.JSON).body("""{"operatorId":"op-1"}""")
            .`when`().post("/api/v1/transport-orders/$taskId/assign").then().statusCode(200)
        given().`when`().post("/api/v1/transport-orders/$taskId/start").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(500)) // STARTED

        // Reversal refuses -- the whole transaction rolls back: line stays unreversed,
        // stock is NOT deleted, and the STARTED task is untouched.
        reverseLine(receiptId, grLineId, expectedStatus = 409)

        assertThat(getReceipt(receiptId).getBoolean("lines[0].reversed")).isFalse()
        assertThat(getStockUnit(stockUnitId).getInt("state")).isEqualTo(100) // still INCOMING
        assertThat(getTask(taskId).getInt("state")).isEqualTo(500) // still STARTED
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reversal succeeds when the putaway task already FINISHED - myWMS permits undo after putaway`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PUF-${s.toString().takeLast(10)}")
        val product = createProduct("PUTF-SKU-$s", itemUnit)
        val storageArea = createArea("PUTF-ST-$s", "STORAGE")
        val type = createLocationType("PUTF-LT-$s")
        val storage = createLocation("PUTF-LOC-$s", type, storageArea)
        val storageId = storage.getLong("id")

        val label = "UL-PUTF-$s"
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        val stockUnitId = received.getLong("stockUnitId")
        val grLineId = received.getLong("lineId")

        // Drive the task to FINISHED via the full lifecycle.
        val taskId = findTaskIdForLabel(label)
        given().contentType(ContentType.JSON).body("""{"operatorId":"op-1"}""")
            .`when`().post("/api/v1/transport-orders/$taskId/assign").then().statusCode(200)
        given().`when`().post("/api/v1/transport-orders/$taskId/start").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(500)) // STARTED
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/v1/transport-orders/$taskId/complete").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(700)) // FINISHED

        // Reversal succeeds despite the task being FINISHED.
        val reversed = reverseLine(receiptId, grLineId)
        assertThat(reversed.getBoolean("lines[0].reversed")).isTrue()

        // Stock is reversed to DELETABLE; task remains FINISHED (no state rewrite).
        assertThat(getStockUnit(stockUnitId).getInt("state")).isEqualTo(1000) // DELETABLE
        assertThat(getTask(taskId).getInt("state")).isEqualTo(700) // task still FINISHED
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reversal succeeds when the putaway task was itself CANCELED`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("PUX-${s.toString().takeLast(10)}")
        val product = createProduct("PUTX-SKU-$s", itemUnit)
        val storageArea = createArea("PUTX-ST-$s", "STORAGE")
        val type = createLocationType("PUTX-LT-$s")
        createLocation("PUTX-LOC-$s", type, storageArea)

        val label = "UL-PUTX-$s"
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        val stockUnitId = received.getLong("stockUnitId")
        val grLineId = received.getLong("lineId")

        val taskId = findTaskIdForLabel(label)
        // Cancel the task directly (pre-STARTED, so it accepts the cancel).
        given().contentType(ContentType.JSON).body("""{"reason":"Testing"}""")
            .`when`().post("/api/v1/transport-orders/$taskId/cancel").then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(800)) // CANCELED

        // Reversal succeeds despite the task being CANCELED.
        val reversed = reverseLine(receiptId, grLineId)
        assertThat(reversed.getBoolean("lines[0].reversed")).isTrue()

        // Stock is reversed to DELETABLE; task remains CANCELED.
        assertThat(getStockUnit(stockUnitId).getInt("state")).isEqualTo(1000) // DELETABLE
        assertThat(getTask(taskId).getInt("state")).isEqualTo(800) // task still CANCELED
    }

    // ── F1 (final review): producer-path regression for DefaultUnitLoadLookup ───────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `F1 - UnitLoadLookup resolves itemDataId+strategyDate from a REAL receipt's INCOMING stock, at the exact moment auto-putaway calls it`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("F1-${s.toString().takeLast(10)}")
        val product = createProduct("F1-SKU-$s", itemUnit)
        val storageArea = createArea("F1-ST-$s", "STORAGE")
        val type = createLocationType("F1-LT-$s")
        createLocation("F1-LOC-$s", type, storageArea)

        // Receive (blind) -> UL created at the dock, still INCOMING. The AFTER_SUCCESS
        // observer (TaskService.onGoodsReceiptLineReceived) has ALREADY fired synchronously
        // by the time this REST call returns, and called unitLoadLookup.findById at exactly
        // this moment (stock INCOMING -- finishReceipt/markOnStock, which promotes it to
        // ON_STOCK, has not run yet). We deliberately do NOT call finishReceipt here so the
        // state the test observes below is identical to what the real observer already saw.
        val label = "UL-F1-$s"
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        val unitLoadId = received.getLong("unitLoadId")

        assertThat(getStockUnit(received.getLong("stockUnitId")).getInt("state")).isEqualTo(100) // still INCOMING

        // Before the F1 fix, DefaultUnitLoadLookup.findById filtered to ON_STOCK-only stock
        // and saw ZERO rows here -- itemDataId/strategyDate resolved to null/null, silently
        // disabling useAreaStrategyDate/useItemDataArea/nearPickingLocation for the sprint's
        // primary production caller. This is a RED-FIRST assertion against the unfixed code.
        tenantContext.clientId = 1L
        val info = unitLoadLookup.findById(unitLoadId)
        assertThat(info).isNotNull
        assertThat(info!!.itemDataId).isEqualTo(product)
        assertThat(info.strategyDate).isNotNull()

        // The real auto-putaway observer already used exactly this (now-fixed) lookup to
        // build its LocationFinderRequest -- the created PUTAWAY task is confirmation the
        // producer path actually ran, not a separately-constructed scenario.
        val taskId = findTaskIdForLabel(label)
        assertThat(getTask(taskId).getString("transportType")).isEqualTo("PUTAWAY")
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `F1 - useAreaStrategyDate cross-area FIFO hiding engages off UnitLoadLookup's REAL INCOMING-state resolution`() {
        val s = System.nanoTime()
        val storage = createArea("F1PP-ST-$s", "STORAGE")
        val zone = createZone("F1PP-Z-$s")
        val type = createLocationType("F1PP-LT-$s")
        val clusterA = createLocationCluster("F1PP-CA-$s")
        val clusterB = createLocationCluster("F1PP-CB-$s")
        val areaA = createStorageArea("F1PP-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("F1PP-AB-$s", listOf(clusterB))

        val locAOldName = "F1PP-A-OLD-$s"
        val locAOld = createLocation(locAOldName, type, storage, zoneId = zone, clusterId = clusterA).getLong("id")
        setAllocation(locAOld, BigDecimal("100")) // already-occupied pallet slot, not a candidate itself
        val locAEmpty = createLocation("F1PP-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA).getLong("id")
        createLocation("F1PP-B-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterB)

        val itemUnit = createItemUnit("F1PP-${s.toString().takeLast(10)}")
        val product = createProduct("F1PP-SKU-$s", itemUnit)
        // Background occupant: older same-product stock already on hand in area A (this part
        // is fixture setup, not what's under test -- mirrors LocationFinderAreaTest.seedStock).
        seedOlderStock(locAOld, locAOldName, product, Instant.parse("2020-01-01T00:00:00Z"))

        val strategyId = createStrategy("F1PP-STRAT-$s", """{"useAreaStrategyDate":true}""")
        putStrategyAreas(strategyId, listOf(areaA, areaB)) // A first, B second

        // The incoming stock UNDER TEST: received via the real goods-receipt REST flow and
        // left INCOMING (not finished) -- reproducing the exact production trigger/timing for
        // TaskService.onGoodsReceiptLineReceived. bestBefore is left unset, so strategyDate
        // defaults to "now" (StockService.createStock) -- newer than the 2020 background stock.
        val receiptId = createBlindReceipt()
        val received = receiveLine(receiptId, product, amount = 5.0, lockType = null, label = "UL-F1PP-$s")
        val unitLoadId = received.getLong("unitLoadId")
        assertThat(getStockUnit(received.getLong("stockUnitId")).getInt("state")).isEqualTo(100) // still INCOMING

        tenantContext.clientId = 1L
        val info = unitLoadLookup.findById(unitLoadId)
        assertThat(info).isNotNull
        assertThat(info!!.itemDataId).isEqualTo(product) // the F1 regression: this was null pre-fix
        assertThat(info.strategyDate).isNotNull()

        // The REAL auto-putaway observer (TaskService.onGoodsReceiptLineReceived) already ran
        // synchronously off the receiveLine() call above and created its own PUTAWAY task --
        // but since TaskService does not thread a storageStrategyId through (a separate,
        // not-yet-wired gap this fix round does not touch), its own finder call ran with NO
        // strategy at all: unscoped by zone or area, it can soft-reserve ANY globally-emptiest
        // STORAGE location -- including this test's own locAEmpty, which would then race
        // against the manual, strategy-aware call below over the exact same location. Cancel
        // it first (releases its reservation) so the manual call below is the only thing
        // holding a reservation on this test's locations.
        val autoTaskId = findTaskIdForLabel("UL-F1PP-$s")
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/v1/transport-orders/$autoTaskId/cancel").then().statusCode(200)

        // Feed the REAL resolved values into the finder exactly as TaskService.findLocation
        // does (reservationKey/unitLoadTypeId/weight/itemDataId/strategyDate all sourced from
        // the same UnitLoadInfo) plus a storage strategy id (see above) -- this call
        // demonstrates the finder-level effect of the F1 fix directly. Area B (after area A,
        // which holds older same-product stock) must be hidden.
        val result = locationFinder.findPutawayLocation(
            LocationFinderRequest(
                unitLoadId = info.id,
                unitLoadTypeId = info.unitLoadTypeId,
                weight = info.weight,
                clientId = 1L,
                reservationKey = s,
                storageStrategyId = strategyId,
                preferredZoneId = zone,
                itemDataId = info.itemDataId,
                strategyDate = info.strategyDate,
            ),
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locAEmpty)
    }

    // ── Closing round: LocationFinderService.resolveStrategy's ItemData-default fallback ──
    // (final-review "RULING" item 3 -- converts L1/L6 from "shipped, pending activation" to
    // simply "shipped": TaskService.findLocation never sets storageStrategyId, so the ONLY
    // way a real auto-putaway call can see a StorageStrategy at all is this fallback.)

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `resolveStrategy falls back to the product default strategy on the REAL putaway path - manualSearch engages, no explicit id`() {
        val s = System.nanoTime()

        // A manualSearch strategy is the cleanest observable probe: tryFindPutawayLocation
        // short-circuits to NoLocation BEFORE any candidate query, purely off whichever
        // strategy resolveStrategy hands it. Since TaskService never sets storageStrategyId,
        // this engaging at all is proof the fallback ran on the real observer path.
        val strategyId = createStrategy("RSF-STRAT-$s", """{"manualSearch":true}""")
        val itemUnit = createItemUnit("RSF-${s.toString().takeLast(10)}")
        val product = createProductWithDefaultStrategy("RSF-SKU-$s", itemUnit, strategyId)

        val storageArea = createArea("RSF-ST-$s", "STORAGE")
        val type = createLocationType("RSF-LT-$s")
        // A perfectly good candidate exists -- it must NOT be suggested, proving manualSearch
        // (sourced only from the product default) actually took effect.
        createLocation("RSF-LOC-$s", type, storageArea)

        val label = "UL-RSF-$s"
        val receiptId = createBlindReceipt()
        receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        finishReceipt(receiptId)

        val taskId = findTaskIdForLabel(label)
        getTask(taskId).let {
            assertThat(it.getInt("state")).isEqualTo(50) // CREATED -- NOT RELEASED, no suggestion made
            assertThat(it.getString("note")).contains("manualSearch")
            assertThat(it.get<Any?>("suggestedLocationId")).isNull()
        }
        // The finder never even ran a candidate query, so nothing is reserved.
        assertThat(reservationRepository.findByTransportOrderId(taskId)).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "task-read", "task-write", "MANAGER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `resolveStrategy fallback is a no-op when the product has no default storage strategy - regression pin`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("RSN-${s.toString().takeLast(10)}")
        val product = createProduct("RSN-SKU-$s", itemUnit) // no defaultStorageStrategyId at all

        val storageArea = createArea("RSN-ST-$s", "STORAGE")
        val type = createLocationType("RSN-LT-$s")
        val storage = createLocation("RSN-LOC-$s", type, storageArea)
        val storageId = storage.getLong("id")

        val label = "UL-RSN-$s"
        val receiptId = createBlindReceipt()
        receiveLine(receiptId, product, amount = 10.0, lockType = null, label = label)
        finishReceipt(receiptId)

        // Unaffected: no strategy resolves at all (rung 2 also returns null) -- same
        // no-strategy auto-putaway behavior as before this closing round.
        val taskId = findTaskIdForLabel(label)
        getTask(taskId).let {
            assertThat(it.getInt("state")).isEqualTo(100) // RELEASED
            assertThat(it.getLong("suggestedLocationId")).isEqualTo(storageId)
        }
        assertThat(reservationRepository.findByTransportOrderId(taskId)).hasSize(1)
    }
}
