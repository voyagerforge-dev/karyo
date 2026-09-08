package com.karyo.inventory.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal

/**
 * Pact provider verification for inventory-service.
 *
 * Verifies that the inventory-service REST API satisfies all consumer contracts
 * published to the Pact Broker. Each @State method seeds real entity data so
 * provider verification returns actual responses matching the contract.
 */
@QuarkusTest
@Provider("inventory-service")
@PactBroker(url = "\${pact.broker.url:http://localhost:9292}")
// ignoreIoErrors stays a literal "true" -- a config-driven "${pact.ignore.io.errors:...}" form was
// verified NOT to resolve on pact-jvm 4.6.17's JUnit5 extension (getValueResolver() defaults to null,
// so the expression rethrows the original IO exception instead of applying its default). Full
// rationale in ProductPactProviderTest. CI's honesty for "broker unreachable" rests on the guaranteed
// pact-broker service container + `curl -sSf` on publish, not on this flag.
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@TestSecurity(user = "manager", roles = ["inventory-read", "inventory-write"])
@OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
class InventoryPactProviderTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    var port: Int = 0

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    // Quarkus + pact-jvm 4.6.17 known limitation -- see ProductPactProviderTest for the full writeup.
    // Real seeding happens here in @BeforeEach (CDI-injected instance); @State methods are inert
    // markers so pact-jvm still recognizes/matches every state a consumer interaction declares.
    @BeforeEach
    @Transactional
    fun setup(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", port)
        val stateNames = context?.interaction?.providerStates?.map { it.name }?.toSet() ?: emptySet()
        if ("stock units exist" in stateNames) {
            seedStockUnit(itemDataId = 100, itemDataNumber = "SKU-001", labelId = "UL-PACT-SU-${System.nanoTime()}")
        }
        if ("unit loads exist" in stateNames) {
            seedUnitLoad(labelId = "UL-PACT-UL-${System.nanoTime()}")
        }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("stock units exist")
    fun stockUnitsExist() {
        // no-op: real seeding happens in setup() above -- see the comment there.
    }

    @State("unit loads exist")
    fun unitLoadsExist() {
        // no-op: real seeding happens in setup() above -- see the comment there.
    }

    // "stock units exist for itemDataId 1" was ALSO a dead state (no consumer interaction in
    // inventory.consumer.pact.test.ts references it, same defect class as the two removed from
    // ProductPactProviderTest) -- deleted here rather than carried forward, along with its dedicated
    // seedStockUnit(itemDataId=1, ...) caller.

    private fun seedStockUnit(itemDataId: Long, itemDataNumber: String, labelId: String) {
        val ult = UnitLoadType().apply {
            name = "Euro Pallet ${System.nanoTime()}"
        }
        unitLoadTypeRepository.persist(ult)

        val ul = UnitLoad().apply {
            this.labelId = labelId
            clientId = 1
            storageLocationId = 1
            storageLocationName = "A-01-01"
            state = StockState.UNDEFINED.code
            unitLoadType = ult
        }
        unitLoadRepository.persist(ul)

        val su = StockUnit().apply {
            this.itemDataId = itemDataId
            this.itemDataNumber = itemDataNumber
            amount = BigDecimal("50.0000")
            reservedAmount = BigDecimal.ZERO
            state = StockState.ON_STOCK.code
            clientId = 1
            unitLoad = ul
        }
        stockUnitRepository.persist(su)
    }

    private fun seedUnitLoad(labelId: String) {
        val ult = UnitLoadType().apply {
            name = "Euro Pallet ${System.nanoTime()}"
        }
        unitLoadTypeRepository.persist(ult)

        val ul = UnitLoad().apply {
            this.labelId = labelId
            clientId = 1
            storageLocationId = 1
            storageLocationName = "A-01-01"
            state = StockState.UNDEFINED.code
            unitLoadType = ult
        }
        unitLoadRepository.persist(ul)
    }
}
