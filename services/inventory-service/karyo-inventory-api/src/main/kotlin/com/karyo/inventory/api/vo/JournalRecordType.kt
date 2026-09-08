package com.karyo.inventory.api.vo

enum class JournalRecordType(val code: Int) {
    CREATED(1),
    CHANGED(2),
    PICKED(3),
    TRANSFERRED(5),
    COUNTED(7),
    DELETED(8),

    // SC19 auth-event rows (Keycloak admin Events API -> InventoryJournal). These codes
    // must exist in the same release as any row carrying them: JournalResource.list calls
    // fromCode per row, and fromCode throws on an unknown code.
    LOGIN(10),
    LOGOUT(11),
    LOGIN_FAILED(12);

    companion object {
        fun fromCode(code: Int) = entries.first { it.code == code }
    }
}
