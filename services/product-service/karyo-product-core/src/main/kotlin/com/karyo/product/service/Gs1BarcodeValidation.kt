package com.karyo.product.service

import com.karyo.sequence.util.CheckDigit

/**
 * SC18: GS1 Mod-10 check-digit enforcement for barcode writes ([ProductService.addBarcode]).
 *
 * Only applies when `numberType` normalizes (case-insensitively) to a recognized GS1 symbology
 * — an unrecognized or absent `numberType` is free-form and untouched, preserving parity with
 * pre-SC18 behavior. [CheckDigit.gs1Valid] throws `IllegalArgumentException` on non-digit input
 * or a string shorter than 2 characters, so [violation] pre-filters digits-only + allowed length
 * BEFORE calling it — the IAE must never reach a caller as an unhandled 500.
 */
internal object Gs1BarcodeValidation {

    /** `numberType` (uppercased, hyphens preserved) -> the GS1 payload+check-digit lengths it accepts. */
    private val GS1_LENGTHS: Map<String, Set<Int>> = mapOf(
        "EAN13" to setOf(13),
        "EAN-13" to setOf(13),
        "EAN8" to setOf(8),
        "EAN-8" to setOf(8),
        "EAN" to setOf(8, 13),
        "UPC" to setOf(12),
        "UPCA" to setOf(12),
        "UPC-A" to setOf(12),
        "GTIN" to setOf(8, 12, 13, 14),
        "GTIN14" to setOf(8, 12, 13, 14),
        "GTIN-14" to setOf(8, 12, 13, 14),
        "SSCC" to setOf(18),
    )

    /**
     * Returns a human-readable violation message if [number] fails GS1 structural or
     * check-digit validation for [numberType], or null if [numberType] doesn't map to a
     * recognized GS1 symbology (free-form parity) or [number] is valid.
     */
    fun violation(numberType: String?, number: String): String? {
        val lengths = GS1_LENGTHS[numberType?.trim()?.uppercase()] ?: return null
        val allowedLengths = lengths.sorted().joinToString(" or ")

        if (!number.all(Char::isDigit)) {
            return "Barcode '$number' for type '$numberType' must contain digits only " +
                "(GS1 $allowedLengths-digit format)"
        }
        if (number.length !in lengths) {
            return "Barcode '$number' for type '$numberType' must be $allowedLengths digits long, " +
                "got ${number.length}"
        }
        if (!CheckDigit.gs1Valid(number)) {
            val expected = CheckDigit.gs1Mod10(number.dropLast(1))
            return "Barcode '$number' has an invalid GS1 check digit for type '$numberType': " +
                "expected $expected, got '${number.last()}'"
        }
        return null
    }
}
