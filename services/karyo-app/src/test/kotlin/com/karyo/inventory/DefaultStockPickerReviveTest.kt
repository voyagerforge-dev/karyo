package com.karyo.inventory

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.spi.StockMover
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Bulk Allocation Sprint C: [StockPicker.reviveDrainedContainer] -- the inverse of the DELETABLE
 * tombstoning that draining a batch pick cart leaves behind.
 *
 * The property under test is that the revive is TARGETED and COMPLETE. Targeted: only the emptied
 * row of the named item AND lot comes back, because a cart is routinely multi-SKU and multi-lot,
 * and a blanket flip would strand PICKED ghosts for items nothing is returning -- permanent ones,
 * since they sit outside both `UnitLoadTerminator`'s gone-predicate (SHIPPED/DELETABLE, so the
 * cart could never be tombstoned again) and `StockPurgeService`'s DELETABLE-only reaper. Complete:
 * reviving the unit load restores the `StorageLocation.allocation` that `UnitLoadTrashedEvent`
 * released, and leaves the same journal + outbox trace the trash did, so a trash/revive round trip
 * is neutral on all three (the defect-burndown-4 row 14 class of bug, in reverse).
 *
 * Every state read goes over REST on purpose: a `@QuarkusTest` method runs inside ONE request
 * context, so a direct repository read shares a Hibernate session with every earlier direct read
 * in the same method and would hand back a first-level-cache copy from before the revive
 * committed. Journal/outbox counts are the exception -- those rows are only ever inserted, never
 * read-then-updated here, so there is no stale copy to serve.
 *
 * clientId 4520-4522 reserved for this suite.
 */
