package com.karyo.inventory

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.spi.StockCountingPort
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.UnitLoadMover
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.service.StockService
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountInput
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.service.StocktakingService
import com.karyo.stocktaking.vo.CountOrderState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Guards defect row 3 (2026-08-02 burndown): a terminated unit load -- hard delete
 * ([com.karyo.inventory.service.UnitLoadService.delete]) or the stocktaking emptied-UL soft flip
 * ([com.karyo.inventory.service.DefaultStockCountingPort.applyCount]) -- now releases its
 * [StorageLocation][com.karyo.layout.domain.model.StorageLocation] `allocation` (was a permanent
 * +100 leak) and leaves a journal/outbox trace, via `UnitLoadTrashedEvent` observed synchronously
 * by layout-core's `UnitLoadTrashedObserver`.
 *
 * Extended for task-10 (defect-burndown-4, rows 14+15): row 15 -- deleting a carrier still
 * carrying a child now 409s `has-dependents` instead of a raw FK 500. Row 14 -- every producer
 * that can drain a unit load to empty on an occupied location now fires the same trio via
 * [com.karyo.inventory.service.UnitLoadTerminator.trashIfEmpty] (`transferStock` merge-drain,
 * `deleteStock`, `shipContainer`), and a DELETABLE unit load now refuses new stock as a
 * transfer target (409), closing the gap the (d) idempotency test above exercised via a
 * repository-level bypass.
 *
 * clientId 4501-4512 reserved for this suite.
 */
@QuarkusTest
class UnitLoadTerminationTest {

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    @Inject
    lateinit var stocktakingService: StocktakingService

    @Inject
    lateinit var countingPort: StockCountingPort

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var unitLoadMover: UnitLoadMover

