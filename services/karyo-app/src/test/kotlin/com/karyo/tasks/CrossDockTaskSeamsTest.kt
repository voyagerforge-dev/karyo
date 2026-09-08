package com.karyo.tasks

import com.karyo.orders.event.GoodsReceiptLineReceivedEvent
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.spi.CrossDockLookup
import com.karyo.tasks.spi.CrossDockTaskCommand
import com.karyo.tasks.spi.PutawayFromStagingCommand
import com.karyo.tasks.spi.TransportOrderPort
import com.karyo.tasks.vo.TransportType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only [CrossDockLookup] implementation. Task 5 (the paid crossdock engine) has not
 * landed yet, so this is the ONLY implementation of the interface anywhere on the test
 * classpath -- it is therefore the sole bean [com.karyo.tasks.service.TaskService]'s
 * `Instance<CrossDockLookup>` ever resolves to for the whole suite. Default-false (nothing
 * intercepted) so every OTHER test's auto-putaway flow ([com.karyo.app.PutawayFlowTest] et al.)
 * is completely unaffected; a test opts a specific `goodsReceiptLineId` in via [intercept].
 */
@ApplicationScoped
class TestCrossDockLookup : CrossDockLookup {
    private val intercepted = ConcurrentHashMap.newKeySet<Long>()

    fun intercept(goodsReceiptLineId: Long) {
        intercepted.add(goodsReceiptLineId)
    }

    override fun existsForReceiptLine(goodsReceiptLineId: Long): Boolean = goodsReceiptLineId in intercepted
}

/**
 * Task 3 (cross-docking sprint): the tasks-side seams -- [TransportType.CROSS_DOCK],
 * [TransportOrderPort.createCrossDock]/[TransportOrderPort.createPutawayFromStaging], and the
 * putaway-observer guard consuming [CrossDockLookup].
 *
 * UL seeding mirrors [TransportOrderPortTest]: POST /api/v1/unit-loads for clientId=1 (the JWT
 * tenant), placed at a dummy location -- tasks only needs label + locationId/Name from
 * [com.karyo.inventory.api.spi.UnitLoadInfo]. `unitLoadTypeId=1` is the V105-seeded default type.
 *
 * TenantContext note (same as [TransportOrderPortTest] and
 * [com.karyo.app.PutawayFlowTest]'s F1 tests): `TenantContext` is `@RequestScoped` and is
 * naturally active during a `@QuarkusTest` test method's own execution, so injecting it and
 * setting `clientId` directly before a bare CDI call (port method or manual event fire) makes
 * the ambient-tenant reads downstream see the right tenant -- no HTTP round trip required.
 */
@QuarkusTest
class CrossDockTaskSeamsTest {

    @Inject
    lateinit var port: TransportOrderPort

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var repository: TransportOrderRepository

    @Inject
    lateinit var crossDockLookup: TestCrossDockLookup

    @Inject
    lateinit var lineReceivedEvent: Event<GoodsReceiptLineReceivedEvent>

    private fun seedUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":100,"storageLocationName":"RESERVE-A"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── location-finder fixtures (mirrors com.karyo.app.PutawayFlowTest's helpers of the
    // same names) -- only [createPutawayFromStaging]'s "resolved by the finder" test needs a
    // real, empty storage location to guarantee a deterministic Found result. ─────────────────

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