@QuarkusTest
class DefaultStockPickerReviveTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var stockMover: StockMover

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    // -- seed helpers (mirrored from UnitLoadTerminationTest) -----------------------------

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, locationId: Long, locationName: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"$locationName"}""",
            )
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun transferUnitLoad(ulId: Long, destinationId: Long, destinationName: String) {
        given().contentType(ContentType.JSON)
            .body("""{"destinationLocationId":$destinationId,"destinationLocationName":"$destinationName"}""")
            .`when`().post("/api/v1/unit-loads/$ulId/transfer").then().statusCode(200)
    }

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double, lot: String?): Long {
        val lotJson = if (lot == null) "" else ""","lotNumber":"$lot""""
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300$lotJson}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun getAllocation(locationId: Long): Double =
        given().`when`().get("/api/v1/locations/$locationId").then().statusCode(200)
            .extract().jsonPath().getDouble("allocation")

    private fun unitLoadStateOf(unitLoadId: Long): Int =
        given().`when`().get("/api/v1/unit-loads/$unitLoadId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

    private fun stockStateOf(stockUnitId: Long): Int =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

    private fun stockAmountOf(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId").then().statusCode(200)
            .extract().jsonPath().getDouble("amount")

    // -- fixture --------------------------------------------------------------------------

    /** A cart drained exactly the way a consolidation pack-out drains one. */
    data class DrainedCart(
        val cartUl: Long,
        val cartLabel: String,
        val cartLocId: Long,
        val itemA: Long,
        val itemB: Long,
        val a1: Long,
        val a2: Long,
        val b1: Long,
    )

    /**
     * Seeds a cart holding three rows -- one item under two lots, plus a second item -- and then
     * moves every one of them off it, which is what tombstones all three rows AND the cart itself.
     * The cart is CREATED at a seed location and TRANSFERRED to its slot on purpose: unit-load
     * creation does not touch allocation (only `UnitLoadTransferredEvent` does), so the transfer
     * is what puts the +100 on the slot that the trash then releases.
     */
    private fun drainedCart(ns: String): DrainedCart {
        val ltId = createLocationType("LT-REV-$ns")
        val areaId = createArea("AREA-REV-$ns")
        val seedName = "SEED-REV-$ns"
        val cartName = "CART-REV-$ns"
        val sinkName = "SINK-REV-$ns"
        val seedId = createLocation(seedName, ltId, areaId)
        val cartLocId = createLocation(cartName, ltId, areaId)
        val sinkLocId = createLocation(sinkName, ltId, areaId)

        val cartLabel = "UL-CART-$ns"
        val cartUl = createUnitLoad(cartLabel, seedId, seedName)
        transferUnitLoad(cartUl, cartLocId, cartName)
        assertThat(getAllocation(cartLocId)).isEqualTo(ALLOCATION_PER_UL)
        val sinkUl = createUnitLoad("UL-SINK-$ns", sinkLocId, sinkName)

        val itemA = 4520001L
        val itemB = 4520002L
        val a1 = createStock(cartUl, itemA, "REV-A-$ns", AMOUNT_A1, "LOT-1")
        val a2 = createStock(cartUl, itemA, "REV-A-$ns", AMOUNT_A2, "LOT-2")
        val b1 = createStock(cartUl, itemB, "REV-B-$ns", AMOUNT_B1, null)

        tenantContext.clientId = CLIENT
        stockMover.transferToUnitLoad(a1, sinkUl, BigDecimal(AMOUNT_A1.toString()), "PACK")
        assertThat(unitLoadStateOf(cartUl)).`as`("cart stays live while other rows hold stock").isEqualTo(UNDEFINED)
        stockMover.transferToUnitLoad(a2, sinkUl, BigDecimal(AMOUNT_A2.toString()), "PACK")
        stockMover.transferToUnitLoad(b1, sinkUl, BigDecimal(AMOUNT_B1.toString()), "PACK")

        assertThat(unitLoadStateOf(cartUl)).`as`("drained cart is tombstoned").isEqualTo(DELETABLE)
        assertThat(listOf(a1, a2, b1).map { stockStateOf(it) }).containsExactly(DELETABLE, DELETABLE, DELETABLE)
        assertThat(getAllocation(cartLocId)).`as`("trash released the slot").isEqualTo(0.0)
        return DrainedCart(cartUl, cartLabel, cartLocId, itemA, itemB, a1, a2, b1)
    }

    /** Journal + outbox trace, mirroring exactly what `UnitLoadTerminator.fireTrashed` wrote. */
    private fun assertReviveTrace(cart: DrainedCart) {
        // activityCode discriminates the revive row from the three CREATED rows `createStock`
        // itself wrote against this same unit load (those carry no activity code).
        assertThat(
            journalRepository.count(
                "toUnitLoad = ?1 and clientId = ?2 and recordType = ?3 and activityCode = ?4",
                cart.cartLabel, CLIENT, JournalRecordType.CREATED.code, ACTIVITY_UNPACK,
            ),
        ).`as`("the unit-load half of the revive is journaled").isEqualTo(1L)
        assertThat(
            journalRepository.count(
                "toUnitLoad = ?1 and clientId = ?2 and recordType = ?3 and activityCode = ?4",
                cart.cartLabel, CLIENT, JournalRecordType.CHANGED.code, ACTIVITY_UNPACK,
            ),
        ).`as`("and so is the one stock row it brought back").isEqualTo(1L)
        assertThat(
            outboxEvents.find(
                "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
                "UnitLoad", cart.cartUl, "UnitLoadRevived",
            ).firstResult(),
        ).`as`("the unit-load half of the revive is published").isNotNull
    }

    // -- tests ----------------------------------------------------------------------------

    @Test
    @TestSecurity(
        user = "revive",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4520"), Claim(key = "tenant_code", value = "REVIVE")])
    fun `revive is targeted at one item and lot, restores allocation, journals, and is idempotent`() {
        val cart = drainedCart(System.nanoTime().toString().takeLast(8))

        // (1) A foreign client flips nothing at all.
        assertThat(stockPicker.reviveDrainedContainer(cart.cartUl, cart.itemA, "LOT-1", FOREIGN_CLIENT)).isEqualTo(0)
        assertThat(unitLoadStateOf(cart.cartUl)).`as`("cross-client revive must not resurrect").isEqualTo(DELETABLE)
        assertThat(getAllocation(cart.cartLocId)).isEqualTo(0.0)

        // (2) The real revive: the unit load plus exactly ONE stock row.
        assertThat(stockPicker.reviveDrainedContainer(cart.cartUl, cart.itemA, "LOT-1", CLIENT))
            .`as`("one unit load + one matching stock row")
            .isEqualTo(2)
        assertThat(unitLoadStateOf(cart.cartUl)).isEqualTo(UNDEFINED)
        assertThat(stockStateOf(cart.a1)).`as`("targeted row comes back as PICKED").isEqualTo(PICKED)
        assertThat(stockStateOf(cart.a2)).`as`("same item, other lot stays tombstoned").isEqualTo(DELETABLE)
        assertThat(stockStateOf(cart.b1)).`as`("other item stays tombstoned").isEqualTo(DELETABLE)
        assertThat(getAllocation(cart.cartLocId)).`as`("revive re-takes the slot").isEqualTo(ALLOCATION_PER_UL)

        assertReviveTrace(cart)

        // (3) Idempotent: nothing left to revive for that item/lot, and no second allocation bump.
        assertThat(stockPicker.reviveDrainedContainer(cart.cartUl, cart.itemA, "LOT-1", CLIENT)).isEqualTo(0)
        assertThat(getAllocation(cart.cartLocId)).isEqualTo(ALLOCATION_PER_UL)

        // (4) A row still carrying stock is never touched: the cart is live again, so fresh stock
        //     can land on it, and asking to revive that item flips nothing.
        val itemC = 4520003L
        val c1 = createStock(cart.cartUl, itemC, "REV-C-${cart.cartLabel}", AMOUNT_C1, null)
        assertThat(stockPicker.reviveDrainedContainer(cart.cartUl, itemC, null, CLIENT)).isEqualTo(0)
        assertThat(stockStateOf(c1)).`as`("a positive-amount row keeps its own state").isEqualTo(ON_STOCK)
        assertThat(stockAmountOf(c1)).isEqualTo(AMOUNT_C1)

        // (5) An unlotted revive never matches a lotted row: LOT-2 is still the only survivor.
        assertThat(stockPicker.reviveDrainedContainer(cart.cartUl, cart.itemA, null, CLIENT)).isEqualTo(0)
        assertThat(stockStateOf(cart.a2)).isEqualTo(DELETABLE)
    }

    /**
     * IMPORTANT 4 (Sprint C final-review fix wave): null and "" are the SAME lot on both halves of
     * the consolidation restore. `ShippingLifecycleService.sourceStockFor` already normalized;
     * this side compared strictly, so a container line carrying "" for "no lot" revived nothing
     * against an unlotted tombstone and the move-back then hit a still-DELETABLE target.
     * `b1` is the cart's unlotted row -- seeded with a null `lotNumber`.
     */
    @Test
    @TestSecurity(
        user = "revive",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4520"), Claim(key = "tenant_code", value = "REVIVE")])
    fun `an empty-string lot revives an unlotted tombstone -- null and blank are one lot`() {
        val cart = drainedCart(System.nanoTime().toString().takeLast(8))

        assertThat(stockPicker.reviveDrainedContainer(cart.cartUl, cart.itemB, "", CLIENT))
            .`as`("the unit load plus the unlotted row it was asked for")
            .isEqualTo(2)
        assertThat(stockStateOf(cart.b1)).`as`("the unlotted row comes back").isEqualTo(PICKED)
        assertThat(stockStateOf(cart.a1)).`as`("a lotted row of another item stays tombstoned").isEqualTo(DELETABLE)
        assertThat(stockStateOf(cart.a2)).isEqualTo(DELETABLE)
        assertThat(unitLoadStateOf(cart.cartUl)).isEqualTo(UNDEFINED)
    }

    /**
     * Row :2061: [StockMover.transferToUnitLoad]'s explicit-`clientId` overload must resolve its
     * scope from the passed `clientId` ALONE. Proven from both sides in one test, because either
     * side on its own is ambiguous: the foreign call runs while the ambient context holds the
     * CORRECT owner (so a refusal can only come from the explicit argument), and the owner call
     * runs while the ambient context holds client 0 -- the unprimed default a non-REST caller
     * actually sees -- so a success can only come from the explicit argument too.
     *
     * The foreign-call contract is derived, not invented: the move goes through
     * `StockService.transferStock` -> `findByIdForWrite`, which throws
     * [com.karyo.inventory.exception.InventoryException.NotFound] for a row its write scope does
     * not permit (a foreign row is reported as absent, never as a distinct "forbidden").
     */
    @Test
    @TestSecurity(
        user = "revive",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "4520"), Claim(key = "tenant_code", value = "REVIVE")])
    fun `transferToUnitLoad with explicit clientId ignores the ambient tenant`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-MOV-$ns")
        val areaId = createArea("AREA-MOV-$ns")
        val srcName = "SRC-MOV-$ns"
        val sinkName = "SINK-MOV-$ns"
        val srcLocId = createLocation(srcName, ltId, areaId)
        val sinkLocId = createLocation(sinkName, ltId, areaId)
        val srcUl = createUnitLoad("UL-MOV-SRC-$ns", srcLocId, srcName)
        val sinkUl = createUnitLoad("UL-MOV-SINK-$ns", sinkLocId, sinkName)
        val srcStock = createStock(srcUl, 4520004L, "MOV-$ns", AMOUNT_A1, null)

        // Ambient holds the RIGHT owner; the foreign explicit clientId must still refuse to move.
        tenantContext.clientId = CLIENT
        assertThatThrownBy {
            stockMover.transferToUnitLoad(srcStock, sinkUl, BigDecimal(MOVE_AMOUNT.toString()), "TEST", FOREIGN_CLIENT)
        }.isInstanceOf(InventoryException.NotFound::class.java)
        assertThat(stockAmountOf(srcStock)).`as`("a refused move leaves the source untouched").isEqualTo(AMOUNT_A1)

        // Ambient holds the unprimed default (client 0); the owner explicit clientId still moves.
        tenantContext.clientId = 0L
        val moved = stockMover.transferToUnitLoad(
            srcStock,
            sinkUl,
            BigDecimal(MOVE_AMOUNT.toString()),
            "TEST",
            CLIENT,
        )
        assertThat(moved.unitLoadId).isEqualTo(sinkUl)
        assertThat(stockAmountOf(srcStock)).isEqualTo(AMOUNT_A1 - MOVE_AMOUNT)
    }

    private companion object {
        const val CLIENT = 4520L
        const val FOREIGN_CLIENT = 4521L
        const val ACTIVITY_UNPACK = "UNPACK"
        const val ALLOCATION_PER_UL = 100.0
        const val UNDEFINED = 0
        const val ON_STOCK = 300
        const val PICKED = 600
        const val DELETABLE = 1000
        const val AMOUNT_A1 = 10.0
        const val AMOUNT_A2 = 4.0
        const val AMOUNT_B1 = 7.0
        const val AMOUNT_C1 = 3.0
        const val MOVE_AMOUNT = 1.0
    }
}
