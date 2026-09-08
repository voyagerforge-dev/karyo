package com.karyo.auth.vo

/**
 * Lifecycle state of a goods owner. Numeric codes follow the myWMS convention
 * (`Client.state`, default 100) so migrated data maps across unchanged.
 *
 * Only the two states Karyo actually uses are defined. Clients are never deleted —
 * 26 entity types may reference one and there are no foreign keys to tell us whether
 * removal is safe — so [INACTIVE] is the retirement path.
 */
enum class ClientState(val code: Int) {
    ACTIVE(100),
    INACTIVE(900);

    companion object {
        fun fromCode(code: Int): ClientState =
            entries.firstOrNull { it.code == code } ?: ACTIVE
    }
}
