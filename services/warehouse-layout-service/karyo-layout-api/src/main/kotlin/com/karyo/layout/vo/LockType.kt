package com.karyo.layout.vo

/**
 * Storage-location lock types — a Karyo-original set (QUARANTINE/DAMAGE exist
 * nowhere in myWMS, which had a single stock-side LockType).
 *
 * ⚠️ A DIFFERENT `com.karyo.inventory.api.vo.LockType` exists for stock units, and
 * the two do NOT agree: they share only 0/1/7. Codes 2 (QUARANTINE) and 8 (DAMAGE)
 * are LOCATION-only; codes 103/202/203/405 (QUALITY_FAULT/LOT_EXPIRED/LOT_TOO_YOUNG/SHIPPED)
 * are STOCK-only. Passing a code across the boundary makes the other enum's
 * [fromCode] throw — never mix them.
 */
enum class LockType(val code: Int) {
    UNLOCKED(0),
    GENERAL(1),
    QUARANTINE(2),
    STOCKTAKING(7),
    DAMAGE(8);

    companion object {
        fun fromCode(code: Int): LockType =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown LockType code: $code")
    }
}