    // ── seed helpers (mirrored from UnitLoadTransferCdiFlowTest / UnitLoadMissingTest) ────────

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun getAllocation(locationId: Long): Double =
        given().`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("allocation")

    private fun createUnitLoad(label: String, locationId: Long, locationName: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"$locationName"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun transferUnitLoad(ulId: Long, destinationId: Long, destinationName: String) {
        given().contentType(ContentType.JSON)
            .body("""{"destinationLocationId":$destinationId,"destinationLocationName":"$destinationName"}""")
            .`when`().post("/api/v1/unit-loads/$ulId/transfer")
            .then().statusCode(200)
    }

    /** Creates an ON_STOCK stock unit. Item-unit name suffix ≤8 chars (max=20 field). */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double, prefix: String): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$prefix-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun unitLoadStateOf(unitLoadId: Long): Int =
        given().`when`().get("/api/v1/unit-loads/$unitLoadId")
            .then().statusCode(200).extract().jsonPath().getInt("state")

    private fun setCarrier(ulId: Long, isCarrier: Boolean) {
        given().contentType(ContentType.JSON)
            .body("""{"isCarrier":$isCarrier}""")
            .`when`().post("/api/v1/unit-loads/$ulId/carrier")
            .then().statusCode(200)
    }

    private fun nestOnCarrier(childId: Long, carrierId: Long) {
        given().contentType(ContentType.JSON)
            .body("""{"carrierUnitLoadId":$carrierId}""")
            .`when`().post("/api/v1/unit-loads/$childId/transfer-to-carrier")
            .then().statusCode(200)
    }

    // ── (a) hard delete releases allocation + outbox + journal ─────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4501"), Claim(key = "tenant_code", value = "ULT-TEST")])
    fun `hard delete releases location allocation and leaves outbox and journal rows`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULT-$ns")
        val areaId = createArea("AREA-ULT-$ns")
        val sourceName = "SRC-ULT-$ns"
        val destName = "DST-ULT-$ns"
        val sourceId = createLocation(sourceName, ltId, areaId)
        val destinationId = createLocation(destName, ltId, areaId)

        val label = "UL-ULT-$ns"
        val ulId = createUnitLoad(label, sourceId, sourceName)
        transferUnitLoad(ulId, destinationId, destName)
        assertThat(getAllocation(destinationId)).isEqualTo(100.0)

        given().`when`().delete("/api/v1/unit-loads/$ulId")
            .then().statusCode(204)

        assertThat(getAllocation(destinationId))
            .`as`("hard delete must release the destination location's allocation")
            .isEqualTo(0.0)

        val outboxRow = outboxEvents.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", ulId, "UnitLoadTrashed",
        ).firstResult()
        assertThat(outboxRow)
            .`as`("hard delete must publish a UnitLoadTrashed outbox row")
            .isNotNull
        assertThat(outboxRow!!.tenantId)
            .`as`("outbox tenantId must be the unit load's own owner")
            .isEqualTo(4501L)

        val journalRows = journalRepository.count(
            "fromUnitLoad = ?1 and clientId = ?2 and recordType = ?3",
            label, 4501L, JournalRecordType.DELETED.code,
        )
        assertThat(journalRows)
            .`as`("hard delete must journal a DELETED row for the unit load")
            .isEqualTo(1L)
    }

    // ── (b) delete-with-stock still 409s ────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator2", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4502"), Claim(key = "tenant_code", value = "ULT-TEST2")])
    fun `hard delete with live stock still 409s has-dependents`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTB-$ns")
        val areaId = createArea("AREA-ULTB-$ns")
        val locName = "LOC-ULTB-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-ULTB-$ns", locationId, locName)
        createStock(ulId, 4502001L, 5.0, "ULTB")

        given().`when`().delete("/api/v1/unit-loads/$ulId")
            .then().statusCode(409)
            .body("type", equalTo("https://karyo.com/errors/has-dependents"))
    }

    // ── (row 15) deleting a carrier with a nested child 409s, not a raw FK 500 ─────────────

    @Test
    @TestSecurity(user = "operator15", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4507"), Claim(key = "tenant_code", value = "ULT-TEST15")])
    fun `deleting a carrier with a nested child 409s has-dependents instead of a raw FK 500`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTF-$ns")
        val areaId = createArea("AREA-ULTF-$ns")
        val locName = "LOC-ULTF-$ns"
        val locationId = createLocation(locName, ltId, areaId)

        val carrierId = createUnitLoad("UL-ULTF-CARRIER-$ns", locationId, locName)
        setCarrier(carrierId, isCarrier = true)
        val childId = createUnitLoad("UL-ULTF-CHILD-$ns", locationId, locName)
        nestOnCarrier(childId, carrierId)

        given().`when`().delete("/api/v1/unit-loads/$carrierId")
            .then().statusCode(409)
            .body("type", equalTo("https://karyo.com/errors/has-dependents"))
    }

    // ── (c) count-flip path releases allocation too ─────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator3", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4503"), Claim(key = "tenant_code", value = "ULT-TEST3")])
    fun `zero-counting the last stock unit flips the UL DELETABLE and releases location allocation`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTC-$ns")
        val areaId = createArea("AREA-ULTC-$ns")
        val holdName = "HOLD-ULTC-$ns"
        val targetName = "TGT-ULTC-$ns"
        val holdId = createLocation(holdName, ltId, areaId)
        val targetId = createLocation(targetName, ltId, areaId)

        val ulId = createUnitLoad("UL-ULTC-$ns", holdId, holdName)
        transferUnitLoad(ulId, targetId, targetName)
        assertThat(getAllocation(targetId)).isEqualTo(100.0)

        createStock(ulId, 4503001L, 5.0, "ULTC")

        tenantContext.clientId = 4503L
        val started = stocktakingService.startCount(StartCountRequest(locationIds = listOf(targetId)), 4503L)
        val sessionView = stocktakingService.getSession(started.id, 4503L)
        val order = sessionView.orders.first()
        val line = order.lines.first()

        stocktakingService.submitCount(
            orderId = order.id,
            inputs = listOf(CountInput(lineId = line.id, countedAmount = BigDecimal.ZERO)),
            clientId = 4503L,
        )
        val counted = stocktakingService.orderView(order.id, 4503L)
        assertThat(counted.state).isEqualTo(CountOrderState.COUNTED.code)

        stocktakingService.accept(order.id, 4503L)

        assertThat(unitLoadStateOf(ulId)).isEqualTo(1000)

        assertThat(getAllocation(targetId))
            .`as`("the count-flip must release the location allocation too, not just mark the UL terminal")
            .isEqualTo(0.0)
    }

    // ── (d) idempotency: re-zeroing stock landed on an already-DELETABLE UL ────────────────

    /**
     * Lands a new stock unit directly on [unitLoadId] via repositories, bypassing REST and
     * bypassing `StockService.unitLoadForWrite`'s DELETABLE-target guard (task-10,
     * defect-burndown-4, row 14 -- `transferStock`/`transferToLocation` now both refuse a
     * DELETABLE target UL with a 409; see the "transferStock onto a DELETABLE unit load is
     * refused" test below). Used here only to reproduce a stock unit riding an
     * already-DELETABLE UL cheaply, without a second write path through REST.
     */
    @Transactional
    fun landStockOnUnitLoad(unitLoadId: Long, ownerClientId: Long, amount: Double, suffix: String): Long {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        val su = StockUnit().apply {
            this.clientId = ownerClientId
            this.itemDataId = 4504002L
            this.itemDataNumber = "ULTD2-$suffix"
            this.amount = BigDecimal.valueOf(amount)
            this.state = StockState.ON_STOCK.code
            this.unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return su.id!!
    }

    @Test
    @TestSecurity(user = "operator4", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4504"), Claim(key = "tenant_code", value = "ULT-TEST4")])
    fun `re-zeroing stock landed on an already-DELETABLE unit load does not double-fire the trashed trio`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTD-$ns")
        val areaId = createArea("AREA-ULTD-$ns")
        val holdName = "HOLD-ULTD-$ns"
        val targetName = "TGT-ULTD-$ns"
        val holdId = createLocation(holdName, ltId, areaId)
        val targetId = createLocation(targetName, ltId, areaId)

        val ulId = createUnitLoad("UL-ULTD-$ns", holdId, holdName)
        transferUnitLoad(ulId, targetId, targetName)
        assertThat(getAllocation(targetId)).isEqualTo(100.0)

        val firstStockId = createStock(ulId, 4504001L, 5.0, "ULTD")

        tenantContext.clientId = 4504L
        countingPort.applyCount(firstStockId, BigDecimal.ZERO, "ST-ULTD-$ns")

        assertThat(unitLoadRepository.findById(ulId)!!.state)
            .`as`("first zero-count must flip the UL DELETABLE")
            .isEqualTo(StockState.DELETABLE.code)
        assertThat(getAllocation(targetId))
            .`as`("first flip must release the allocation")
            .isEqualTo(0.0)

        val outboxCountAfterFirst = outboxEvents.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", ulId, "UnitLoadTrashed",
        )
        assertThat(outboxCountAfterFirst)
            .`as`("first flip publishes exactly one UnitLoadTrashed outbox row")
            .isEqualTo(1L)

        // Land NEW stock directly on the now-DELETABLE UL (bypassing the transferStock/
        // transferToLocation DELETABLE-target guard on purpose -- see landStockOnUnitLoad's
        // KDoc) and zero-count it again. Without the idempotency guard, this re-enters the
        // DELETABLE branch in applyCount and re-fires the journal/outbox/event trio,
        // double-releasing the location's allocation.
        val secondStockId = landStockOnUnitLoad(ulId, 4504L, 4.0, ns)
        countingPort.applyCount(secondStockId, BigDecimal.ZERO, "ST-ULTD-$ns-2")

        assertThat(unitLoadRepository.findById(ulId)!!.state)
            .`as`("UL stays DELETABLE -- the guard must not re-flip or un-flip it")
            .isEqualTo(StockState.DELETABLE.code)

        val outboxCountAfterSecond = outboxEvents.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", ulId, "UnitLoadTrashed",
        )
        assertThat(outboxCountAfterSecond)
            .`as`("re-entry on an already-DELETABLE UL must NOT publish a second UnitLoadTrashed row")
            .isEqualTo(1L)

        assertThat(getAllocation(targetId))
            .`as`("re-entry must not double-release (or go negative on) the location's allocation")
            .isEqualTo(0.0)
    }

    // ── (row 14) every empty-UL producer fires the trashed trio ────────────────────────────

    /** Forward-only state change via the REST route (used to reach PICKED/PACKED). */
    private fun moveToState(id: Long, state: Int) {
        given().contentType(ContentType.JSON)
            .body("""{"state":$state}""")
            .`when`().post("/api/v1/stock-units/$id/change-state")
            .then().statusCode(200)
    }

    /** Flips a unit load straight to DELETABLE via the repository, bypassing every write path,
     * to set up the "target already terminal" fixture for the guard test below. */
    @Transactional
    fun markUnitLoadDeletable(unitLoadId: Long) {
        unitLoadRepository.findById(unitLoadId)!!.state = StockState.DELETABLE.code
    }

    private fun assertTrashedTrioFired(unitLoadId: Long, clientId: Long, label: String, locationId: Long) {
        assertThat(unitLoadRepository.findById(unitLoadId)!!.state)
            .`as`("$label: UL must flip to DELETABLE")
            .isEqualTo(StockState.DELETABLE.code)
        assertThat(getAllocation(locationId))
            .`as`("$label: the location allocation must be released")
            .isEqualTo(0.0)
        val outboxRow = outboxEvents.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", unitLoadId, "UnitLoadTrashed",
        ).firstResult()
        assertThat(outboxRow).`as`("$label: must publish a UnitLoadTrashed outbox row").isNotNull
        assertThat(outboxRow!!.tenantId).`as`("$label: outbox tenantId is the UL's own owner").isEqualTo(clientId)
    }

    @Test
    @TestSecurity(user = "operator8", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4508"), Claim(key = "tenant_code", value = "ULT-TEST8")])
    fun `transferStock merge-draining a source UL to zero fires the trashed trio`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTG-$ns")
        val areaId = createArea("AREA-ULTG-$ns")
        val holdName = "HOLD-ULTG-$ns"
        val srcName = "SRC-ULTG-$ns"
        val tgtName = "TGT-ULTG-$ns"
        val holdId = createLocation(holdName, ltId, areaId)
        val srcLocId = createLocation(srcName, ltId, areaId)
        val tgtLocId = createLocation(tgtName, ltId, areaId)

        val sourceUlId = createUnitLoad("UL-ULTG-SRC-$ns", holdId, holdName)
        transferUnitLoad(sourceUlId, srcLocId, srcName)
        assertThat(getAllocation(srcLocId)).isEqualTo(100.0)
        val targetUlId = createUnitLoad("UL-ULTG-TGT-$ns", tgtLocId, tgtName)
        val sourceStockId = createStock(sourceUlId, 4508001L, 8.0, "ULTG")

        tenantContext.clientId = 4508L
        // Review fix (defect-burndown-5, final-fixwave finding 1): captured BEFORE the call so
        // we can pin that the empty-source branch stamps `modified` too -- same captured-before
        // idiom as DefaultStockPickerShipTest's ship-time assertion.
        val beforeTransfer = Instant.now()
        stockService.transferStock(sourceStockId, targetUlId, BigDecimal("8.0"), "ULTG-XFER", tenantContext)

        assertTrashedTrioFired(sourceUlId, 4508L, "transferStock merge-drain", srcLocId)
        assertThat(stockUnitRepository.findById(sourceStockId)!!.modified)
            .`as`("transferStock's empty-source DELETABLE flip must stamp modified, not leave it " +
                "at whatever value it had before the transfer -- StockPurgeService's retention " +
                "window counts from this field")
            .isAfterOrEqualTo(beforeTransfer)
    }

    @Test
    @TestSecurity(user = "operator9", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4509"), Claim(key = "tenant_code", value = "ULT-TEST9")])
    fun `REST deleteStock of a UL's last live unit fires the trashed trio`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTH-$ns")
        val areaId = createArea("AREA-ULTH-$ns")
        val holdName = "HOLD-ULTH-$ns"
        val targetName = "TGT-ULTH-$ns"
        val holdId = createLocation(holdName, ltId, areaId)
        val targetId = createLocation(targetName, ltId, areaId)

