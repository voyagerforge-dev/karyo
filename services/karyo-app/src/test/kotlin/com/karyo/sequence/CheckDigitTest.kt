package com.karyo.sequence

import com.karyo.sequence.util.CheckDigit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * Pure-JVM check-digit math tests — no `@QuarkusTest`, [CheckDigit] has no CDI/DB dependency.
 *
 * NOTE on the SSCC-18 vector: the task brief's specified value (`106141411234567891`, claiming
 * `gs1Mod10("10614141123456789")` should be `1`) does not check out against the standard GS1
 * Mod-10 algorithm (rightmost-payload-digit = weight 3, alternating) — hand-verified twice below
 * against the SAME algorithm that correctly reproduces the EAN-13 and EAN-8 vectors (both
 * well-known, independently-verifiable real barcodes). The correct check digit for payload
 * `10614141123456789` is `7`, not `1`; this suite uses the corrected SSCC (`...97`) rather than
 * asserting an arithmetically-inconsistent value. See task-3-report.md for detail.
 */
class CheckDigitTest {

    @Test
    fun `gs1Mod10 computes the GS1 check digit for a 12-digit EAN-13 payload`() {
        // 4006381333931 (real EAN-13): payload 400638133393, weighted sum from the right =
        // 3*3+9*1+3*3+3*1+3*3+1*1+8*3+3*1+6*3+0*1+0*3+4*1 = 89; 89 mod 10 = 9; check = 10-9 = 1.
        assertThat(CheckDigit.gs1Mod10("400638133393")).isEqualTo(1)
    }

    @Test
    fun `gs1Valid accepts the known-good EAN-13`() {
        assertThat(CheckDigit.gs1Valid("4006381333931")).isTrue()
    }

    @Test
    fun `gs1Valid rejects an EAN-13 with the wrong trailing check digit`() {
        assertThat(CheckDigit.gs1Valid("4006381333932")).isFalse()
    }

    @Test
    fun `gs1Valid accepts a known-good EAN-8`() {
        // 96385074: payload 9638507, weighted sum = 21+0+15+8+9+6+27 = 86; 86 mod 10 = 6; check = 4.
        assertThat(CheckDigit.gs1Valid("96385074")).isTrue()
    }

    @Test
    fun `gs1Mod10 on a 17-digit SSCC-style payload matches hand computation, not the brief's misstated vector`() {
        // See class KDoc: correct check digit is 7 (verified twice by hand), not the brief's
        // claimed 1. The full, correctly-checksummed SSCC-18 is 106141411234567897.
        assertThat(CheckDigit.gs1Mod10("10614141123456789")).isEqualTo(7)
        assertThat(CheckDigit.gs1Valid("106141411234567897")).isTrue()
    }

    @Test
    fun `gs1Mod10 rejects non-digit input`() {
        assertThatIllegalArgumentException().isThrownBy { CheckDigit.gs1Mod10("40063813339A") }
    }

    @Test
    fun `gs1Mod10 rejects empty input`() {
        assertThatIllegalArgumentException().isThrownBy { CheckDigit.gs1Mod10("") }
    }

    @Test
    fun `gs1Valid rejects a string too short to hold a payload plus check digit`() {
        assertThatIllegalArgumentException().isThrownBy { CheckDigit.gs1Valid("5") }
    }

    @Test
    fun `mod43 computes the Code-39 check character for a mixed alphanumeric payload`() {
        // AB-123: A=10, B=11, -=36, 1=1, 2=2, 3=3 -> sum=63 -> 63 mod 43 = 20 -> charset[20] = 'K'.
        assertThat(CheckDigit.mod43("AB-123")).isEqualTo('K')
    }

    @Test
    fun `mod43 rejects a character outside the Code-39 charset`() {
        assertThatIllegalArgumentException().isThrownBy { CheckDigit.mod43("ab-123") }
    }

    @Test
    fun `mod43 rejects empty input`() {
        assertThatIllegalArgumentException().isThrownBy { CheckDigit.mod43("") }
    }
}
