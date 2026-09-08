package com.karyo.inventory.service

import com.karyo.auth.dto.CreateClientRequest
import com.karyo.auth.service.ClientService
import com.karyo.auth.vo.ClientState
import com.karyo.events.outbox.OutboxEvent
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.InventoryJournal
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
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
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [UnitLoadService.changeClient] — the one sanctioned exception to "attribution is never
 * reassigned by callers". Fixture idiom from [ReservationTransferTest] /
 * `CrossOwnerWriteTest`: entities seeded directly via repositories (a REST seed could not
 * create foreign-owner rows), the service called directly with a manually primed
 * [TenantContext]. Clients 1 (ACME) and 2 (GLOBEX) exist via migration V1201.
 */
@QuarkusTest
class ChangeClientTest {

    @Inject
    lateinit var unitLoadService: UnitLoadService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    @Inject
    lateinit var pickOrderRepository: PickOrderRepository

    @Inject
    lateinit var pickRepository: PickRepository

    @Inject
    lateinit var clientService: ClientService

    @Inject
    lateinit var em: EntityManager

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun suffix(): String = System.nanoTime().toString().takeLast(8)

    /**
     * Seeds a unit load owned by [owner] carrying one stock unit per entry of [amounts],
     * each with a unique SKU number so journal rows can be located per stock unit.
     * Returns the unit load id and the per-stock SKU numbers (index-aligned with [amounts]).
     */
    @Transactional
    fun seedUnitLoad(
        owner: Long,
        suffix: String,
        amounts: List<BigDecimal>,
        reservedAmounts: List<BigDecimal> = amounts.map { BigDecimal.ZERO },
        states: List<Int> = amounts.map { StockState.ON_STOCK.code },
    ): Pair<Long, List<String>> {
        val type = unitLoadTypeRepository.listAll().first()
        val ul = UnitLoad().apply {
            this.clientId = owner
            this.labelId = "CC-UL-$suffix"
            this.unitLoadType = type
            this.storageLocationId = 1L
            this.storageLocationName = "CC-LOC"
        }
        unitLoadRepository.persist(ul)
        val skus = amounts.mapIndexed { i, amount ->
            val sku = "CC-SKU-$suffix-$i"
            stockUnitRepository.persist(
                StockUnit().apply {
                    this.clientId = owner
                    this.itemDataId = 1L
                    this.itemDataNumber = sku
                    this.amount = amount
                    this.reservedAmount = reservedAmounts[i]
                    this.state = states[i]
                    this.unitLoad = ul
                }
            )
            sku
        }
        return ul.id!! to skus
    }

    /** Persists a real (non-terminal) fulfillment pick referencing [sourceStockUnitId]. */
    @Transactional
    fun seedOpenPick(sourceStockUnitId: Long, suffix: String) {
        // deliveryOrderId must not be a small literal -- this row is persisted directly
        // (bypassing REST) into the shared Testcontainers DB/JVM. A hardcoded id (e.g. 10L) can
        // collide with a REAL delivery-order id assigned later in the same full-suite run by an
        // unrelated fulfillment test, making PackingService.openPacking's
        // pickOrderRepository/shipmentRepository `findByDeliveryOrderId` lookups match this
        // stale non-terminal row instead of (or ambiguously alongside) the real one. See the
        // identical note in PickOrderPersistenceTest / ShipmentPersistenceTest / OpenPickGuardTest.
        val deliveryOrderId = System.nanoTime()
        val po = PickOrder().apply {
            clientId = 1L
            pickOrderNumber = "CC-PO-$suffix"
            this.deliveryOrderId = deliveryOrderId
            deliveryOrderNumber = "CC-ORD-$suffix"
            state = PickState.STARTED.code
        }
        pickOrderRepository.persist(po)
        pickRepository.persist(
            Pick().apply {
                clientId = 1L
                pickOrderId = po.id!!
                deliveryOrderLineId = 20L
                itemDataId = 1L
                itemDataNumber = "CC-PICK-SKU-$suffix"
                this.sourceStockUnitId = sourceStockUnitId
                plannedAmount = BigDecimal("1.000")
                state = PickState.STARTED.code
                pickingType = PickingType.PICK.name
            }
        )
    }

