package com.karyo.sequence.exception

import com.karyo.common.exception.KaryoException

/**
 * Sequence-generation failures. Both variants are caller-input problems (a collision-prone
 * `prefix`/generator choice, or a `maxLength` too small for the configured format) rather than
 * system faults, so both map to 422 — see [SequenceExceptionMapper], which copies the per-subtype
 * `when`-mapped-to-status shape used by `InventoryExceptionMapper` for `InventoryException`.
 */
sealed class SequenceException(message: String) : KaryoException(message) {

    /** [com.karyo.sequence.SequenceNumberService.next] tried every attempt and every candidate collided. */
    class Exhausted(val name: String, val attempts: Int) :
        SequenceException("Sequence '$name' failed to produce a unique candidate after $attempts attempts")

    /** A generated candidate exceeded the caller-supplied `maxLength` — thrown before the uniqueness check. */
    class TooLong(val name: String, val length: Int, val maxLength: Int) :
        SequenceException("Sequence '$name' candidate length $length exceeds maxLength $maxLength")
}
