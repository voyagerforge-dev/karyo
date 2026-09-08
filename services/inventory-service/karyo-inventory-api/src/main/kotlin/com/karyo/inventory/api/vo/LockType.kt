package com.karyo.inventory.api.vo

/**
 * Stock-unit lock types — behavioral parity with myWMS's stock lock types (independent
 * implementation).
 *
 * ⚠️ A DIFFERENT `com.karyo.layout.vo.LockType` exists for storage locations, and
 * the two do NOT agree: they share only 0/1/7. Codes 2 (QUARANTINE) and 8 (DAMAGE)
 * are LOCATION-only; codes 103/202/203/405 are STOCK-only. Passing a code across the
 * boundary makes the other enum's [fromCode] throw — never mix them. Receiving
 * (`ReceiveLineRequest.lockType`) uses THIS enum.
 *
 * SHIPPED(405): parity value, no Karyo writer yet — myWMS sets it on dispatch; Karyo's
 * dispatch path predates it (honest gap, see WORKLIST).
 */
enum class LockType(val code: Int) {
    UNLOCKED(0),
    GENERAL(1),
    STOCKTAKING(7),
    QUALITY_FAULT(103),
    LOT_EXPIRED(202),
    LOT_TOO_YOUNG(203),
    SHIPPED(405);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown lock type: $code")
    }
}
