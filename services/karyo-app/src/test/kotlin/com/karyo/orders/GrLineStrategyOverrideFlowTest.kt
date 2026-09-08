package com.karyo.orders

import com.karyo.layout.domain.model.StorageStrategy
import com.karyo.layout.repository.StorageStrategyRepository
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.repository.TransportOrderRepository
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
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

private const val GRS_DOCK_LOCATION_ID = 900L
private const val GRS_DOCK_LOCATION_NAME = "DOCK-GRS"

/**
 * Integration test (REAL beans, no mocks) for the per-receipt-line storage strategy
 * override (inbound-completion row 7 residual): `ReceiveLineRequest.storageStrategyId`
 * threads request -> `GoodsReceiptLine` (V425) -> `GoodsReceiptLineReceivedEvent` ->
 * `TransportOrder` (V503, PERSISTED) -> `LocationFinderRequest.storageStrategyId` at
 * BOTH auto-putaway call sites (`TaskService.createPutawayTask` and
 * `TaskService.reResolveSuggestion`, which replays on `POST /transport-orders/{id}/start`
 * when no suggestion exists yet).
 *
 * Templates: [PutawayFlowTest]'s rung-2 fallback tests (zone-honoring assertion shape) and
 * `LocationFinderStrategyOwnershipTest` (direct-entity foreign-strategy seeding).
 */
@QuarkusTest
class GrLineStrategyOverrideFlowTest {

    @Inject
    lateinit var strategyRepository: StorageStrategyRepository

    @Inject
    lateinit var transportOrderRepository: TransportOrderRepository

