package com.karyo.sequence.spi

/**
 * Immutable per-call request for one sequence-number candidate.
 *
 * @param name Sequence identity — resolves the [com.karyo.sequence.domain.SequenceNumberState]
 *   row for stateful generators (e.g. FORMATTED_COUNTER); stateless generators (e.g.
 *   TIMESTAMP_RANDOM) ignore it beyond echoing it back to callers. NOT the same thing as the
 *   configured *generator* name (`karyo.sequence.generator` / [com.karyo.sequence.SequenceConfig])
 *   — a single generator implementation serves every sequence [name] in the system.
 * @param prefix Caller-supplied string prefix threaded into every generator's output verbatim.
 * @param clientId Caller's tenant id — informational only. `sequence_numbers` has no `client_id`
 *   column (see the V2 migration KDoc): sequence names are global, matching myWMS's legacy
 *   `UNIQUE(name)` semantics (e.g. unit-load labels are globally unique, not per-tenant).
 */
data class SequenceSpec(val name: String, val prefix: String, val clientId: Long)

/**
 * Strategy SPI for producing sequence-number candidates. Implementations are registered as CDI
 * beans and selected by [name] via `karyo.sequence.generator` (see
 * [com.karyo.sequence.SequenceNumberService] for the resolution/retry mechanics).
 *
 * [generate] is called once per attempt by [com.karyo.sequence.SequenceNumberService.next], which
 * checks the returned candidate for uniqueness itself — a conforming implementation does not need
 * to guarantee uniqueness on its own, only to make each attempt likely to differ from the last
 * (e.g. TIMESTAMP_RANDOM's random suffix, FORMATTED_COUNTER's persisted counter bump). A
 * generator that returns an identical string on every [attempt] will simply exhaust the caller's
 * retry budget on a collision — that is a correctness signal, not a bug to work around here.
 */
interface SequenceNumberGenerator {
    /** Registration name, matched against `karyo.sequence.generator` / `KARYO_SEQUENCE_GENERATOR`. */
    val name: String

    /**
     * Produces one candidate for [spec]. [attempt] is 1-indexed (1..
     * [com.karyo.sequence.SequenceNumberService.GENERATION_ATTEMPTS]) — most implementations
     * ignore it (their own internal state/randomness already varies the output), but it is
     * threaded through for generators that want attempt-aware behavior (e.g. widening a random
     * range on repeated collisions).
     */
    fun generate(spec: SequenceSpec, attempt: Int): String
}
