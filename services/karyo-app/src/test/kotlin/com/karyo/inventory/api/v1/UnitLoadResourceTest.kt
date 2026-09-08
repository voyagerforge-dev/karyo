package com.karyo.inventory.api.v1

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.layout.domain.model.Area
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.AreaRepository
import com.karyo.layout.repository.LocationTypeRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.ClearingLocationInfo
import com.karyo.layout.spi.ClearingLocationLookup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn

@QuarkusTest
class UnitLoadResourceTest {

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    @Inject
    lateinit var areaRepository: AreaRepository

    @Inject
    lateinit var locationTypeRepository: LocationTypeRepository

    @Inject
    lateinit var storageLocationRepository: StorageLocationRepository

    // A2-1: mocked so `transfer-to-clearing 409s when no clearing location is configured`
    // is deterministic regardless of test execution order or DB state (the real
    // DefaultClearingLocationLookup query is covered separately by
    // com.karyo.layout.ClearingLocationTest against the real DB).
    @InjectMock
    lateinit var clearingLocationLookup: ClearingLocationLookup

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `create and retrieve unit load`() {
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-TEST-001","unitLoadTypeId":1,"storageLocationId":${reserveLocationId()},"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .body("labelId", `is`("UL-TEST-001"))
            .body("id", notNullValue())
            .extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/unit-loads/$id")
            .then().statusCode(200).body("labelId", `is`("UL-TEST-001"))

        given().`when`().get("/api/v1/unit-loads/by-label/UL-TEST-001")
            .then().statusCode(200).body("id", `is`(id.toInt()))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transfer unit load to new location`() {
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-TRANSFER-001","unitLoadTypeId":1,"storageLocationId":${reserveLocationId()},"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        val destinationLocationId = reserveLocationId()
        given()
            .contentType(ContentType.JSON)
            .body("""{"destinationLocationId":$destinationLocationId,"destinationLocationName":"B-02-03"}""")
            .`when`().post("/api/v1/unit-loads/$id/transfer")
            .then().statusCode(200)
            .body("storageLocationId", `is`(destinationLocationId.toInt()))
            .body("storageLocationName", `is`("B-02-03"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transfer unit load onto a carrier`() {
        val s = System.nanoTime().toString().takeLast(6)
        val dockLocationId = reserveLocationId()

        val carrierId = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-REST-CARR-$s","unitLoadTypeId":1,"storageLocationId":$dockLocationId,"storageLocationName":"DOCK-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        // `CreateUnitLoadRequest` carries no `isCarrier` flag, so it is set on the entity directly.
        QuarkusTransaction.requiringNew().run {
            unitLoadRepository.findById(carrierId)!!.isCarrier = true
        }

        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-REST-SUB-$s","unitLoadTypeId":1,"storageLocationId":${reserveLocationId()},"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given()
            .contentType(ContentType.JSON)
            .body("""{"carrierUnitLoadId":$carrierId}""")
            .`when`().post("/api/v1/unit-loads/$id/transfer-to-carrier")
            .then().statusCode(200)
            .body("storageLocationId", `is`(dockLocationId.toInt()))
            .body("storageLocationName", `is`("DOCK-01"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `viewer cannot create unit load`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-DENIED","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(403)
    }

    @Test
    fun `unauthenticated request returns 401`() {
        given().`when`().get("/api/v1/unit-loads/1")
            .then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `mark and unmark a unit load as carrier via the API`() {
        val s = System.nanoTime().toString().takeLast(6)
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-CARR-SET-$s","unitLoadTypeId":1,"storageLocationId":${reserveLocationId()},"storageLocationName":"DOCK-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        given().contentType(ContentType.JSON).body("""{"isCarrier":true}""")
            .`when`().post("/api/v1/unit-loads/$id/carrier")
            .then().statusCode(200).body("isCarrier", `is`(true))

        given().contentType(ContentType.JSON).body("""{"isCarrier":false}""")
            .`when`().post("/api/v1/unit-loads/$id/carrier")
            .then().statusCode(200).body("isCarrier", `is`(false))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `unmarking a carrier that still carries unit loads is refused`() {
        val s = System.nanoTime().toString().takeLast(6)
        val carrierId = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-CARR-BUSY-$s","unitLoadTypeId":1,"storageLocationId":${reserveLocationId()},"storageLocationName":"DOCK-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON).body("""{"isCarrier":true}""")
            .`when`().post("/api/v1/unit-loads/$carrierId/carrier").then().statusCode(200)

        val subjectId = given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"UL-CARR-SUBJ-$s","unitLoadTypeId":1,"storageLocationId":${reserveLocationId()},"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON).body("""{"carrierUnitLoadId":$carrierId}""")
            .`when`().post("/api/v1/unit-loads/$subjectId/transfer-to-carrier").then().statusCode(200)

        // InventoryException.ValidationFailed maps to 400 (InventoryExceptionMapper) — the same
        // status existing tests rely on for the sibling "is not a carrier" guard in transferToCarrier.
        given().contentType(ContentType.JSON).body("""{"isCarrier":false}""")
            .`when`().post("/api/v1/unit-loads/$carrierId/carrier")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `setting the carrier flag requires inventory-write`() {
        given().contentType(ContentType.JSON).body("""{"isCarrier":true}""")
            .`when`().post("/api/v1/unit-loads/1/carrier")
            .then().statusCode(403)
    }

    // --- A2-3 recursive unit-load lock -------------------------------------------------

    /**
     * Reserves a genuinely-unique location id by persisting a real (throwaway) Area +
     * LocationType + StorageLocation, and returning the StorageLocation's real id.
     *
     * `UnitLoad.storageLocationId` carries no FK (cross-module refs are ids-only, per repo
     * convention), so this class used to pass hardcoded placeholders (100/200/300/500) instead.
     * Those collided intermittently with a REAL StorageLocation id assigned elsewhere in the
     * same test run once the shared `storage_locations` auto-increment sequence reached one of
     * those small numbers -- `PartialMoveFlowTest`'s `findByStorageLocationId` lookups would
     * then pick up this class's stray UnitLoad rows too. Drawing every location id from an
     * actually-created entity, instead of a hardcoded literal, makes collision structurally
     * impossible.
     */
    @Transactional
    fun reserveLocationId(): Long {
        val s = System.nanoTime()
        val area = Area().apply { name = "UL-RES-AREA-$s" }
        areaRepository.persist(area)
        val type = LocationType().apply { name = "UL-RES-TYPE-$s" }
        locationTypeRepository.persist(type)
        val location = StorageLocation().apply {
            name = "UL-RES-LOC-$s"
            locationType = type
            this.area = area
            clientId = 1L
        }
        storageLocationRepository.persist(location)
        return location.id!!
    }

    private fun createUnitLoad(label: String, locationId: Long = reserveLocationId(), locationName: String = "A-01-01"): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":$locationId,"storageLocationName":"$locationName"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double = 10.0): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,"unitLoadId":$unitLoadId,"state":300}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `lock cascades to stock units and nested carrier children`() {
        val s = System.nanoTime().toString().takeLast(6)

        val carrierId = createUnitLoad("UL-LOCK-CARR-$s", locationName = "DOCK-01")
        QuarkusTransaction.requiringNew().run {
            unitLoadRepository.findById(carrierId)!!.isCarrier = true
        }
        val childId = createUnitLoad("UL-LOCK-CHILD-$s")
        given().contentType(ContentType.JSON).body("""{"carrierUnitLoadId":$carrierId}""")
            .`when`().post("/api/v1/unit-loads/$childId/transfer-to-carrier")
            .then().statusCode(200)

        val stockOnCarrier = createStock(carrierId, 9100L + (System.nanoTime() % 100_000), "LOCK-CARR-$s")
        val stockOnChild = createStock(childId, 9200L + (System.nanoTime() % 100_000), "LOCK-CHILD-$s")

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$carrierId/lock")
            .then().statusCode(200)
            .body("lockType", `is`(1))
            .body("lockTypeName", `is`("GENERAL"))

        given().`when`().get("/api/v1/unit-loads/$childId")
            .then().statusCode(200)
            .body("lockType", `is`(1))
            .body("lockTypeName", `is`("GENERAL"))

        given().`when`().get("/api/v1/stock-units/$stockOnCarrier")
            .then().statusCode(200).body("lockType", `is`(1))
        given().`when`().get("/api/v1/stock-units/$stockOnChild")
            .then().statusCode(200).body("lockType", `is`(1))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `unlock cascades back to UNLOCKED`() {
        val s = System.nanoTime().toString().takeLast(6)

        val carrierId = createUnitLoad("UL-UNLOCK-CARR-$s", locationName = "DOCK-01")
        QuarkusTransaction.requiringNew().run {
            unitLoadRepository.findById(carrierId)!!.isCarrier = true
        }
        val childId = createUnitLoad("UL-UNLOCK-CHILD-$s")
        given().contentType(ContentType.JSON).body("""{"carrierUnitLoadId":$carrierId}""")
            .`when`().post("/api/v1/unit-loads/$childId/transfer-to-carrier")
            .then().statusCode(200)

        val stockOnChild = createStock(childId, 9300L + (System.nanoTime() % 100_000), "UNLOCK-CHILD-$s")

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$carrierId/lock")
            .then().statusCode(200)

        given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/unit-loads/$carrierId/unlock")
            .then().statusCode(200)
            .body("lockType", `is`(0))
            .body("lockTypeName", `is`("UNLOCKED"))

        given().`when`().get("/api/v1/unit-loads/$childId")
            .then().statusCode(200).body("lockType", `is`(0))

        given().`when`().get("/api/v1/stock-units/$stockOnChild")
            .then().statusCode(200).body("lockType", `is`(0))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `lock rejects invalid lockType code with 4xx`() {
        val id = createUnitLoad("UL-LOCK-BADCODE-${System.nanoTime()}")

        given().contentType(ContentType.JSON).body("""{"lockType":999}""")
            .`when`().post("/api/v1/unit-loads/$id/lock")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `lock is idempotent - relocking GENERAL changes nothing and 200s`() {
        val id = createUnitLoad("UL-LOCK-IDEM-${System.nanoTime()}")

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$id/lock")
            .then().statusCode(200).body("lockType", `is`(1))

        val outboxCountAfterFirstLock = outboxEvents.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", id, "LockChanged",
        )

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$id/lock")
            .then().statusCode(200).body("lockType", `is`(1))

        // F3: re-locking an already-GENERAL unit load must be a true no-op on the outbox log
        // too, not just on the returned lockType -- applyLockRecursive's `old != target.code`
        // guard is what is under test here; a regression that dropped it would still 200 with
        // the same lockType but silently double the LockChanged rows.
        val outboxCountAfterSecondLock = outboxEvents.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", id, "LockChanged",
        )
        assertThat(outboxCountAfterSecondLock).isEqualTo(outboxCountAfterFirstLock)
    }

    // --- F1: recursive lock must scope-check nested carrier children -------------------

    /** Seeds a bare unit load owned by [owner], bypassing REST so a foreign owner can be created. */
    @Transactional
    fun seedUnitLoad(owner: Long, labelId: String, isCarrier: Boolean = false, carrier: UnitLoad? = null): Long {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = labelId
            this.unitLoadType = type
            this.storageLocationId = reserveLocationId()
            this.storageLocationName = "F1-LOC"
            this.isCarrier = isCarrier
            this.carrierUnitLoad = carrier
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    @Test
    @TestSecurity(user = "owner1", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "principal_kind", value = "owner"),
        ],
    )
    fun `OWNER cannot lock recursively into a cross-owner nested carrier child`() {
        val s = System.nanoTime().toString().takeLast(8)
        val carrierId = seedUnitLoad(owner = 1L, labelId = "UL-F1-CARR-$s", isCarrier = true)
        val carrier = unitLoadRepository.findById(carrierId)!!
        // Nested carrier child owned by a DIFFERENT client -- transferToCarrier now refuses to
        // construct this tree itself (WORKLIST F3 fixed: cross-owner nesting is a 409), so this
        // seeds the mixed tree directly to exercise the lock cascade's OWN defense against
        // legacy-data trees that predate the guard, which is the point under test here.
        val childId = seedUnitLoad(owner = 2L, labelId = "UL-F1-CHILD-$s", carrier = carrier)

        // Before the fix: this 200s, and the child's lockType (owner 2's row) gets written by
        // an owner-1 principal that has no write scope over client 2 at all.
        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$carrierId/lock")
            .then().statusCode(404)

        // Total-operation rollback: the carrier's OWN lock write happens before the offending
        // child is reached in the recursion, yet must not survive either -- the whole
        // transaction rolls back on the child's scope violation. Read via a fresh transaction
        // (not the test's ambient persistence context, which still holds the pre-REST-call
        // entity instances in its L1 cache) so this is a real DB round-trip, not a stale read.
        assertThat(
            QuarkusTransaction.requiringNew().call { unitLoadRepository.findById(carrierId)!!.lockType },
        ).isEqualTo(0)
        assertThat(
            QuarkusTransaction.requiringNew().call { unitLoadRepository.findById(childId)!!.lockType },
        ).isEqualTo(0)
    }

    @Test
    @TestSecurity(user = "ops-actor", roles = ["inventory-read", "inventory-write", "ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `OPS principal CAN lock recursively into a cross-owner nested carrier child`() {
        val s = System.nanoTime().toString().takeLast(8)
        val carrierId = seedUnitLoad(owner = 1L, labelId = "UL-F1-OPS-CARR-$s", isCarrier = true)
        val carrier = unitLoadRepository.findById(carrierId)!!
        val childId = seedUnitLoad(owner = 2L, labelId = "UL-F1-OPS-CHILD-$s", carrier = carrier)

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$carrierId/lock")
            .then().statusCode(200).body("lockType", `is`(1))

        // Fresh transaction for the same reason as the OWNER test above: a real DB read, not
        // the test's own stale L1-cached entity instances.
        assertThat(
            QuarkusTransaction.requiringNew().call { unitLoadRepository.findById(carrierId)!!.lockType },
        ).isEqualTo(1)
        assertThat(
            QuarkusTransaction.requiringNew().call { unitLoadRepository.findById(childId)!!.lockType },
        ).isEqualTo(1)
    }

    // --- A2-1 transferToClearing ---------------------------------------------------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transfer-to-clearing moves the UL to the clearing location and locks recursively`() {
        val s = System.nanoTime().toString().takeLast(6)
        val clearingId = 900_000_000L + (System.nanoTime() % 100_000)
        doReturn(ClearingLocationInfo(clearingId, "CLR-$s"))
            .`when`(clearingLocationLookup).findClearing()

        val id = createUnitLoad("UL-CLR-$s")
        val stockId = createStock(id, 9500L + (System.nanoTime() % 100_000), "CLR-STOCK-$s")

        given().contentType(ContentType.JSON).body("""{"note":"quarantine"}""")
            .`when`().post("/api/v1/unit-loads/$id/transfer-to-clearing")
            .then().statusCode(200)
            .body("storageLocationId", `is`(clearingId.toInt()))
            .body("storageLocationName", `is`("CLR-$s"))
            .body("lockType", `is`(1))
            .body("lockTypeName", `is`("GENERAL"))

        given().`when`().get("/api/v1/stock-units/$stockId")
            .then().statusCode(200).body("lockType", `is`(1))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transfer-to-clearing 409s when no clearing location is configured`() {
        // Explicitly stubbed (not just relying on Mockito's unstubbed-null default) so this
        // is deterministic even if another test method in this class already stubbed the
        // same shared mock -- @InjectMock installs one mock instance for the whole class.
        doReturn(null).`when`(clearingLocationLookup).findClearing()

        val id = createUnitLoad("UL-CLR-NONE-${System.nanoTime()}")

        given().contentType(ContentType.JSON).body("""{}""")
            .`when`().post("/api/v1/unit-loads/$id/transfer-to-clearing")
            .then().statusCode(409)
    }

    // --- FIX-1 (F2): unlock must not clobber a pre-existing per-stock lock ----------------

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `lock then unlock preserves a stronger pre-existing per-stock lock`() {
        val s = System.nanoTime().toString().takeLast(6)
        val palletId = createUnitLoad("UL-PRESERVE-$s")

        val expiredStockId = createStock(palletId, 9600L + (System.nanoTime() % 100_000), "PRESERVE-A-$s")
        val plainStockId = createStock(palletId, 9700L + (System.nanoTime() % 100_000), "PRESERVE-B-$s")

        // Stock A carries an individually-set LOT_EXPIRED(202) hold from receipt, BEFORE the
        // pallet is ever locked.
        given().contentType(ContentType.JSON).body("""{"lockType":202}""")
            .`when`().post("/api/v1/stock-units/$expiredStockId/lock")
            .then().statusCode(200).body("lockType", `is`(202))

        // Locking the pallet must not downgrade stock A's stronger, independently-set hold --
        // it is already locked, which is the pallet-lock's goal. Stock B (plain, unlocked)
        // DOES get driven to the pallet's GENERAL(1) code.
        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$palletId/lock")
            .then().statusCode(200).body("lockType", `is`(1))

        given().`when`().get("/api/v1/stock-units/$expiredStockId")
            .then().statusCode(200).body("lockType", `is`(202))
        given().`when`().get("/api/v1/stock-units/$plainStockId")
            .then().statusCode(200).body("lockType", `is`(1))

        // Unlocking the pallet clears only the stock this pallet-lock itself drove (B, still
        // carrying the pallet's own GENERAL(1) code) -- stock A's differing, pre-existing 202
        // hold must survive the unlock untouched. Before the fix, unlock drove EVERY stock
        // unit on the pallet to UNLOCKED(0), silently clearing the expiry hold and letting
        // expired stock re-enter selection.
        given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/unit-loads/$palletId/unlock")
            .then().statusCode(200).body("lockType", `is`(0))

        given().`when`().get("/api/v1/stock-units/$expiredStockId")
            .then().statusCode(200).body("lockType", `is`(202))
        given().`when`().get("/api/v1/stock-units/$plainStockId")
            .then().statusCode(200).body("lockType", `is`(0))
    }

    @Test
    fun `fromCode 405 returns SHIPPED with correct name mapping`() {
        val lockType = com.karyo.inventory.api.vo.LockType.fromCode(405)
        assertThat(lockType).isEqualTo(com.karyo.inventory.api.vo.LockType.SHIPPED)
        assertThat(lockType.name).isEqualTo("SHIPPED")
    }
}
