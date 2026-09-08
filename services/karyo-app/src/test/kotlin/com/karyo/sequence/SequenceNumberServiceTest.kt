package com.karyo.sequence

import com.karyo.sequence.domain.SequenceNumberState
import com.karyo.sequence.exception.SequenceException
import com.karyo.sequence.generator.FormattedCounterGenerator
import com.karyo.sequence.repository.SequenceNumberStateRepository
import com.karyo.sequence.spi.SequenceNumberGenerator
import com.karyo.sequence.spi.SequenceSpec
import com.karyo.sequence.util.CheckDigit
import io.mockk.every
import io.mockk.mockk
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [SequenceNumberService] tests: the retry/exhaustion/boot-validation mechanics are plain unit
 * tests (direct construction with a stubbed `Instance`, mirroring `WorkDispatchServiceTest`'s
 * `inst` idiom); the generator-specific behavior (TIMESTAMP_RANDOM shape, FORMATTED_COUNTER
 * persistence) needs the real CDI beans + DB, hence `@QuarkusTest` for the whole class.
 *
 * `sequence_numbers.name` is UNIQUE and [FormattedCounterGenerator.generate] commits its own
 * `REQUIRES_NEW` transaction independent of this test's rollback boundary, so every FORMATTED_
 * COUNTER test seeds a `System.nanoTime()`-suffixed name to avoid cross-test / cross-run collisions
 * on the shared test DB (see AGENTS.md's "shared test DB" gotcha).
 */
@QuarkusTest
class SequenceNumberServiceTest {

    @Inject
    lateinit var sequenceNumberService: SequenceNumberService

    @Inject
    lateinit var formattedCounterGenerator: FormattedCounterGenerator

    @Inject
    lateinit var sequenceNumberStateRepository: SequenceNumberStateRepository

    private fun uniqueName(label: String) = "$label.${System.nanoTime()}"

    /** Persists a [SequenceNumberState] row directly, bypassing the generator's create-if-absent path. */
    private fun seedState(
        name: String,
        format: String = "%1\$04d",
        endCounter: Long = 9999,
        counter: Long = 0,
        checkDigitType: String? = null,
    ) {
        QuarkusTransaction.requiringNew().run {
            val state = SequenceNumberState().apply {
                this.name = name
                this.format = format
                this.endCounter = endCounter
                this.counter = counter
                this.checkDigitType = checkDigitType
            }
            sequenceNumberStateRepository.persist(state)
        }
    }

    /** Minimal `Instance<T>` fake backed by a list (only the iterable surface is used) — mirrors WorkDispatchServiceTest. */
    private fun <T> inst(items: List<T>): Instance<T> {
        val i = mockk<Instance<T>>(relaxed = true)
        every { i.iterator() } answers { items.toMutableList().iterator() }
        return i
    }

    /** Records call count; returns [candidates] cyclically so a caller can force N distinct/identical attempts. */
    private class RecordingGenerator(override val name: String, private val candidates: List<String>) :
        SequenceNumberGenerator {
        var calls = 0
            private set

        override fun generate(spec: SequenceSpec, attempt: Int): String {
            calls++
            return candidates[(attempt - 1) % candidates.size]
        }
    }

    // ── SequenceNumberService: retry loop / exhaustion / TooLong / boot validation (plain unit tests) ──

    @Test
    fun `next retries once per attempt against isUnique and throws Exhausted after GENERATION_ATTEMPTS`() {
        val generator = RecordingGenerator("FAKE", (1..10).map { "CAND-$it" })
        val service = SequenceNumberService(inst(listOf(generator)), SequenceConfig("FAKE"))

        assertThatThrownBy { service.next("seq", "P", clientId = 0, maxLength = 100) { false } }
            .isInstanceOf(SequenceException.Exhausted::class.java)
        assertThat(generator.calls).isEqualTo(SequenceNumberService.GENERATION_ATTEMPTS)
    }

    @Test
    fun `next returns the first candidate isUnique accepts without exhausting the retry budget`() {
        val generator = RecordingGenerator("FAKE", listOf("A", "B", "C"))
        val service = SequenceNumberService(inst(listOf(generator)), SequenceConfig("FAKE"))

        val result = service.next("seq", "P", clientId = 0, maxLength = 100) { it == "B" }

        assertThat(result).isEqualTo("B")
        assertThat(generator.calls).isEqualTo(2)
    }

    @Test
    fun `next throws TooLong when a candidate exceeds maxLength, before ever consulting isUnique`() {
        val generator = RecordingGenerator("FAKE", listOf("CANDIDATE-TOO-LONG"))
        val service = SequenceNumberService(inst(listOf(generator)), SequenceConfig("FAKE"))
        var isUniqueCalls = 0

        assertThatThrownBy {
            service.next("seq", "P", clientId = 0, maxLength = 5) { isUniqueCalls++; true }
        }.isInstanceOf(SequenceException.TooLong::class.java)
        assertThat(generator.calls).isEqualTo(1)
        assertThat(isUniqueCalls).`as`("TooLong must short-circuit before isUnique is consulted").isEqualTo(0)
    }

    @Test
    fun `validateOnStartup throws IllegalStateException when the configured generator name is unregistered`() {
        val generator = RecordingGenerator("REAL", listOf("X"))
        val service = SequenceNumberService(inst(listOf(generator)), SequenceConfig("UNKNOWN"))

        assertThatThrownBy { service.validateOnStartup(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("UNKNOWN")
    }

    @Test
    fun `validateOnStartup throws IllegalStateException when two generators share a name`() {
        val a = RecordingGenerator("DUP", listOf("A"))
        val b = RecordingGenerator("DUP", listOf("B"))
        val service = SequenceNumberService(inst(listOf(a, b)), SequenceConfig("DUP"))

        assertThatThrownBy { service.validateOnStartup(StartupEvent()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("DUP")
    }

    @Test
    fun `validateOnStartup returns normally for a valid, unique, configured generator name`() {
        val generator = RecordingGenerator("REAL", listOf("X"))
        val service = SequenceNumberService(inst(listOf(generator)), SequenceConfig("REAL"))

        service.validateOnStartup(StartupEvent())
    }

    // ── TIMESTAMP_RANDOM (default configured generator, real CDI bean via SequenceNumberService) ──

    @Test
    fun `next with the default TIMESTAMP_RANDOM generator matches prefix-epochMillis-3digit shape`() {
        val candidate = sequenceNumberService.next("ul-label", "UL", clientId = 0, maxLength = 100) { true }

        assertThat(candidate).matches("^UL-\\d{13}-\\d{3}$")
    }

    // ── FORMATTED_COUNTER (real CDI bean + DB, injected directly since it's not the configured default) ──

    @Test
    fun `FormattedCounterGenerator increments a seeded counter with a custom format across successive calls`() {
        val name = uniqueName("test.seq")
        // format is "%1$06d" only — SequenceSpec.prefix supplies the "TST" segment; the generator's
        // candidate formula is unconditionally `prefix + "-" + formattedTail`, so embedding "TST-"
        // in BOTH the format and the prefix would double it up.
        seedState(name, format = "%1\$06d")
        val spec = SequenceSpec(name, prefix = "TST", clientId = 0)

        val first = formattedCounterGenerator.generate(spec, attempt = 1)
        val second = formattedCounterGenerator.generate(spec, attempt = 1)

        assertThat(first).isEqualTo("TST-000001")
        assertThat(second).isEqualTo("TST-000002")
    }

    @Test
    fun `FormattedCounterGenerator creates a row with migration defaults on first use for an unseeded name`() {
        val name = uniqueName("test.seq.unseeded")
        val spec = SequenceSpec(name, prefix = "UL", clientId = 0)

        val first = formattedCounterGenerator.generate(spec, attempt = 1)

        assertThat(first).isEqualTo("UL-0001")
    }

    @Test
    fun `FormattedCounterGenerator wraps the counter back to zero once it reaches endCounter`() {
        val name = uniqueName("test.seq.wrap")
        seedState(name, format = "%1\$06d", endCounter = 2, counter = 1)
        val spec = SequenceSpec(name, prefix = "WRP", clientId = 0)

        val atEnd = formattedCounterGenerator.generate(spec, attempt = 1) // 1 -> 2 (== endCounter)
        val wrapped = formattedCounterGenerator.generate(spec, attempt = 1) // 2 >= endCounter -> wraps to 0

        assertThat(atEnd).isEqualTo("WRP-000002")
        assertThat(wrapped).isEqualTo("WRP-000000")
    }

    @Test
    fun `FormattedCounterGenerator with GS1_MOD10 appends a check digit that validates against the numeric tail`() {
        val name = uniqueName("test.seq.gs1")
        seedState(name, format = "%1\$04d", checkDigitType = "GS1_MOD10")
        val spec = SequenceSpec(name, prefix = "UL", clientId = 0)

        val candidate = formattedCounterGenerator.generate(spec, attempt = 1)

        assertThat(candidate).startsWith("UL-0001")
        val numericTail = candidate.filter(Char::isDigit)
        assertThat(CheckDigit.gs1Valid(numericTail))
            .`as`("the digit run of '$candidate' ('$numericTail') must validate as a GS1 mod-10 code")
            .isTrue()
    }
}
