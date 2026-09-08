package com.karyo.sequence.generator

import com.karyo.sequence.domain.SequenceNumberState
import com.karyo.sequence.repository.SequenceNumberStateRepository
import com.karyo.sequence.spi.SequenceNumberGenerator
import com.karyo.sequence.spi.SequenceSpec
import com.karyo.sequence.util.CheckDigit
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.LocalDate

/**
 * myWMS `sequencenumber`-parity counter generator (`karyo.sequence.generator` value
 * `"FORMATTED_COUNTER"`). One persisted [SequenceNumberState] row per [SequenceSpec.name],
 * created on first use with the migration's defaults (counter 0, endCounter 9999, format
 * `"%1$04d"`, no check digit).
 *
 * Output shape: `"$prefix-${formattedCounter}"`, optionally with a trailing check-digit
 * character — see [appendCheckDigit].
 */
@ApplicationScoped
class FormattedCounterGenerator(
    private val repository: SequenceNumberStateRepository,
) : SequenceNumberGenerator {
    override val name = "FORMATTED_COUNTER"

    /**
     * Bumps and persists the counter for [spec] in its OWN, independent transaction
     * (`REQUIRES_NEW`) — a caller transaction that later rolls back (e.g. the receiving flow that
     * consumes the generated label) must never rewind a counter another concurrent caller may
     * already have observed skipped past. The row is pessimistic-write-locked
     * ([SequenceNumberStateRepository.findLocked]) for the duration of this new transaction so
     * concurrent bumps serialize instead of racing.
     *
     * `@Transactional` is a CDI interceptor: it only fires on calls that go THROUGH this bean's
     * proxy, so the annotation is placed directly on the SPI method itself rather than on a
     * private helper called from within it (a same-class self-invocation of `this.someHelper()`
     * would silently bypass the interceptor). [com.karyo.sequence.SequenceNumberService] holds
     * this generator as an `Instance<SequenceNumberGenerator>` element (the injected proxy, not a
     * raw `this`), so its call to [generate] correctly goes through the proxy and triggers
     * `REQUIRES_NEW`.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    override fun generate(spec: SequenceSpec, attempt: Int): String {
        val state = repository.findLocked(spec.name) ?: create(spec.name)
        state.counter = if (state.counter >= state.endCounter) 0 else state.counter + 1
        // %1$ = counter, %2$ = today's date — format strings that only reference %1$ (the
        // migration default `"%1$04d"`) simply leave the date argument unused; String.format
        // does not error on a supplied-but-unreferenced argument.
        val tail = String.format(state.format, state.counter, LocalDate.now())
        val candidate = "${spec.prefix}-$tail"
        return appendCheckDigit(candidate, state.checkDigitType)
    }

    private fun create(name: String): SequenceNumberState {
        val state = SequenceNumberState().apply { this.name = name }
        repository.persist(state)
        return state
    }

    /**
     * Appends a trailing check-digit character to [candidate] per [checkDigitType]:
     *  - `"GS1_MOD10"`: [CheckDigit.gs1Mod10] computed over every DIGIT CHARACTER of [candidate]
     *    (prefix, separators and any letters are skipped, all remaining digits taken in order) —
     *    the resulting 0-9 digit is appended to the END of the FULL candidate string (not just to
     *    the digit run).
     *  - `"MOD43"`: [CheckDigit.mod43] computed over the FULL [candidate] string (Code-39's
     *    charset already covers digits, letters, and `-`), appended as a single character.
     *  - anything else (including `null`): [candidate] is returned unchanged.
     */
    private fun appendCheckDigit(candidate: String, checkDigitType: String?): String = when (checkDigitType) {
        GS1_MOD10 -> candidate + CheckDigit.gs1Mod10(candidate.filter(Char::isDigit))
        MOD43 -> candidate + CheckDigit.mod43(candidate)
        else -> candidate
    }

    companion object {
        const val GS1_MOD10 = "GS1_MOD10"
        const val MOD43 = "MOD43"
    }
}
