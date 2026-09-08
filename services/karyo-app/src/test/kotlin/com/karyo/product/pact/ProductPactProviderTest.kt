package com.karyo.product.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemDataNumber
import com.karyo.product.domain.model.ItemUnit
import com.karyo.product.domain.model.PackagingUnit
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.product.vo.ItemDataState
import com.karyo.product.vo.ItemUnitType
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Pact provider verification for product-service.
 *
 * Verifies that the product-service REST API satisfies all consumer contracts
 * published to the Pact Broker. Each @State method seeds real entity data so
 * provider verification returns actual responses matching the contract.
 */
@QuarkusTest
@Provider("product-service")
@PactBroker(url = "\${pact.broker.url:http://localhost:9292}")
// ignoreIoErrors is intentionally a literal "true", NOT "${pact.ignore.io.errors:...}".
// Empirically verified (real local broker run, 2026-07-31): PactVerificationInvocationContextProvider
// only expression-resolves an annotation string when a non-null ValueResolver is available, but its
// own getValueResolver(ExtensionContext) hook returns null unless a test subclasses and overrides it --
// none of these provider tests do. With an unresolved "${...}" string, the io-error handler's literal
// `ignoreIoErrors() == "true"` short-circuit fails, expression parsing is attempted against a null
// resolver, and the original IO exception is rethrown instead of the default applying -- confirmed by
// running these 4 classes broker-less with an earlier "${...}" form: every test failed hard on
// connection-refused instead of passing. So CI's honesty for the "broker unreachable" case rests on
// the guaranteed pact-broker service container + `curl -sSf` on publish, not on this flag; local/dev
// runs stay lenient (silent pass on IO error) via the hardcoded "true".
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@TestSecurity(user = "manager", roles = ["product-read", "product-write"])
@OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
class ProductPactProviderTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    var port: Int = 0

    @Inject
    lateinit var itemUnitRepository: ItemUnitRepository

    @Inject
    lateinit var itemDataRepository: ItemDataRepository

    // Quarkus + pact-jvm 4.6.17 known limitation (open as of 2026-07-31, confirmed empirically against
    // a real broker): @State-annotated methods below run on a test-class instance pact-jvm builds via
    // reflection in a different classloader than the one Quarkus/Arc injects, so @Inject fields there
    // are always null / UninitializedPropertyAccessException. Upstream: quarkiverse/quarkus-pact#2,
    // quarkusio/quarkus#22611 (both still open; the linked reproducer confirms the same failure mode
    // in Java). Community-documented workaround: do the actual CDI-backed seeding here in @BeforeEach,
    // which DOES run on the correctly-injected instance, keyed off the interaction's provider-state
    // name(s); the @State methods stay as required-but-inert markers so pact-jvm still recognizes and
    // matches every state a consumer interaction declares.
    @BeforeEach
    @Transactional
    fun setup(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", port)
        val stateNames = context?.interaction?.providerStates?.map { it.name }?.toSet() ?: emptySet()
        if ("products exist" in stateNames) seedProduct(number = "SKU-PACT-${System.nanoTime()}")
        if ("item units exist" in stateNames) seedItemUnitOnly()
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("products exist")
    fun productsExist() {
        // no-op: real seeding happens in setup() above -- see the comment there.
    }

    @State("item units exist")
    fun itemUnitsExist() {
        // no-op: real seeding happens in setup() above -- see the comment there.
    }

    // item_units.name is VARCHAR(20) (product migration V201) and UNIQUE, so the seed value has to
    // be BOTH bounded and distinct. It used to be "Piece ${System.nanoTime()}": on Linux
    // System.nanoTime() is CLOCK_MONOTONIC -- nanoseconds since BOOT -- so it renders under 14
    // digits, and the row under 20 characters, only while the host has been up less than ~27.8
    // hours. On any longer-lived machine it is 15-16 digits and the insert dies with
    // "value too long for type character varying(20)" -- measured at 21 characters on a host up
    // 3d19h (2026-08-28), 22 on one up 2w3d. A counter is 16 characters whatever the uptime, and
    // unlike a truncated random UUID it cannot collide with the UNIQUE index: the test database is
    // ephemeral and only this JVM writes to it, and the six names V201 seeds (PCS/KG/L/M/BOX/PAL)
    // share no prefix with it. The counter MUST be static -- JUnit builds a fresh test-class
    // instance per interaction, so an instance field would restart at 1 and collide on the second.
    private fun uniqueUnitName(): String = "Piece-PACT-%05d".format(unitNameSeq.incrementAndGet())

    private fun seedItemUnitOnly() {
        val itemUnit = ItemUnit().apply {
            name = uniqueUnitName()
            unitType = ItemUnitType.PIECE
        }
        itemUnitRepository.persist(itemUnit)
    }

    private fun seedProduct(number: String) {
        val itemUnit = ItemUnit().apply {
            name = uniqueUnitName()
            unitType = ItemUnitType.PIECE
        }
        itemUnitRepository.persist(itemUnit)

        val itemData = ItemData().apply {
            this.number = number
            name = "Test Product"
            state = ItemDataState.ACTIVE.code
            clientId = 1
            this.itemUnit = itemUnit
        }
        // The consumer pact's "paginated products" response matcher requires content[0].numbers and
        // content[0].packagingUnits to each have at least one entry (eachLike(...) with min:1) --
        // both must be seeded here, not just the bare ItemData, or verification 200s with the wrong
        // shape instead of matching. Cascade = ALL on ItemData.numbers/packagingUnits persists them
        // when itemData is persisted below.
        val number1 = ItemDataNumber().apply {
            this.itemData = itemData
            this.number = "EAN-PACT-${System.nanoTime()}"
            index = 0
        }
        itemData.numbers.add(number1)

        val packagingUnit = PackagingUnit().apply {
            this.itemData = itemData
            name = "Box"
            amount = BigDecimal("12.0000")
            this.itemUnit = itemUnit
            packingLevel = 1
        }
        itemData.packagingUnits.add(packagingUnit)

        itemDataRepository.persist(itemData)
    }

    companion object {
        /** Backs [uniqueUnitName]; see the comment there for why it is static. */
        private val unitNameSeq = AtomicInteger(0)
    }
}
