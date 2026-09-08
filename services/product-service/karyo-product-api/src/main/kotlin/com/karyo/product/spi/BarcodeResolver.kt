package com.karyo.product.spi

import com.karyo.product.dto.ProductResponse

/**
 * Strategy SPI (ADR-036 convention): resolvers are consulted in ascending [priority];
 * [supports] gates which barcodes a resolver handles, and the **first non-null** [resolve]
 * wins. The built-in resolver registers at [DEFAULT_PRIORITY] (runs last), so a custom
 * resolver at a lower value pre-empts it. Returning null = "I can't resolve this, fall
 * through to the next resolver".
 */
interface BarcodeResolver {
    fun resolve(barcode: String, clientId: Long): ProductResponse?

    /** Whether this resolver handles [barcode] at all (e.g. a GS1-only resolver). */
    fun supports(barcode: String): Boolean = true

    /** Lower runs first; the built-in resolver uses [DEFAULT_PRIORITY] (runs last). */
    fun priority(): Int = DEFAULT_PRIORITY

    /**
     * Structural pre-check (SC18), consulted BEFORE [resolve] — same chain, same [priority]
     * ascending order, first **non-null** wins, exactly mirroring the [resolve] contract.
     * Return non-null to declare [barcode] structurally invalid for a symbology this resolver
     * recognizes (e.g. a GS1 check-digit mismatch); the caller maps that into a **422** and
     * never reaches the [resolve] chain. Return null (the default) to raise no objection —
     * consultation continues to the next resolver by priority, and once every resolver has
     * passed, lookup falls through to the normal [resolve] chain (unresolved barcodes still 404).
     */
    fun structuralError(barcode: String): String? = null

    companion object {
        /** Lowest priority — the built-in resolver; custom resolvers use lower values to pre-empt. */
        const val DEFAULT_PRIORITY = Int.MAX_VALUE
    }
}
