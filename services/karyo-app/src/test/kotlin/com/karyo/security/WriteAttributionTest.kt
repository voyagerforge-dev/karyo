package com.karyo.security

import com.karyo.inventory.api.dto.CreateStockUnitRequest
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.api.spi.ReceiveStockRequest
import com.karyo.inventory.api.spi.StockReceiver
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.inventory.service.StockService
import com.karyo.inventory.service.UnitLoadService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Attribution must be explicit or inherited — never defaulted. Falling through to 0 would
 * silently attribute a customer's goods to the SYS tenant.
 */
@QuarkusTest
class WriteAttributionTest {

    @Inject
    lateinit var unitLoadService: UnitLoadService

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var stockReceiver: StockReceiver

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    private fun typeId(): Long = unitLoadTypeRepository.listAll().first().id!!

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unit load is attributed to the explicit clientId, not the actor's`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val ul = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = 2L,
                labelId = "WA-UL-$suffix",
                unitLoadTypeId = typeId(),
                storageLocationId = 1L,
                storageLocationName = "WA-LOC",
            ),
            tenantContext,
        )

        assertThat(ul.clientId).isEqualTo(2L)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unit load creation rejects a missing owner instead of defaulting to SYS`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThatThrownBy {
            unitLoadService.create(
                CreateUnitLoadRequest(
                    clientId = 0L,
                    labelId = "WA-BAD-$suffix",
                    unitLoadTypeId = typeId(),
                    storageLocationId = 1L,
                    storageLocationName = "WA-LOC",
                ),
                tenantContext,
            )
        }.isInstanceOf(InventoryException.ValidationFailed::class.java)
            .hasMessageContaining("clientId must identify a goods owner")
    }

    @Test
    @TestSecurity(user = "owner", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an owner principal supplying another owner's clientId is rejected`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThatThrownBy {
            unitLoadService.create(
                CreateUnitLoadRequest(
                    clientId = 2L,
                    labelId = "WA-CROSS-$suffix",
                    unitLoadTypeId = typeId(),
                    storageLocationId = 1L,
                    storageLocationName = "WA-LOC",
                ),
                tenantContext,
            )
        }.isInstanceOf(InventoryException.ValidationFailed::class.java)
            .hasMessageContaining("is not permitted for this principal")
    }

    @Test
    @TestSecurity(user = "owner", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an owner principal supplying its own clientId explicitly succeeds`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val ul = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = 1L,
                labelId = "WA-SELF-$suffix",
                unitLoadTypeId = typeId(),
                storageLocationId = 1L,
                storageLocationName = "WA-LOC",
            ),
            tenantContext,
        )

        assertThat(ul.clientId).isEqualTo(1L)
    }

    @Test
    @TestSecurity(user = "owner", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an owner principal omitting clientId is attributed to its own client`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val ul = unitLoadService.create(
            CreateUnitLoadRequest(
                labelId = "WA-OWNER-$suffix",
                unitLoadTypeId = typeId(),
                storageLocationId = 1L,
                storageLocationName = "WA-LOC",
            ),
            tenantContext,
        )

        assertThat(ul.clientId).isEqualTo(1L)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an ops principal omitting clientId is rejected, not defaulted`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        assertThatThrownBy {
            unitLoadService.create(
                CreateUnitLoadRequest(
                    labelId = "WA-OPS-$suffix",
                    unitLoadTypeId = typeId(),
                    storageLocationId = 1L,
                    storageLocationName = "WA-LOC",
                ),
                tenantContext,
            )
        }.isInstanceOf(InventoryException.ValidationFailed::class.java)
            .hasMessageContaining("clientId is required for an ops principal")
    }

    @Test
    @TestSecurity(user = "sys", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `a principal with no client of its own omitting clientId is rejected, not attributed to SYS`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 0L
        tenantContext.principalKind = PrincipalKind.OWNER

        assertThatThrownBy {
            unitLoadService.create(
                CreateUnitLoadRequest(
                    labelId = "WA-SYS-$suffix",
                    unitLoadTypeId = typeId(),
                    storageLocationId = 1L,
                    storageLocationName = "WA-LOC",
                ),
                tenantContext,
            )
        }.isInstanceOf(InventoryException.ValidationFailed::class.java)
            .hasMessageContaining("clientId must identify a goods owner")
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `stock unit inherits its owner from the parent unit load`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val ul = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = 2L,
                labelId = "WA-INH-$suffix",
                unitLoadTypeId = typeId(),
                storageLocationId = 1L,
                storageLocationName = "WA-LOC",
            ),
            tenantContext,
        )

        val su = stockService.createStock(
            CreateStockUnitRequest(
                unitLoadId = ul.id!!,
                itemDataId = 1L,
                itemDataNumber = "WA-SKU-$suffix",
                amount = BigDecimal("3"),
            ),
            tenantContext,
        )

        // Inherited from the unit load (owner 2), NOT from the acting principal (client 1).
        assertThat(su.clientId).isEqualTo(2L)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `changeClient is the one sanctioned reassignment of attribution - OPS moves goods to the target owner`() {
        // Everywhere else in this class attribution is set once (explicit or inherited) and
        // never reassigned. changeClient is the documented exception to that invariant:
        // an OPS principal reassigns a whole unit load — and its stock — to a new owner.
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val ul = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = 1L,
                labelId = "WA-CC-$suffix",
                unitLoadTypeId = typeId(),
                storageLocationId = 1L,
                storageLocationName = "WA-LOC",
            ),
            tenantContext,
        )
        val su = stockService.createStock(
            CreateStockUnitRequest(
                unitLoadId = ul.id!!,
                itemDataId = 1L,
                itemDataNumber = "WA-CC-SKU-$suffix",
                amount = BigDecimal("3"),
            ),
            tenantContext,
        )

        val changed = unitLoadService.changeClient(ul.id!!, 2L, "WA-CC", tenantContext)

        assertThat(changed.clientId).isEqualTo(2L)
        assertThat(stockService.findById(su.id!!, tenantContext).clientId).isEqualTo(2L)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `receiving refuses to reuse another owner's unit load`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val label = "WA-REUSE-$suffix"
        // A unit load owned by client 2, sitting at the receiving location, in a
        // REUSABLE state (INCOMING) — built via stockReceiver.receive (not
        // unitLoadService.create directly) so the state term in validateReusable is
        // actually satisfied. A unit load built via create() alone persists at
        // StockState.UNDEFINED(0), which is never in REUSABLE_UL_STATES — that would
        // make the owner check unreachable and the "cross-owner" assertion below pass
        // for the wrong reason (state rejection, not owner rejection).
        stockReceiver.receive(
            ReceiveStockRequest(
                clientId = 2L,
                itemDataId = 1L,
                itemDataNumber = "WA-SKU-$suffix-OWNER",
                amount = BigDecimal("5"),
                locationId = 1L,
                locationName = "WA-LOC",
                unitLoadLabel = label,
            ),
        )

        // A receipt for client 3 must NOT be allowed to reuse it — that would attribute
        // client 3's goods to client 2.
        assertThatThrownBy {
            stockReceiver.receive(
                ReceiveStockRequest(
                    clientId = 3L,
                    itemDataId = 1L,
                    itemDataNumber = "WA-SKU-$suffix",
                    amount = BigDecimal("5"),
                    locationId = 1L,
                    locationName = "WA-LOC",
                    unitLoadLabel = label,
                ),
            )
        }.isInstanceOf(InventoryException.ValidationFailed::class.java)
            .hasMessageContaining("not reusable here")
    }
}
