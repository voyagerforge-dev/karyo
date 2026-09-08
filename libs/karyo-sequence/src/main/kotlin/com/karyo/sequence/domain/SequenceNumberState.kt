package com.karyo.sequence.domain

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * Persisted counter state for the FORMATTED_COUNTER generator (V2 migration). One row per
 * sequence [name], globally unique — no `client_id` (see the migration's KDoc: this matches
 * myWMS's legacy `UNIQUE(name)` `sequencenumber` table, not per-tenant scoping).
 *
 * Defaults mirror the migration's column defaults so a row created in code (rather than by SQL
 * `DEFAULT`, e.g. [com.karyo.sequence.generator.FormattedCounterGenerator]'s create-if-absent
 * path) behaves identically to a freshly-migrated row.
 */
@Entity
@Table(name = "sequence_numbers")
class SequenceNumberState : BaseEntity() {

    @Column(nullable = false, unique = true, length = 255)
    lateinit var name: String

    @Column(nullable = false)
    var counter: Long = 0

    @Column(name = "end_counter", nullable = false)
    var endCounter: Long = 9999

    /** [String.format] pattern; `%1$` is the counter, `%2$` is `LocalDate.now()` for date-bearing formats. */
    @Column(nullable = false, length = 255)
    var format: String = "%1\$04d"

    /** `null` (no check digit), `"GS1_MOD10"`, or `"MOD43"` — see `FormattedCounterGenerator.appendCheckDigit`. */
    @Column(name = "check_digit_type", length = 32)
    var checkDigitType: String? = null
}
