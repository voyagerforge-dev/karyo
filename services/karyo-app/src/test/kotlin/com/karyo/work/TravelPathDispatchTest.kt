package com.karyo.work

import com.karyo.layout.spi.LocationLockPort
import com.karyo.security.TenantContext
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.domain.model.CountSession
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.service.StrictPriorityDispatchStrategy
import com.karyo.work.service.TravelPathDispatchStrategy
import com.karyo.work.service.WorkDispatchService
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Plain unit coverage for [TravelPathDispatchStrategy]/[StrictPriorityDispatchStrategy].order()
 * (St6, stocktaking-block sprint Task 8) — no Quarkus context needed, mirrors
 * [WorkDispatchServiceTest]'s directly-constructed style. The config-knob selection end-to-end
 * check lives in [TravelPathDispatchConfigTest] below — it needs its own `@QuarkusTest` +
 * `@TestProfile` app restart, so it cannot share this class (`@TestProfile` is class-scoped).
 */
class TravelPathDispatchTest {

    private fun item(id: Long, travelOrder: Int?, prio: Int = 50, age: Long = 100) = WorkItem(
        ref = WorkRef(WorkType.COUNT, id), workType = WorkType.COUNT, priority = prio, state = WorkState.OPEN,
        claimedBy = null, zone = null, primaryLocation = "L$id", destination = null,
        summary = "COUNT $id", createdAt = Instant.ofEpochSecond(age), travelOrder = travelOrder,
    )

    @Test
    fun `TRAVEL_PATH orders items by travelOrder, nulls last`() {
        // shuffled travelOrder 3, 1, null -- must come out 1, 3, null
        val items = listOf(item(1, travelOrder = 3), item(2, travelOrder = 1), item(3, travelOrder = null))

        val ordered = TravelPathDispatchStrategy().order(items)

        assertThat(ordered.map { it.ref.sourceId }).containsExactly(2L, 1L, 3L)
    }

    @Test
    fun `TRAVEL_PATH falls back to priority DESC then createdAt ASC among equal travelOrder, including null-vs-null`() {
        val items = listOf(
            item(1, travelOrder = null, prio = 50, age = 20),
            item(2, travelOrder = null, prio = 90, age = 10),
            item(3, travelOrder = 5, prio = 10, age = 5),
        )

        val ordered = TravelPathDispatchStrategy().order(items)

        // id 3 has the only real travelOrder -> first; among the two nulls, prio 90 beats 50
        assertThat(ordered.map { it.ref.sourceId }).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `STRICT_PRIORITY (default) ignores travelOrder entirely -- createdAt order regression`() {
        val items = listOf(
            item(1, travelOrder = 3, age = 10),
            item(2, travelOrder = 1, age = 20),
            item(3, travelOrder = null, age = 30),
        )

        val ordered = StrictPriorityDispatchStrategy().order(items)

        // equal priority (50, the item() default) for all three -> tiebreak is createdAt ASC,
        // i.e. the input order itself; travelOrder plays no part.
        assertThat(ordered.map { it.ref.sourceId }).containsExactly(1L, 2L, 3L)
    }
}

/**
 * Config-knob selection (St6): with `karyo.work.dispatch-strategy=TRAVEL_PATH` set via
 * [TravelPathActive] (mirrors `LicenseServiceTest`'s `@TestProfile`/`getConfigOverrides()`
 * pattern), the real CDI-wired [WorkDispatchService] must pick [TravelPathDispatchStrategy]
 * over the default [StrictPriorityDispatchStrategy] — proven by seeding two real COUNT work
 * items (via [CountWorkProvider][com.karyo.stocktaking.messaging.CountWorkProvider], which
 * populates `travelOrder` from a real [LocationLockPort.orderIndexFor] read) whose
 * orderIndex-derived `travelOrder` DISAGREES with their `createdAt` order, then asserting the
 * dispatch pool comes back in `travelOrder`, not insertion, order.
 */
@QuarkusTest
@TestProfile(TravelPathDispatchConfigTest.TravelPathActive::class)
class TravelPathDispatchConfigTest {

    class TravelPathActive : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("karyo.work.dispatch-strategy" to "TRAVEL_PATH")
    }

    @Inject lateinit var dispatchService: WorkDispatchService
    @Inject lateinit var countOrderRepo: CountOrderRepository
    @Inject lateinit var countSessionRepo: CountSessionRepository
    @Inject lateinit var tenantContext: TenantContext

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

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long, orderIndex: Int): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId,"orderIndex":$orderIndex}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Transactional
    fun seedGeneratedCountOrder(clientId: Long, suffix: String, locationId: Long, locationName: String): Long {
        val session = CountSession().apply {
            this.clientId = clientId
            sessionNumber = "CS-TP-$suffix"
            state = CountSessionState.OPEN.code
        }
        countSessionRepo.persist(session)
        val order = CountOrder().apply {
            this.clientId = clientId
            sessionId = session.id!!
            orderNumber = "CO-TP-$suffix"
            this.locationId = locationId
            this.locationName = locationName
            state = CountOrderState.GENERATED.code
        }
        countOrderRepo.persist(order)
        return order.id!!
    }

    @Test
    @TestSecurity(user = "tp-op", roles = ["inventory-read", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8500")])
    fun `TRAVEL_PATH active via the config knob dispatches in orderIndex order, not createdAt order`() {
        val suffix = System.nanoTime().toString()
        val ltId = createLocationType("LT-TP-$suffix")
        val areaId = createArea("AREA-TP-$suffix")

        // B seeded FIRST (earlier createdAt -- STRICT_PRIORITY would put it first) but with the
        // HIGHER orderIndex; A seeded SECOND but with the LOWER orderIndex. TRAVEL_PATH must
        // invert insertion order to A-then-B.
        tenantContext.clientId = 8500L
        val locNameB = "LOC-TP-B-$suffix"
        val locBId = createLocation(locNameB, ltId, areaId, orderIndex = 20)
        val orderBId = seedGeneratedCountOrder(8500L, "B-$suffix", locBId, locNameB)

        val locNameA = "LOC-TP-A-$suffix"
        val locAId = createLocation(locNameA, ltId, areaId, orderIndex = 10)
        val orderAId = seedGeneratedCountOrder(8500L, "A-$suffix", locAId, locNameA)

        val refs = dispatchService.available("tp-op", 8500L, WorkFilter(setOf(WorkType.COUNT))).map { it.ref }

        assertThat(refs.indexOf(WorkRef(WorkType.COUNT, orderAId)))
            .isLessThan(refs.indexOf(WorkRef(WorkType.COUNT, orderBId)))
    }
}