    // ── Seeding helpers (mirrors PutawayFlowTest's REST helpers of the same names) ────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"GR Strategy Override Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createArea(name: String, usage: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["$usage"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createZone(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/zones")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Suppress("LongParameterList")
    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long? = null): JsonPath {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath()
    }

    private fun createStrategy(name: String, body: String = "{}"): Long {
        val extra = if (body == "{}") "" else ",${body.trim('{', '}')}"
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$extra}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** A strategy owned by a *foreign* client — REST always stamps the caller's own
     *  clientId, so a genuinely foreign row can only be seeded by direct entity persist
     *  (mirrors LocationFinderStrategyOwnershipTest's identical helper). */
    @Transactional
    fun createForeignStrategy(name: String, ownerClientId: Long): Long {
        val entity = StorageStrategy().apply {
            this.name = name
            this.clientId = ownerClientId
        }
        strategyRepository.persist(entity)
        return entity.id!!
    }

    /** Forces a task straight to RESERVED, bypassing assign() (which requires RELEASED --
     *  unreachable for a no-suggestion CREATED task, exactly the scenario /start's
     *  re-resolve path exists for). start() only checks state == RESERVED, not provenance. */
    @Transactional
    fun forceReserved(taskId: Long) {
        val order = transportOrderRepository.findById(taskId)!!
        order.state = OrderState.RESERVED.code
    }

    private fun createBlindReceipt(): Long =
        given().contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL"}""")
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Suppress("LongParameterList")
    private fun receiveLine(
        receiptId: Long,
        productId: Long,
        amount: Double,
        label: String,
        storageStrategyId: Long? = null,
        expectedStatus: Int = 201,
    ): JsonPath {
        val strategyField = storageStrategyId?.let { ""","storageStrategyId":$it""" } ?: ""
        val body = """{"itemDataId":$productId,"amount":$amount,""" +
            """"locationId":$GRS_DOCK_LOCATION_ID,"locationName":"$GRS_DOCK_LOCATION_NAME",""" +
            """"unitLoadLabel":"$label","allowOverReceipt":false$strategyField}"""
        return given().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(expectedStatus).extract().jsonPath()
    }

    private fun finishReceipt(receiptId: Long) {
        given().`when`().post("/api/v1/goods-receipts/$receiptId/finish").then().statusCode(200)
    }

    /** size=200 + newest-first: the shared test DB accumulates tasks from every OTHER
     *  test class in the same run, so an unbounded/default-paged/oldest-first query can
     *  miss a task this test JUST created (the default page-0/size-20 sort is oldest-first
     *  by `created`). Our task is always among the most recent, so newest-first is safe. */
    private fun listTasks(): JsonPath =
        given().`when`().get("/api/v1/transport-orders?size=200&sort=created,desc")
            .then().statusCode(200).extract().jsonPath()

    private fun getTask(id: Long): JsonPath =
        given().`when`().get("/api/v1/transport-orders/$id")
            .then().statusCode(200).extract().jsonPath()

    private fun findTaskIdForLabel(label: String): Long {
        val tasks = listTasks().getList<Map<String, Any>>("content")
        val match = tasks.first { it["unitLoadLabel"] == label }
        return (match["id"] as Number).toLong()
    }

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
    fun `a - valid zone-constrained storageStrategyId on the receive line - putaway suggestion honors the zone`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("GRSA-${s.toString().takeLast(10)}")
        val product = createProduct("GRSA-SKU-$s", itemUnit)
        val storageArea = createArea("GRSA-ST-$s", "STORAGE")
        val type = createLocationType("GRSA-LT-$s")

        // Created FIRST (lower id) -- would win an UNCONSTRAINED search, so the assertion
        // below is genuinely fix-dependent, not an accident of insertion order.
        createLocation("GRSA-OUT-$s", type, storageArea)

        val zone = createZone("GRSA-Z-$s")
        val insideLoc = createLocation("GRSA-IN-$s", type, storageArea, zoneId = zone)
        val insideLocId = insideLoc.getLong("id")

        val strategyId = createStrategy("GRSA-STRAT-$s", """{"zoneId":$zone}""")

        val label = "UL-GRSA-$s"
        val receiptId = createBlindReceipt()
        receiveLine(receiptId, product, amount = 10.0, label = label, storageStrategyId = strategyId)
        finishReceipt(receiptId)

        val taskId = findTaskIdForLabel(label)
        getTask(taskId).let {
            assertThat(it.getInt("state")).isEqualTo(100) // RELEASED
            assertThat(it.getLong("suggestedLocationId")).isEqualTo(insideLocId)
        }
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
    fun `b - unknown storageStrategyId on the receive line is rejected with 422`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("GRSB-${s.toString().takeLast(10)}")
        val product = createProduct("GRSB-SKU-$s", itemUnit)
        val receiptId = createBlindReceipt()

        receiveLine(
            receiptId, product, amount = 5.0, label = "UL-GRSB-$s",
            storageStrategyId = 987_654_321L, expectedStatus = 422,
        )
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
    fun `c - foreign-tenant storageStrategyId on the receive line is rejected with 422`() {
        val s = System.nanoTime()
        val clientB = s // per-test unique foreign client id
        val foreignStrategyId = createForeignStrategy("GRSC-STRAT-$s", clientB)

        val itemUnit = createItemUnit("GRSC-${s.toString().takeLast(10)}")
        val product = createProduct("GRSC-SKU-$s", itemUnit)
        val receiptId = createBlindReceipt()

        receiveLine(
            receiptId, product, amount = 5.0, label = "UL-GRSC-$s",
            storageStrategyId = foreignStrategyId, expectedStatus = 422,
        )
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
    fun `d - re-resolve on start replays the persisted storage strategy override - the persisted-id trap`() {
        val s = System.nanoTime()
        val itemUnit = createItemUnit("GRSD-${s.toString().takeLast(10)}")
        val product = createProduct("GRSD-SKU-$s", itemUnit)
        val storageArea = createArea("GRSD-ST-$s", "STORAGE")
        val type = createLocationType("GRSD-LT-$s")

        // Zone starts EMPTY -- the strategy-constrained search at auto-putaway time finds
        // nothing at all.
        val zone = createZone("GRSD-Z-$s")
        val strategyId = createStrategy("GRSD-STRAT-$s", """{"zoneId":$zone}""")

        val label = "UL-GRSD-$s"
        val receiptId = createBlindReceipt()
        receiveLine(receiptId, product, amount = 10.0, label = label, storageStrategyId = strategyId)
        finishReceipt(receiptId)

        val taskId = findTaskIdForLabel(label)
        getTask(taskId).let {
            assertThat(it.getInt("state")).isEqualTo(50) // CREATED -- no suggestion, zone was empty
            assertThat(it.get<Any?>("suggestedLocationId")).isNull()
        }

        // Add a location into the SAME (per-test-unique) zone -- now eligible.
        val newLoc = createLocation("GRSD-LOC-$s", type, storageArea, zoneId = zone)
        val newLocId = newLoc.getLong("id")

        // No REST path moves a no-suggestion CREATED task to RESERVED (assign() requires
        // RELEASED) -- direct entity manipulation, mirrors the ownership test's pattern.
        forceReserved(taskId)

        given().`when`().post("/api/v1/transport-orders/$taskId/start")
            .then().statusCode(200)
            .body("state", `is`(500)) // STARTED
            .body("suggestedLocationId", `is`(newLocId.toInt()))
    }
}