        val ulId = createUnitLoad("UL-ULTH-$ns", holdId, holdName)
        transferUnitLoad(ulId, targetId, targetName)
        assertThat(getAllocation(targetId)).isEqualTo(100.0)
        val stockId = createStock(ulId, 4509001L, 3.0, "ULTH")

        given().`when`().delete("/api/v1/stock-units/$stockId")
            .then().statusCode(204)

        assertTrashedTrioFired(ulId, 4509L, "REST deleteStock", targetId)
    }

    @Test
    @TestSecurity(user = "operator10", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4510"), Claim(key = "tenant_code", value = "ULT-TEST10")])
    fun `shipContainer flipping the last stock to SHIPPED fires the trashed trio`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTI-$ns")
        val areaId = createArea("AREA-ULTI-$ns")
        val holdName = "HOLD-ULTI-$ns"
        val targetName = "TGT-ULTI-$ns"
        val holdId = createLocation(holdName, ltId, areaId)
        val targetId = createLocation(targetName, ltId, areaId)

        val ulId = createUnitLoad("UL-ULTI-$ns", holdId, holdName)
        transferUnitLoad(ulId, targetId, targetName)
        assertThat(getAllocation(targetId)).isEqualTo(100.0)
        val stockId = createStock(ulId, 4510001L, 6.0, "ULTI")
        moveToState(stockId, StockState.PICKED.code)
        moveToState(stockId, StockState.PACKED.code)

        tenantContext.clientId = 4510L
        val count = stockPicker.shipContainer(ulId)

        assertThat(count).isEqualTo(1)
        assertTrashedTrioFired(ulId, 4510L, "shipContainer", targetId)
    }

    // ── S6 (outbound-completion task-8): rename BEFORE shipContainer -> trash payload pin ────

    /**
     * Pins the S6 ordering (`ShippingService.dispatch`: rename AFTER `move`, BEFORE
     * `shipContainer`): when a unit load's labelId is renamed via
     * [UnitLoadMover.appendDispatchSuffix] and THEN its last stock ships, the
     * [UnitLoadTerminator] trio it fires -- journal `fromUnitLoad` and the `UnitLoadTrashed`
     * outbox payload -- must carry the NEW (renamed) label, not the original one.
     */
    @Test
    @TestSecurity(user = "operator13", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4513"), Claim(key = "tenant_code", value = "ULT-TEST13")])
    fun `renaming a unit load before shipContainer trashes with the NEW label in journal and outbox`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTM-$ns")
        val areaId = createArea("AREA-ULTM-$ns")
        val holdName = "HOLD-ULTM-$ns"
        val targetName = "TGT-ULTM-$ns"
        val holdId = createLocation(holdName, ltId, areaId)
        val targetId = createLocation(targetName, ltId, areaId)

        val ulId = createUnitLoad("UL-ULTM-$ns", holdId, holdName)
        transferUnitLoad(ulId, targetId, targetName)
        val stockId = createStock(ulId, 4513001L, 6.0, "ULTM")
        moveToState(stockId, StockState.PICKED.code)
        moveToState(stockId, StockState.PACKED.code)

        tenantContext.clientId = 4513L
        val renamedLabel = unitLoadMover.appendDispatchSuffix(ulId)
        assertThat(renamedLabel).`as`("rename must append \"-\" + the unit load's own id").isEqualTo("UL-ULTM-$ns-$ulId")

        val count = stockPicker.shipContainer(ulId)
        assertThat(count).isEqualTo(1)
        assertTrashedTrioFired(ulId, 4513L, "renamed shipContainer", targetId)

        val journalRow = journalRepository.find("fromUnitLoad = ?1", renamedLabel).firstResult()
        assertThat(journalRow)
            .`as`("the trashed journal row must carry the RENAMED label, not the original")
            .isNotNull

        val outboxRow = outboxEvents.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", ulId, "UnitLoadTrashed",
        ).firstResult()
        assertThat(outboxRow!!.payload)
            .`as`("the UnitLoadTrashed outbox payload must carry the RENAMED label")
            .contains(renamedLabel)
    }

    @Test
    @TestSecurity(user = "operator11", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4511"), Claim(key = "tenant_code", value = "ULT-TEST11")])
    fun `transferStock onto a DELETABLE unit load is refused`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTJ-$ns")
        val areaId = createArea("AREA-ULTJ-$ns")
        val locName = "LOC-ULTJ-$ns"
        val locationId = createLocation(locName, ltId, areaId)

        val sourceUlId = createUnitLoad("UL-ULTJ-SRC-$ns", locationId, locName)
        val sourceStockId = createStock(sourceUlId, 4511001L, 5.0, "ULTJ")
        val targetUlId = createUnitLoad("UL-ULTJ-TGT-$ns", locationId, locName)
        markUnitLoadDeletable(targetUlId)

        tenantContext.clientId = 4511L
        assertThatThrownBy {
            stockService.transferStock(sourceStockId, targetUlId, BigDecimal("2.0"), "ULTJ-XFER", tenantContext)
        }.isInstanceOf(InventoryException.InvalidStateTransition::class.java)
    }

    // ── (row 14 fix round) UnitLoadService.transferToLocation's own DELETABLE guard ────────

    /**
     * Direct coverage for `UnitLoadService.transferToLocation`'s guard (task-10 fix round):
     * moving a DELETABLE unit load itself must refuse with 409, distinct from case (e) above
     * (which guards the TARGET a live stock transfer lands on, `StockService.unitLoadForWrite`).
     * Goes through REST so the 409 mapping itself is pinned, not just the exception type.
     */
    @Test
    @TestSecurity(user = "operator12", roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4512"), Claim(key = "tenant_code", value = "ULT-TEST12")])
    fun `transferToLocation of a DELETABLE unit load is refused`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-ULTK-$ns")
        val areaId = createArea("AREA-ULTK-$ns")
        val sourceName = "SRC-ULTK-$ns"
        val destName = "DST-ULTK-$ns"
        val sourceId = createLocation(sourceName, ltId, areaId)
        val destinationId = createLocation(destName, ltId, areaId)

        val ulId = createUnitLoad("UL-ULTK-$ns", sourceId, sourceName)
        markUnitLoadDeletable(ulId)

        given().contentType(ContentType.JSON)
            .body("""{"destinationLocationId":$destinationId,"destinationLocationName":"$destName"}""")
            .`when`().post("/api/v1/unit-loads/$ulId/transfer")
            .then().statusCode(409)
            .body("type", equalTo("https://karyo.com/errors/invalid-state-transition"))

        assertThat(unitLoadRepository.findById(ulId)!!.storageLocationId)
            .`as`("a refused transfer must not have moved the unit load")
            .isEqualTo(sourceId)
    }
}
