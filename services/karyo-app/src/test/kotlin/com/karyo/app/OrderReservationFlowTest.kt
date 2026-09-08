package com.karyo.app

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.orders.repository.OrderLineReservationRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val FLOW_ROLES_USER = "manager"

/**
 * Integration test (REAL beans, no mocks) for the order -> reservation wiring:
 * orders module -> StockReserver SPI -> inventory 13-pass selection ->
 * reservedAmount math -> OrderLineReservation bookkeeping -> outbox feed.
 */
@QuarkusTest
class OrderReservationFlowTest {

    @Inject
    lateinit var reservationRepository: OrderLineReservationRepository

    @Inject
    lateinit var outboxRepository: OutboxEventRepository

    // ── REST seeding helpers (existing services) ─────────────────────────

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Order Flow Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double): Long =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun stockReservedAmount(stockUnitId: Long): Double =
        given()
            .`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("reservedAmount")

    private fun createOrder(itemDataId: Long, amount: Double): ValidatableResponse =
        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"Flow Test Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)

    private fun seedProductWithStock(suffix: Long, stockAmount: Double): Triple<Long, Long, String> {
        val itemUnitId = createItemUnit("OU-${suffix.toString().takeLast(10)}")
        val number = "ORD-SKU-$suffix"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-ORD-$suffix")
        val stockUnitId = createStock(unitLoadId, productId, number, stockAmount)
        return Triple(productId, stockUnitId, number)
    }

    private fun outboxStateChangeCount(orderId: Long): Long =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "DeliveryOrder", orderId, "DeliveryOrderStateChanged",
        ).count()

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release reserves stock, cancel returns it`() {
        val suffix = System.nanoTime()
        val (productId, stockUnitId, _) = seedProductWithStock(suffix, stockAmount = 100.0)

        // Create: order CREATED(50), line CREATED(50)
        val create = createOrder(productId, amount = 60.0)
        val orderId = create.extract().jsonPath().getLong("id")
        assertThat(create.extract().jsonPath().getInt("state")).isEqualTo(50)
        assertThat(create.extract().jsonPath().getInt("lines[0].state")).isEqualTo(50)

        // Release: full coverage -> line PROCESSABLE(300), order PROCESSABLE(300), no shortages
        val release = given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
        val releaseJson = release.extract().jsonPath()
        assertThat(releaseJson.getInt("order.state")).isEqualTo(300)
        assertThat(releaseJson.getInt("order.lines[0].state")).isEqualTo(300)
        assertThat(releaseJson.getDouble("order.lines[0].reservedAmount")).isEqualTo(60.0)
        assertThat(releaseJson.getList<Any>("shortages")).isEmpty()

        // Per-line reservation breakdown rows exist
        val lineId = releaseJson.getLong("order.lines[0].id")
        val slices = reservationRepository.findByLineId(lineId)
        assertThat(slices).isNotEmpty
        assertThat(slices.sumOf { it.amount.toDouble() }).isEqualTo(60.0)
        assertThat(slices.first().stockUnitId).isEqualTo(stockUnitId)

        // StockUnit.reservedAmount incremented by the inventory module
        assertThat(stockReservedAmount(stockUnitId)).isEqualTo(60.0)

        // Outbox feed: UNDEFINED->CREATED, CREATED->RELEASED, RELEASED->PROCESSABLE
        assertThat(outboxStateChangeCount(orderId)).isEqualTo(3)

        // Cancel: reservations released exactly, rows gone, states CANCELED(800)
        given()
            .`when`().post("/api/v1/delivery-orders/$orderId/cancel")
            .then().statusCode(200)
            .extract().jsonPath().let {
                assertThat(it.getInt("state")).isEqualTo(800)
                assertThat(it.getInt("lines[0].state")).isEqualTo(800)
                assertThat(it.getDouble("lines[0].reservedAmount")).isEqualTo(0.0)
            }
        assertThat(stockReservedAmount(stockUnitId)).isEqualTo(0.0)
        assertThat(reservationRepository.findByLineId(lineId)).isEmpty()
        assertThat(outboxStateChangeCount(orderId)).isEqualTo(4)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create echoes line externalNumber and hints, update null-merges a single hint`() {
        val suffix = System.nanoTime()
        val (productId, _, _) = seedProductWithStock(suffix, stockAmount = 10.0)

        // Create with a line-level externalNumber + all three operator hints (real wiring, no mocks)
        val create = given()
            .contentType(ContentType.JSON)
            .body(
                """{"customerName":"Hints Customer","pickingHint":"Fragile - pick last",""" +
                    """"packingHint":"Double-box","shippingHint":"Liftgate required",""" +
                    """"lines":[{"itemDataId":$productId,"amount":2.0,"externalNumber":"EXT-LINE-$suffix"}]}"""
            )
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)
        val createJson = create.extract().jsonPath()
        val orderId = createJson.getLong("id")
        assertThat(createJson.getString("pickingHint")).isEqualTo("Fragile - pick last")
        assertThat(createJson.getString("packingHint")).isEqualTo("Double-box")
        assertThat(createJson.getString("shippingHint")).isEqualTo("Liftgate required")
        assertThat(createJson.getString("lines[0].externalNumber")).isEqualTo("EXT-LINE-$suffix")

        // Update changes ONE hint; the ?.let null-merge leaves omitted fields untouched
        val update = given()
            .contentType(ContentType.JSON)
            .body("""{"packingHint":"Use branded tape"}""")
            .`when`().put("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
        val updateJson = update.extract().jsonPath()
        assertThat(updateJson.getString("packingHint")).isEqualTo("Use branded tape")
        assertThat(updateJson.getString("pickingHint")).isEqualTo("Fragile - pick last")
        assertThat(updateJson.getString("shippingHint")).isEqualTo("Liftgate required")

        // Persistence check: re-read from the database
        val readJson = given()
            .`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200)
            .extract().jsonPath()
        assertThat(readJson.getString("packingHint")).isEqualTo("Use branded tape")
        assertThat(readJson.getString("pickingHint")).isEqualTo("Fragile - pick last")
        assertThat(readJson.getString("shippingHint")).isEqualTo("Liftgate required")
        assertThat(readJson.getString("lines[0].externalNumber")).isEqualTo("EXT-LINE-$suffix")
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `omitted line externalNumber and hints stay null`() {
        val suffix = System.nanoTime()
        val (productId, _, _) = seedProductWithStock(suffix, stockAmount = 10.0)

        val createJson = createOrder(productId, amount = 2.0).extract().jsonPath()
        assertThat(createJson.getString("pickingHint")).isNull()
        assertThat(createJson.getString("packingHint")).isNull()
        assertThat(createJson.getString("shippingHint")).isNull()
        assertThat(createJson.getString("lines[0].externalNumber")).isNull()
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `shortage leaves line PENDING, retry-reservation completes it`() {
        val suffix = System.nanoTime()
        val (productId, firstStockUnitId, number) = seedProductWithStock(suffix, stockAmount = 50.0)

        val orderId = createOrder(productId, amount = 80.0).extract().jsonPath().getLong("id")

        // Release with shortage: line PENDING(550), order stays RELEASED(100), shortfall 30
        val release = given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
        val releaseJson = release.extract().jsonPath()
        assertThat(releaseJson.getInt("order.state")).isEqualTo(100)
        assertThat(releaseJson.getInt("order.lines[0].state")).isEqualTo(550)
        assertThat(releaseJson.getDouble("order.lines[0].reservedAmount")).isEqualTo(50.0)
        assertThat(releaseJson.getList<Any>("shortages")).hasSize(1)
        assertThat(releaseJson.getDouble("shortages[0].shortfall")).isEqualTo(30.0)
        assertThat(stockReservedAmount(firstStockUnitId)).isEqualTo(50.0)

        // Stock arrives: second stock unit covers the remainder
        val secondUnitLoadId = createUnitLoad("UL-ORD2-$suffix")
        val secondStockUnitId = createStock(secondUnitLoadId, productId, number, 40.0)

        // Retry: the sanctioned PENDING(550)->PROCESSABLE(300) hop; order promotes to PROCESSABLE
        val retry = given()
            .`when`().post("/api/v1/delivery-orders/$orderId/retry-reservation")
            .then().statusCode(200)
        val retryJson = retry.extract().jsonPath()
        assertThat(retryJson.getInt("order.state")).isEqualTo(300)
        assertThat(retryJson.getInt("order.lines[0].state")).isEqualTo(300)
        assertThat(retryJson.getDouble("order.lines[0].reservedAmount")).isEqualTo(80.0)
        assertThat(retryJson.getList<Any>("shortages")).isEmpty()
        assertThat(stockReservedAmount(secondStockUnitId)).isEqualTo(30.0)

        // Cancel releases both slices back
        given()
            .`when`().post("/api/v1/delivery-orders/$orderId/cancel")
            .then().statusCode(200)
        assertThat(stockReservedAmount(firstStockUnitId)).isEqualTo(0.0)
        assertThat(stockReservedAmount(secondStockUnitId)).isEqualTo(0.0)
    }
}
