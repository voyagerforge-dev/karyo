package com.karyo.sequence.generator

import com.karyo.sequence.spi.SequenceNumberGenerator
import com.karyo.sequence.spi.SequenceSpec
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default generator (`karyo.sequence.generator` default `"TIMESTAMP_RANDOM"`). Stateless — no
 * `sequence_numbers` row, no persistence, no lock contention. Output shape:
 * `"$prefix-${epochMillis}-${random 3-digit suffix}"`, e.g. `"UL-1733865600123-042"`.
 *
 * Collision probability is low (millisecond timestamp + 900-way random suffix) but non-zero under
 * concurrent callers within the same millisecond — that's exactly what
 * [com.karyo.sequence.SequenceNumberService]'s retry loop exists to absorb; this generator does
 * not need to guarantee uniqueness on its own.
 */
@ApplicationScoped
class TimestampRandomGenerator : SequenceNumberGenerator {
    override val name = "TIMESTAMP_RANDOM"

    override fun generate(spec: SequenceSpec, attempt: Int): String =
        "${spec.prefix}-${System.currentTimeMillis()}-${(MIN_SUFFIX..MAX_SUFFIX).random()}"

    companion object {
        const val MIN_SUFFIX = 100
        const val MAX_SUFFIX = 999
    }
}
