package com.karyo.work

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.security.TenantContext
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/**
 * REST integration test for [com.karyo.work.api.v1.WorkInboxResource].
 *
 * Verifies:
 * 1. Unauthenticated GET /available → 401.
 * 2. Create group + add member via admin endpoints; GET /groups shows the group.
 * 3. POST /next on an empty pool (tenant 8399 has no open work) → 204.
 * 4. `?workingAreaId=` (L5, locations-layout sprint Task 8 CONTINGENT filter): in-area vs
 *    out-of-area location-bearing items, location-less items excluded, unknown id → 400,
 *    omitted param → unchanged behavior.
 *
 * clientId 8300 / 8301 / 8399 reserved for this suite.
 * Reuses inventory-read / inventory-write realm roles (no Keycloak --reset-db needed).
 */
@QuarkusTest
class WorkInboxResourceTest {

    @Inject lateinit var countOrderRepo: CountOrderRepository
    @Inject lateinit var countSessionRepo: CountSessionRepository
    @Inject lateinit var pickOrderRepo: PickOrderRepository
    @Inject lateinit var tenantContext: TenantContext

    @Test
    fun `available requires auth`() {
        given().`when`().get("/api/v1/work/available").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-write", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8300")])
    fun `create group, add member, list shows it`() {
        val groupId = given().contentType(ContentType.JSON)
            .body("""{"name":"Pickers-8300","workTypes":["PICK"]}""")
            .`when`().post("/api/v1/work/groups")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given().contentType(ContentType.JSON).body("""{"operatorId":"alice"}""")
            .`when`().post("/api/v1/work/groups/$groupId/members")
            .then().statusCode(204)

        given().`when`().get("/api/v1/work/groups")
            .then().statusCode(200)
            .body("find { it.name == 'Pickers-8300' }.workTypes", org.hamcrest.Matchers.contains("PICK"))
    }

    @Test
    @TestSecurity(user = "loner", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8399")])
    fun `next on an empty eligible pool returns 204`() {
        // clientId 8399 has no open work items in any provider — pool is empty → 204
        given().`when`().post("/api/v1/work/next").then().statusCode(204)
    }

    @Test
    @TestSecurity(user = "loner", roles = ["inventory-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8399")])
    fun `claim with malformed ref returns 400`() {
        // "BADREF" has no colon — WorkRef.parse throws IllegalArgumentException → 400
        given().`when`().post("/api/v1/work/BADREF/claim").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "loner", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8399")])
    fun `available with invalid type returns 400`() {
        // "NOTATYPE" is not a valid WorkType — valueOf throws IllegalArgumentException → 400
        given().queryParam("type", "NOTATYPE").`when`().get("/api/v1/work/available").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-write", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8300")])
    fun `createGroup with bogus workType returns 400`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"BogusGroup","workTypes":["BOGUS"]}""")
            .`when`().post("/api/v1/work/groups")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-write", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8300")])
    fun `createGroup with empty workTypes returns 400`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"EmptyGroup","workTypes":[]}""")
            .`when`().post("/api/v1/work/groups")
            .then().statusCode(400)
    }

    // ── ?workingAreaId= (L5, locations-layout sprint Task 8) ──────────────────────────

    private fun ns() = System.nanoTime()

    private fun createCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

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

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long, clusterId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId,"locationClusterId":$clusterId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createWorkingArea(name: String, clusterIds: List<Long>): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":${clusterIds}}""")
            .`when`().post("/api/v1/working-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Transactional
    fun seedCountOrder(clientId: Long, suffix: String, locationId: Long): Long {
        val session = CountSession().apply {
            this.clientId = clientId
            sessionNumber = "CS-WA-$suffix"
            state = CountSessionState.OPEN.code
        }
        countSessionRepo.persist(session)
        val order = CountOrder().apply {
            this.clientId = clientId
            sessionId = session.id!!
            orderNumber = "CO-WA-$suffix"
            this.locationId = locationId
            locationName = "LOC-$suffix"
            state = CountOrderState.GENERATED.code
        }
        countOrderRepo.persist(order)
        return order.id!!
    }

    @Transactional
    fun seedPickOrder(clientId: Long, suffix: String): Long {
        val o = PickOrder().apply {
            this.clientId = clientId
            pickOrderNumber = "PK-WA-$suffix"
            deliveryOrderId = 0L
            deliveryOrderNumber = "DO-WA-$suffix"
            state = PickState.RELEASED.code
            prio = 50
        }
        pickOrderRepo.persist(o)
        return o.id!!
    }

    /**
     * Seeds cluster-in/cluster-out locations + a working area over the "in" cluster, plus
     * one COUNT order at each location and one location-less PICK order.
     */
    private data class WorkingAreaFixture(
        val workingAreaId: Long, val inRef: String, val outRef: String, val pickRef: String,
    )

    private fun seedWorkingAreaFixture(clientId: Long): WorkingAreaFixture {
        val s = ns()
        val clusterIn = createCluster("WA-WORK-CL-IN-$s")
        val clusterOut = createCluster("WA-WORK-CL-OUT-$s")
        val ltId = createLocationType("WA-WORK-LT-$s")
        val areaId = createArea("WA-WORK-AREA-$s")
        val locIn = createLocation("WA-WORK-LOC-IN-$s", ltId, areaId, clusterIn)
        val locOut = createLocation("WA-WORK-LOC-OUT-$s", ltId, areaId, clusterOut)
        val workingAreaId = createWorkingArea("WA-WORK-WA-$s", listOf(clusterIn))

        tenantContext.clientId = clientId // prime @RequestScoped context for direct repo seeding below
        val inId = seedCountOrder(clientId, "IN-$s", locIn)
        val outId = seedCountOrder(clientId, "OUT-$s", locOut)
        val pickId = seedPickOrder(clientId, "$s")

        return WorkingAreaFixture(workingAreaId, "COUNT:$inId", "COUNT:$outId", "PICK:$pickId")
    }

    @Test
    @TestSecurity(user = "wa-op", roles = ["inventory-read", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8301")])
    fun `available with workingAreaId includes in-area item and excludes out-of-area and location-less items`() {
        val fx = seedWorkingAreaFixture(8301L)

        given().queryParam("workingAreaId", fx.workingAreaId)
            .`when`().get("/api/v1/work/available")
            .then().statusCode(200)
            .body("ref", hasItem(fx.inRef))
            .body("ref", not(hasItem(fx.outRef)))
            .body("ref", not(hasItem(fx.pickRef)))
    }

    @Test
    @TestSecurity(user = "wa-op", roles = ["inventory-read", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8301")])
    fun `available with unknown workingAreaId returns 400`() {
        seedWorkingAreaFixture(8301L) // establishes the tenant's pool; irrelevant to this assertion
        val unknownId = ns()

        given().queryParam("workingAreaId", unknownId)
            .`when`().get("/api/v1/work/available")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "wa-op", roles = ["inventory-read", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8301")])
    fun `available without workingAreaId is unaffected (regression pin)`() {
        val fx = seedWorkingAreaFixture(8301L)

        given()
            .`when`().get("/api/v1/work/available")
            .then().statusCode(200)
            .body("ref", hasItem(fx.inRef))
            .body("ref", hasItem(fx.outRef))
            .body("ref", hasItem(fx.pickRef))
    }
}
