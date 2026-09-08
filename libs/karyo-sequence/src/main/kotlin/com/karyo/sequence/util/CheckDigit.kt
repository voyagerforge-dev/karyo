package com.karyo.sequence.util

/**
 * Check-digit math for two published symbologies — standard formulas re-typed from the GS1
 * General Specifications (Mod-10, used by EAN/UPC/GTIN/SSCC) and the Code 39 symbology spec
 * (Mod-43). No derivation from any external check-digit implementation.
 *
 * GS1 Mod-10 weighting: starting from the RIGHTMOST digit of the payload (the digit immediately
 * to the left of where the check digit is appended), weights alternate 3, 1, 3, 1, .... This is
 * length-independent — the same rule applies whether the payload is 7 digits (EAN-8), 12 digits
 * (EAN-13), or 17 digits (SSCC-18).
 */
object CheckDigit {

    private const val MOD10_BASE = 10
    private const val MOD10_HIGH_WEIGHT = 3
    private const val MOD10_LOW_WEIGHT = 1

    /**
     * GS1 Mod-10 check digit for [digits] — the payload ONLY, without its own trailing check
     * digit. Weights alternate 3, 1, 3, 1, ... starting from [digits]' rightmost character.
     *
     * Verified by hand (EAN-13 `4006381333931`): payload `"400638133393"` (12 digits) — weighted
     * sum from the right is `3*3+9*1+3*3+3*1+3*3+1*1+8*3+3*1+6*3+0*1+0*3+4*1 = 89`; `89 mod 10 = 9`;
     * check digit `= 10 - 9 = 1`. Matches the known-valid EAN-13.
     *
     * Verified by hand (EAN-8 `96385074`): payload `"9638507"` (7 digits) — weighted sum
     * `= 21+0+15+8+9+6+27 = 86`; `86 mod 10 = 6`; check digit `= 10 - 6 = 4`. Matches.
     *
     * @throws IllegalArgumentException if [digits] is empty or contains a non-digit character.
     */
    fun gs1Mod10(digits: String): Int {
        require(digits.isNotEmpty() && digits.all(Char::isDigit)) {
            "gs1Mod10 requires a non-empty digit-only string, got '$digits'"
        }
        var sum = 0
        for ((indexFromRight, char) in digits.reversed().withIndex()) {
            val weight = if (indexFromRight % 2 == 0) MOD10_HIGH_WEIGHT else MOD10_LOW_WEIGHT
            sum += (char - '0') * weight
        }
        val remainder = sum % MOD10_BASE
        return if (remainder == 0) 0 else MOD10_BASE - remainder
    }

    /**
     * Validates a FULL code — payload plus its trailing check digit — via [gs1Mod10].
     *
     * @throws IllegalArgumentException if [full] has fewer than 2 characters (no room for a
     *   payload plus a check digit) or contains a non-digit character.
     */
    fun gs1Valid(full: String): Boolean {
        require(full.length >= 2 && full.all(Char::isDigit)) {
            "gs1Valid requires a digit-only string of at least 2 characters, got '$full'"
        }
        val payload = full.dropLast(1)
        val checkDigit = full.last() - '0'
        return gs1Mod10(payload) == checkDigit
    }

    private const val MOD43_MODULUS = 43

    // Code-39 charset, index = character value (0-based, 43 entries: digits, A-Z, then symbols).
    private const val CODE39_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. \$/+%"

    /**
     * Code-39 Mod-43 check character for [payload] (the encoded characters, excluding the
     * start/stop `*` delimiters and excluding the check character itself). Each character's
     * value is its index in [CODE39_CHARSET]; the check character is
     * `CODE39_CHARSET[sum(values) mod 43]`.
     *
     * Verified by hand: `"AB-123"` — `A=10, B=11, -=36, 1=1, 2=2, 3=3` — sum `= 63`;
     * `63 mod 43 = 20`; `CODE39_CHARSET[20] = 'K'`.
     *
     * @throws IllegalArgumentException if [payload] is empty or contains a character outside [CODE39_CHARSET].
     */
    fun mod43(payload: String): Char {
        require(payload.isNotEmpty()) { "mod43 requires a non-empty payload" }
        val sum = payload.sumOf { char ->
            val index = CODE39_CHARSET.indexOf(char)
            require(index >= 0) { "mod43 payload contains character '$char' outside the Code-39 charset" }
            index
        }
        return CODE39_CHARSET[sum % MOD43_MODULUS]
    }
}