    /**
     * Seeds a client via [ClientService] — the only sanctioned mutation path (direct
     * entity/repository writes would bypass the outbox events the service publishes) —
     * optionally deactivating it. Mirrors [ClientServiceTest]'s
     * "deactivate then reactivate round-trips the state" pattern.
     */
    private fun seedClient(state: Int = ClientState.ACTIVE.code): Long {
        val s = suffix()
        val created = clientService.create(CreateClientRequest(name = "CC-Client-$s", number = "CC$s"))
        if (state == ClientState.INACTIVE.code) clientService.deactivate(created.id)
        return created.id
    }

    private fun primeOps() {
        tenantContext.clientId = 0L
        tenantContext.principalKind = PrincipalKind.OPS
    }

    private fun journalRowsFor(sku: String): List<InventoryJournal> =
        journalRepository.list("productNumber", sku)

    private fun clientChangedEvents(unitLoadId: Long): List<OutboxEvent> =
        outboxEvents.list(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "UnitLoad", unitLoadId, "UnitLoadClientChanged",
        )

    private fun assertUntouched(ulId: Long, skus: List<String>, owner: Long = 1L) {
        assertThat(unitLoadRepository.findById(ulId)!!.clientId).isEqualTo(owner)
        val stocks = stockUnitRepository.findByUnitLoadId(ulId)
        assertThat(stocks).allSatisfy { assertThat(it.clientId).isEqualTo(owner) }
        skus.forEach { sku -> assertThat(journalRowsFor(sku)).isEmpty() }
        assertThat(clientChangedEvents(ulId)).isEmpty()
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `findByIdForWrite acquires a pessimistic write lock`() {
        val sfx = suffix()
        val (ulId, _) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10")))
        primeOps()

        // Inside the test tx, load then ask the EntityManager what lock it holds.
        QuarkusTransaction.requiringNew().run {
            val ul = unitLoadService.findByIdForWrite(ulId, tenantContext)
            assertThat(em.getLockMode(ul)).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
        }
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `happy path - unit load and both stock units move to the new owner with paired journal rows and paired events`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10"), BigDecimal("5")))
        primeOps()

        val changed = unitLoadService.changeClient(ulId, 2L, "CC-TEST", tenantContext)

        assertThat(changed.clientId).isEqualTo(2L)
        assertThat(unitLoadRepository.findById(ulId)!!.clientId).isEqualTo(2L)
        val stocks = stockUnitRepository.findByUnitLoadId(ulId)
        assertThat(stocks).hasSize(2).allSatisfy { assertThat(it.clientId).isEqualTo(2L) }

        // Paired journal rows per stock unit — 4 total. The clientId assertions pin the
        // write ordering: DELETED before reassignment (old owner), CREATED after (new owner).
        skus.forEach { sku ->
            val rows = journalRowsFor(sku)
            assertThat(rows).hasSize(2)
            val deleted = rows.single { it.recordType == JournalRecordType.DELETED.code }
            assertThat(deleted.clientId).isEqualTo(1L)
            assertThat(deleted.fromUnitLoad).isEqualTo("CC-UL-$sfx")
            assertThat(deleted.activityCode).isEqualTo("CC-TEST")
            val created = rows.single { it.recordType == JournalRecordType.CREATED.code }
            assertThat(created.clientId).isEqualTo(2L)
            assertThat(created.toUnitLoad).isEqualTo("CC-UL-$sfx")
        }

        // Published under BOTH owners — the fanout matches s.clientId == e.tenantId.
        val events = clientChangedEvents(ulId)
        assertThat(events).hasSize(2)
        assertThat(events.map { it.tenantId }).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    @TestSecurity(user = "owner1", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `OWNER principal gets 403 even for its own unit load, and nothing changes`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10")))

        // Through REST, so the endpoint wiring and the Forbidden -> 403 mapping are pinned.
        // No principal_kind claim -> OWNER; the gate fires before any row load.
        given()
            .contentType(ContentType.JSON)
            .body("""{"targetClientId":2}""")
            .`when`().post("/api/v1/unit-loads/$ulId/change-client")
            .then().statusCode(403)

        assertUntouched(ulId, skus)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `target 0 and nonexistent target are both rejected as invalid targets`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10")))
        primeOps()

        assertThatThrownBy { unitLoadService.changeClient(ulId, 0L, null, tenantContext) }
            .isInstanceOf(InventoryException.InvalidTarget::class.java)
            .hasMessageContaining("SYS")

        // First production caller of ClientLookup.exists — proves the cross-module wiring.
        assertThatThrownBy { unitLoadService.changeClient(ulId, 999_999_999L, null, tenantContext) }
            .isInstanceOf(InventoryException.InvalidTarget::class.java)
            .hasMessageContaining("999999999")

        assertUntouched(ulId, skus)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `changeClient refuses an INACTIVE target owner`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10")))
        primeOps()
        val retiredId = seedClient(state = ClientState.INACTIVE.code)

        assertThatThrownBy { unitLoadService.changeClient(ulId, retiredId, null, tenantContext) }
            .isInstanceOf(InventoryException.InvalidTarget::class.java)
            .hasMessageContaining("retired")

        assertUntouched(ulId, skus)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `a reservation on ANY stock unit refuses the whole operation and nothing changes`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(
            owner = 1L,
            suffix = sfx,
            amounts = listOf(BigDecimal("10"), BigDecimal("5")),
            reservedAmounts = listOf(BigDecimal.ZERO, BigDecimal("2")),
        )
        primeOps()

        assertThatThrownBy { unitLoadService.changeClient(ulId, 2L, null, tenantContext) }
            .isInstanceOf(InventoryException.Encumbered::class.java)
            .hasMessageContaining("reserved")

        // Totality: BOTH stock units and the unit load untouched, no journals, no events.
        assertUntouched(ulId, skus)
        val stocks = stockUnitRepository.findByUnitLoadId(ulId).sortedBy { it.id }
        assertThat(stocks[0].amount).isEqualByComparingTo(BigDecimal("10"))
        assertThat(stocks[1].amount).isEqualByComparingTo(BigDecimal("5"))
        assertThat(stocks[1].reservedAmount).isEqualByComparingTo(BigDecimal("2"))
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `an open pick on one stock unit refuses the whole operation and nothing changes`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10"), BigDecimal("5")))
        // A REAL pick row (fulfillment repositories, non-terminal STARTED state) — no stub.
        val firstStockId = stockUnitRepository.findByUnitLoadId(ulId).minOf { it.id!! }
        seedOpenPick(firstStockId, sfx)
        primeOps()

        assertThatThrownBy { unitLoadService.changeClient(ulId, 2L, null, tenantContext) }
            .isInstanceOf(InventoryException.Encumbered::class.java)
            .hasMessageContaining("pick")

        assertUntouched(ulId, skus)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `a PICKED-state stock unit refuses the whole operation even with no reservation and no open pick`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(
            owner = 1L,
            suffix = sfx,
            amounts = listOf(BigDecimal("10"), BigDecimal("5")),
            states = listOf(StockState.ON_STOCK.code, StockState.PICKED.code),
        )
        primeOps()

        // Neither existing guard fires: no reservation, no open pick row for this stock unit.
        assertThatThrownBy { unitLoadService.changeClient(ulId, 2L, null, tenantContext) }
            .isInstanceOf(InventoryException.Encumbered::class.java)
            .hasMessageContaining("state")

        // Totality: BOTH stock units and the unit load untouched, no journals, no events.
        assertUntouched(ulId, skus)
        val stocks = stockUnitRepository.findByUnitLoadId(ulId).sortedBy { it.id }
        assertThat(stocks[0].state).isEqualTo(StockState.ON_STOCK.code)
        assertThat(stocks[1].state).isEqualTo(StockState.PICKED.code)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `same-client change is a silent no-op - success with zero journal rows and zero events`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10")))
        primeOps()

        val result = unitLoadService.changeClient(ulId, 1L, null, tenantContext)

        assertThat(result.clientId).isEqualTo(1L)
        skus.forEach { sku -> assertThat(journalRowsFor(sku)).isEmpty() }
        assertThat(clientChangedEvents(ulId)).isEmpty()
    }

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "tenant_code", value = "SYS")])
    fun `zero-amount stock unit is cascaded but gets no journal rows`() {
        val sfx = suffix()
        val (ulId, skus) = seedUnitLoad(owner = 1L, suffix = sfx, amounts = listOf(BigDecimal("10"), BigDecimal.ZERO))
        primeOps()

        unitLoadService.changeClient(ulId, 2L, null, tenantContext)

        val stocks = stockUnitRepository.findByUnitLoadId(ulId)
        // Cascaded: BOTH stock units carry the new owner, including the empty one.
        assertThat(stocks).hasSize(2).allSatisfy { assertThat(it.clientId).isEqualTo(2L) }
        // myWMS parity: journal rows only where goods actually moved.
        assertThat(journalRowsFor(skus[0])).hasSize(2)
        assertThat(journalRowsFor(skus[1])).isEmpty()
        // The event still counts every stock unit on the load.
        assertThat(clientChangedEvents(ulId)).hasSize(2)
    }
}
