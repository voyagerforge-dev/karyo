package com.karyo.stocktaking

import com.karyo.inventory.service.StockService
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.service.StocktakingService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test for `GET /api/v1/count-sessions` (defect-burndown row 8): the endpoint used
 * to return every session for the tenant with its full nested orders→lines graph -- 1+S+O
 * queries, VIEWER-reachable, a warehouse-scale OOM risk. This pins the replacement: a paginated
 * [com.karyo.stocktaking.dto.CountSessionSummaryView] projection (order-state counts only).
 * `GET /count-sessions/{id}` keeps returning the full graph -- covered by [StocktakingResourceTest]
 * and friends, not here.
 *
 * clientId 2951 reserved for this suite.
 */
@QuarkusTest
class ListSessionsTest {

    @Inject
    lateinit var service: StocktakingService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var stockService: StockService

    // ── Seeding helpers (mirrored from StocktakingResourceTest/FullInventoryTest) ─────────────

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, locationId: Long, locationName: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"$locationName"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates an ON_STOCK stock unit. Item-unit name suffix ≤8 chars (field max=20). */
    private fun createStock(ulId: Long, amount: Double): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2951001,"itemDataNumber":"LSS-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    // ── Test ─────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2951"), Claim(key = "tenant_code", value = "LSS-TEST")])
    fun `GET count-sessions returns the paginated summary projection, not the nested graph`() {
        tenantContext.clientId = 2951
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LSS-$ns")
        val areaId = createArea("AREA-LSS-$ns")

        // Session A: END_OF_PERIOD on a single reserved location -> the whole scope is skipped,
        // so this session ends up with 0 orders (the degenerate full-inventory case, closed
        // immediately -- see StocktakingService.startCount's KDoc). Started FIRST, before any
        // other location exists for this client, so END_OF_PERIOD's whole-warehouse scope can't
        // pick up session B's locations too.
        val reservedName = "LSS-RESV-$ns"
        val reservedId = createLocation(reservedName, ltId, areaId)
        val reservedUl = createUnitLoad("UL-LSS-R-$ns", reservedId, reservedName)
        val reservedStockId = createStock(reservedUl, 10.0)
        stockService.reserveStock(reservedStockId, BigDecimal.valueOf(3.0), "TEST-RESERVE", tenantContext)

        val emptySession = service.startCount(StartCountRequest(type = "END_OF_PERIOD"), 2951)
        assertThat(emptySession.orderCount).isEqualTo(0)

        // Session B: 3 fresh locations -> one order left GENERATED, one COUNTED (mismatch), one
        // FINISHED (exact-match auto-finish).
        val loc1 = createLocation("LSS-L1-$ns", ltId, areaId)
        createStock(createUnitLoad("UL-LSS-1-$ns", loc1, "LSS-L1-$ns"), 5.0)
        val loc2 = createLocation("LSS-L2-$ns", ltId, areaId)
        createStock(createUnitLoad("UL-LSS-2-$ns", loc2, "LSS-L2-$ns"), 8.0)
        val loc3 = createLocation("LSS-L3-$ns", ltId, areaId)
        createStock(createUnitLoad("UL-LSS-3-$ns", loc3, "LSS-L3-$ns"), 4.0)

        val started = service.startCount(StartCountRequest(locationIds = listOf(loc1, loc2, loc3)), 2951)
        val graph = service.getSession(started.id, 2951)
        val orderByLocation = graph.orders.associateBy { it.locationId }

        val order2 = orderByLocation.getValue(loc2)
        service.submitCount(
            order2.id,
            listOf(CountInput(order2.lines.first().id, order2.lines.first().plannedAmount.add(BigDecimal.ONE))),
            2951,
        )
        val order3 = orderByLocation.getValue(loc3)
        service.submitCount(
            order3.id,
            listOf(CountInput(order3.lines.first().id, order3.lines.first().plannedAmount)),
            2951,
        )

        val json = given()
            .`when`().get("/api/v1/count-sessions?page=0&size=20")
            .then().statusCode(200)
            .body("content[0].orders", nullValue())
            .extract().jsonPath()

        assertThat(json.getInt("page.totalElements")).isEqualTo(2)
        val content = json.getList<Map<String, Any>>("content")
        assertThat(content).hasSize(2)

        val emptyRow = content.first { (it["id"] as Number).toLong() == emptySession.id }
        assertThat((emptyRow["orderCount"] as Number).toInt()).isEqualTo(0)
        assertThat((emptyRow["countedCount"] as Number).toInt()).isEqualTo(0)
        assertThat((emptyRow["finishedCount"] as Number).toInt()).isEqualTo(0)

        val bRow = content.first { (it["id"] as Number).toLong() == started.id }
        assertThat((bRow["orderCount"] as Number).toInt()).isEqualTo(3)
        assertThat((bRow["countedCount"] as Number).toInt()).isEqualTo(1)
        assertThat((bRow["finishedCount"] as Number).toInt()).isEqualTo(1)
        assertThat(bRow).doesNotContainKey("orders")
    }
}
