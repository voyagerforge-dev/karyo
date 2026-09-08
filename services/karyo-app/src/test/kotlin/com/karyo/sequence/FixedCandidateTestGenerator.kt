package com.karyo.sequence

import com.karyo.sequence.spi.SequenceNumberGenerator
import com.karyo.sequence.spi.SequenceSpec
import jakarta.enterprise.context.ApplicationScoped

/**
 * Test-only [SequenceNumberGenerator], selected via `karyo.sequence.generator=FIXED_TEST`
 * ([SequenceConflictWiringTest]'s `@TestProfile`). Ignores [spec] and [attempt] entirely and
 * always returns the SAME literal candidate — so a real production call whose target repository
 * already carries a row with that exact value is GUARANTEED to collide on every one of
 * [SequenceNumberService.GENERATION_ATTEMPTS] attempts, driving `next` to
 * [com.karyo.sequence.exception.SequenceException.Exhausted] rather than a lucky distinct retry.
 *
 * That determinism is the whole point of [SequenceConflictWiringTest]: it proves the REAL
 * `isUnique` predicate wired into `PickOrderService`/`ExtinguishService`/`PackingService` is
 * actually being consulted (not neutered to `{ true }`) — a neutered predicate would accept the
 * first (colliding) candidate immediately, and the ensuing `persist()` would then violate the
 * target table's own `UNIQUE` constraint instead of cleanly 422ing — a different, also-non-2xx
 * outcome that still fails those tests' specific `sequence-exhausted` assertion, so the mutation
 * is killed either way.
 *
 * Registered unconditionally (like every other [SequenceNumberGenerator] bean, e.g.
 * [com.karyo.sequence.generator.TimestampRandomGenerator]) but only ever SELECTED when
 * `karyo.sequence.generator` names it — harmless everywhere else, per
 * [SequenceNumberService]'s strict-by-name resolution.
 */
@ApplicationScoped
class FixedCandidateTestGenerator : SequenceNumberGenerator {
    override val name = "FIXED_TEST"

    override fun generate(spec: SequenceSpec, attempt: Int): String = FIXED_CANDIDATE

    companion object {
        const val FIXED_CANDIDATE = "SC17-FIXED-TEST-CANDIDATE"
    }
}
