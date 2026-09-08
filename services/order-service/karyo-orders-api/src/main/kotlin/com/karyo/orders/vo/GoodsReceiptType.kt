package com.karyo.orders.vo

/**
 * Inbound *reason* for a goods receipt -- behavioral parity with myWMS `GoodsReceiptType`, with
 * the exact legacy codes (NORMAL = 0, RETOUR = 1). Before this type the only
 * discriminator was `asnId != null` (ASN-bound) vs null (blind).
 *
 * RETOUR (customer return) carries two rules, enforced in `GoodsReceiptService`:
 *  - **No ASN binding:** a RETOUR receipt must not reference an ASN — customer
 *    returns do not arrive on supplier ASNs; they ride the existing blind path.
 *    RETOUR + `asnId` is a 422.
 *  - **Inspected by default:** a RETOUR line received without an explicit
 *    `lockType` defaults to QUALITY_FAULT(103) — returned goods are inspected
 *    before restocking. An explicit caller lockType (any allowed value) wins.
 *
 * The type is immutable after create — there is no update path.
 */
enum class GoodsReceiptType(val code: Int) {
    /** Regular inbound (supplier delivery / purchase) — the default. */
    NORMAL(0),
    /** Customer return. */
    RETOUR(1);

    companion object {
        /** Legacy-code lookup; null for an unknown code (the caller decides the failure). */
        fun fromCode(code: Int): GoodsReceiptType? = entries.firstOrNull { it.code == code }
    }
}