    private fun createLocation(name: String, typeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `createCrossDock mints a RELEASED CROSS_DOCK transport with the staging destination`() {
        val s = System.nanoTime()
        val ulId = seedUnitLoad("UL-XD-PORT-$s")
        tenantContext.clientId = 1L

        val ref = port.createCrossDock(
            CrossDockTaskCommand(
                clientId = 1,
                unitLoadId = ulId,
                destinationLocationId = 777L,
                destinationLocationName = "XD-STAGE-01",
                goodsReceiptLineId = s,
            ),
        )

        assertThat(ref.orderNumber).startsWith("XD-")
        assertThat(ref.state).isEqualTo(OrderState.RELEASED.code)

        val order = repository.findByIdAndClient(ref.id, 1L)
        assertThat(order).isNotNull
        assertThat(order!!.transportType).isEqualTo(TransportType.CROSS_DOCK)
        assertThat(order.state).isEqualTo(OrderState.RELEASED.code)
        assertThat(order.destinationLocationId).isEqualTo(777L)
        assertThat(order.destinationLocationName).isEqualTo("XD-STAGE-01")
        assertThat(order.goodsReceiptLineId).isEqualTo(s)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `createPutawayFromStaging mints a PUTAWAY transport resolved by the location finder`() {
        val s = System.nanoTime()
        // Guarantees AT LEAST one empty, compatible candidate exists for the finder even when
        // this class runs in isolation (a fresh DB, no other test class's fixtures seeded yet).
        // Sibling `com.karyo.tasks.*` tests running in the SAME shared dev-services DB (see
        // OrderPurgeBlockerNettingTest's KDoc on this suite's shared-state idiom) may also leave
        // behind compatible empty locations, so the finder is free to pick ANY of them -- the
        // assertion below checks resolution SUCCEEDED (Found, not NoLocation), not that it
        // picked THIS specific location.
        val storageArea = createArea("XD-ST-$s", "STORAGE")
        val locationType = createLocationType("XD-LT-$s")
        createLocation("XD-LOC-$s", locationType, storageArea)

        val ulId = seedUnitLoad("UL-XD-STAGE-$s")
        tenantContext.clientId = 1L

        val ref = port.createPutawayFromStaging(PutawayFromStagingCommand(clientId = 1, unitLoadId = ulId))

        assertThat(ref.orderNumber).startsWith("TO-")
        assertThat(ref.state).isEqualTo(OrderState.RELEASED.code)

        val order = repository.findByIdAndClient(ref.id, 1L)
        assertThat(order).isNotNull
        assertThat(order!!.transportType).isEqualTo(TransportType.PUTAWAY)
        assertThat(order.state).isEqualTo(OrderState.RELEASED.code)
        assertThat(order.suggestedLocationId).isNotNull()
        assertThat(order.suggestedLocationName).isNotNull()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `putaway observer skips a line the cross-dock engine intercepted`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L

        // Line the (fake, paid-module-standin) cross-dock engine already claimed. The guard
        // runs right after the qaHold check, BEFORE unitLoadLookup -- so a nonexistent
        // unitLoadId is safe here, the observer must never reach it.
        val interceptedLineId = s
        crossDockLookup.intercept(interceptedLineId)

        lineReceivedEvent.fire(
            GoodsReceiptLineReceivedEvent(
                goodsReceiptId = s,
                goodsReceiptLineId = interceptedLineId,
                asnId = null,
                itemDataId = -1,
                amount = BigDecimal.TEN,
                stockUnitId = -1,
                unitLoadId = -1,
                unitLoadLabel = "UL-XD-INTERCEPTED-$s",
                locationId = 900,
                locationName = "DOCK-XD",
                qaHold = false,
                clientId = 1,
                occurredAt = Instant.now(),
            ),
        )

        assertThat(repository.findByGoodsReceiptLineId(interceptedLineId))
            .`as`("the cross-dock engine already owns this line -- no putaway task should be minted")
            .isNull()

        // A second line the lookup does NOT know about: a real, resolvable unit load, and the
        // observer proceeds exactly as it always has -- a PUTAWAY task is minted.
        val notInterceptedLineId = s + 1
        val ulId = seedUnitLoad("UL-XD-NOTINTERCEPTED-$s")

        lineReceivedEvent.fire(
            GoodsReceiptLineReceivedEvent(
                goodsReceiptId = s,
                goodsReceiptLineId = notInterceptedLineId,
                asnId = null,
                itemDataId = -1,
                amount = BigDecimal.TEN,
                stockUnitId = -1,
                unitLoadId = ulId,
                unitLoadLabel = "UL-XD-NOTINTERCEPTED-$s",
                locationId = 900,
                locationName = "DOCK-XD",
                qaHold = false,
                clientId = 1,
                occurredAt = Instant.now(),
            ),
        )

        val minted = repository.findByGoodsReceiptLineId(notInterceptedLineId)
        assertThat(minted)
            .`as`("the lookup returned false for this line -- the ordinary auto-putaway path must still run")
            .isNotNull
        assertThat(minted!!.transportType).isEqualTo(TransportType.PUTAWAY)
    }
}
