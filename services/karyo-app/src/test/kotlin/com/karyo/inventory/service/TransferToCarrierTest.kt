package com.karyo.inventory.service

import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Exercises [UnitLoadService.transferToCarrier] directly — placing a unit load onto a carrier
 * unit load (a pallet onto a truck or dolly). Structural template is `ReservationTransferTest`:
 * seed data through the `/api/v1` REST surface, then call the service directly.
 *
 * TenantContext is @RequestScoped and is normally populated by TenantFilter on HTTP requests
 * only. These calls go straight into the service, so clientId (and, where the scoping is the
 * point of the test, principalKind) is primed by hand.
 *
 * `labelId` is UNIQUE and the test DB is shared across a run, so every fixture carries a
 * bounded unique suffix.
 */
@QuarkusTest
class TransferToCarrierTest {

    @Inject
    lateinit var unitLoadService: UnitLoadService

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var tenantContext: TenantContext

    private fun uniq() = System.nanoTime().toString().takeLast(6)

    private fun createUnitLoad(
        label: String,
        locationId: Long = 100,
        locationName: String = "A-01-01",
        clientId: Long? = null,
    ): Long {
        val clientField = if (clientId != null) """"clientId":$clientId,""" else ""
        return given()
            .contentType(ContentType.JSON)
            .body(
                """{$clientField"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"$locationName"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    /**
     * `CreateUnitLoadRequest` has no `isCarrier` field, so the flag is set directly on the
     * managed entity — this is fixture setup, not the behaviour under test.
     */
    private fun markAsCarrier(id: Long) {
        QuarkusTransaction.requiringNew().run {
            unitLoadRepository.findById(id)!!.isCarrier = true
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transferToCarrier sets the carrier and moves the unit load to the carrier's location`() {
        val s = uniq()
        val ulId = createUnitLoad("UL-CARR-SUB-$s", locationId = 100, locationName = "A-01-01")
        val carrierId = createUnitLoad("UL-CARR-TRK-$s", locationId = 200, locationName = "B-02-03")
        markAsCarrier(carrierId)
        tenantContext.clientId = 1L

        val moved = unitLoadService.transferToCarrier(ulId, carrierId, tenantContext)

        assertThat(moved.carrierUnitLoad?.id).isEqualTo(carrierId)
        assertThat(moved.storageLocationId).isEqualTo(200L)
        assertThat(moved.storageLocationName).isEqualTo("B-02-03")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transferToCarrier rejects a target that is not a carrier`() {
        val s = uniq()
        val ulId = createUnitLoad("UL-NOTC-SUB-$s")
        val notACarrier = createUnitLoad("UL-NOTC-TGT-$s", locationId = 200, locationName = "B-02-03")
        tenantContext.clientId = 1L

        assertThrows<InventoryException.ValidationFailed> {
            unitLoadService.transferToCarrier(ulId, notACarrier, tenantContext)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transferToCarrier rejects a unit load carrying itself`() {
        val ulId = createUnitLoad("UL-SELF-${uniq()}")
        markAsCarrier(ulId)
        tenantContext.clientId = 1L

        assertThrows<InventoryException.ValidationFailed> {
            unitLoadService.transferToCarrier(ulId, ulId, tenantContext)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transferToCarrier rejects a carrier cycle`() {
        val s = uniq()
        val aId = createUnitLoad("UL-CYC-A-$s", locationId = 100, locationName = "A-01-01")
        val bId = createUnitLoad("UL-CYC-B-$s", locationId = 200, locationName = "B-02-03")
        markAsCarrier(aId)
        markAsCarrier(bId)
        tenantContext.clientId = 1L

        // A is placed on carrier B ...
        unitLoadService.transferToCarrier(aId, bId, tenantContext)

        // ... so placing B on A would close the loop.
        assertThrows<InventoryException.ValidationFailed> {
            unitLoadService.transferToCarrier(bId, aId, tenantContext)
        }
    }

    /**
     * Second-entity scoping: the subject is within the owner's write scope, so only an
     * independent scope check on the *carrier* can reject this. Without that check, naming
     * another goods owner's unit load as the carrier sidesteps the owner check entirely.
     */
    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `an owner cannot use another owner's unit load as the carrier`() {
        val s = uniq()
        val ownedByTwo = createUnitLoad("UL-SCOPE-SUB-$s", clientId = 2)
        val ownedByOne = createUnitLoad(
            "UL-SCOPE-CARR-$s",
            locationId = 200,
            locationName = "B-02-03",
            clientId = 1,
        )
        markAsCarrier(ownedByOne)

        tenantContext.clientId = 2L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThrows<InventoryException.NotFound> {
            unitLoadService.transferToCarrier(ownedByTwo, ownedByOne, tenantContext)
        }
    }

    /**
     * D1 precedent (`StockService.requireSameOwnerForTransfer`): cross-owner nesting is an
     * integrity violation, not an authorization one -- an OPS principal passes both write-scope
     * checks (subject and carrier) for ANY two owners, so only an explicit clientId comparison
     * inside the service stops it from constructing the mixed-owner trees WORKLIST F3 flagged.
     */
    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `transferToCarrier refuses nesting under another owner's carrier`() {
        val s = uniq()
        val ulId = createUnitLoad("UL-COW-SUB-$s", clientId = 1)
        val carrierId = createUnitLoad(
            "UL-COW-TRK-$s",
            locationId = 200,
            locationName = "B-02-03",
            clientId = 2,
        )
        markAsCarrier(carrierId)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThrows<InventoryException.CrossOwner> {
            unitLoadService.transferToCarrier(ulId, carrierId, tenantContext)
        }
    }

    /** Belt for regression: the guard must not fire when subject and carrier share an owner. */
    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "principal_kind", value = "ops"),
        ],
    )
    fun `transferToCarrier still succeeds when subject and carrier share an owner`() {
        val s = uniq()
        val ulId = createUnitLoad("UL-COW-SAME-SUB-$s", clientId = 1)
        val carrierId = createUnitLoad(
            "UL-COW-SAME-TRK-$s",
            locationId = 200,
            locationName = "B-02-03",
            clientId = 1,
        )
        markAsCarrier(carrierId)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val moved = unitLoadService.transferToCarrier(ulId, carrierId, tenantContext)

        assertThat(moved.carrierUnitLoad?.id).isEqualTo(carrierId)
        assertThat(moved.storageLocationId).isEqualTo(200L)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `findByCarrierUnitLoadId returns the unit loads on a carrier`() {
        val s = uniq()
        val carrierId = createUnitLoad("UL-FIND-TRK-$s", locationId = 200, locationName = "B-02-03")
        markAsCarrier(carrierId)
        val firstId = createUnitLoad("UL-FIND-1-$s")
        val secondId = createUnitLoad("UL-FIND-2-$s")
        tenantContext.clientId = 1L

        unitLoadService.transferToCarrier(firstId, carrierId, tenantContext)
        unitLoadService.transferToCarrier(secondId, carrierId, tenantContext)

        val onCarrier = QuarkusTransaction.requiringNew().call {
            unitLoadRepository.findByCarrierUnitLoadId(carrierId).map { it.id }
        }

        assertThat(onCarrier).containsExactlyInAnyOrder(firstId, secondId)
    }
}
