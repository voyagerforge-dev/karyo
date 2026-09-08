package com.karyo.sequence

import com.karyo.sequence.exception.SequenceException
import com.karyo.sequence.spi.SequenceNumberGenerator
import com.karyo.sequence.spi.SequenceSpec
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Resolves the ACTIVE [SequenceNumberGenerator] by name against
 * [SequenceConfig.generatorName] and drives a bounded conflict-check retry loop over it.
 * Mirrors `com.karyo.work.service.WorkDispatchService`'s shape: strict-by-name resolution, a
 * boot-time [validateOnStartup] check (unknown/duplicate names fail app boot, never a silent
 * fallback to a different registered generator), and dispatch-time resolution kept as the belt.
 */
@ApplicationScoped
class SequenceNumberService @Inject constructor(
    private val generators: Instance<SequenceNumberGenerator>,
    private val config: SequenceConfig,
) {

    /** Fails app boot when `karyo.sequence.generator` names no registered bean, or two beans share a name. */
    fun validateOnStartup(@Observes ignored: StartupEvent) {
        generator()
        checkForDuplicateGeneratorNames()
    }

    private fun checkForDuplicateGeneratorNames() {
        val duplicates = generators.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            error(
                "Duplicate SequenceNumberGenerator name(s) $duplicates -- two beans registering " +
                    "the same name resolve in CDI discovery order; rename one so selection is deterministic.",
            )
        }
    }

    private fun generator(): SequenceNumberGenerator =
        generators.firstOrNull { it.name == config.generatorName }
            ?: error(
                "No SequenceNumberGenerator registered for name '${config.generatorName}' " +
                    "(karyo.sequence.generator / KARYO_SEQUENCE_GENERATOR) -- refusing to silently " +
                    "fall back to a different registered generator; register a bean whose `name` " +
                    "matches, or fix the configured value.",
            )

    /**
     * Generates a unique sequence number for [name]/[prefix]/[clientId], retrying up to
     * [GENERATION_ATTEMPTS] times against [isUnique] (the caller's own conflict check — e.g. "no
     * existing row with this value"). A candidate longer than [maxLength] fails immediately with
     * [SequenceException.TooLong] (retrying would produce an equally-too-long candidate from a
     * deterministic-length generator, so there's nothing a retry could fix). Exhausting every
     * attempt without a unique candidate throws [SequenceException.Exhausted].
     */
    fun next(name: String, prefix: String, clientId: Long, maxLength: Int, isUnique: (String) -> Boolean): String {
        val spec = SequenceSpec(name, prefix, clientId)
        val active = generator()
        for (attempt in 1..GENERATION_ATTEMPTS) {
            val candidate = active.generate(spec, attempt)
            if (candidate.length > maxLength) throw SequenceException.TooLong(name, candidate.length, maxLength)
            if (isUnique(candidate)) return candidate
        }
        throw SequenceException.Exhausted(name, GENERATION_ATTEMPTS)
    }

    companion object {
        const val GENERATION_ATTEMPTS = 5
    }
}
