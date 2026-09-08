package com.karyo.webhooks.service

import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Plain JUnit — no Quarkus container needed. Instantiate the validator directly. */
class WebhookUrlValidatorTest {

    private val validator = WebhookUrlValidator()

    @Test
    fun `metadata IP 169_254_169_254 is rejected`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate("http://169.254.169.254/latest/meta-data")
        }
    }

    @Test
    fun `loopback 127_0_0_1 is rejected`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate("http://127.0.0.1/x")
        }
    }

    @Test
    fun `private range 10_0_0_5 is rejected`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate("https://10.0.0.5/hook")
        }
    }

    @Test
    fun `file scheme is rejected`() {
        assertThrows(BadRequestException::class.java) {
            validator.validate("file:///etc/passwd")
        }
    }

    @Test
    fun `public IP literal 93_184_216_34 is allowed`() {
        // 93.184.216.34 is a well-known public IP (example.com) — no DNS lookup needed
        assertDoesNotThrow {
            validator.validate("https://93.184.216.34/x")
        }
    }

    @Test
    fun `non-resolvable acme_test hostname is allowed (offline-DNS rule)`() {
        // acme.test does not exist in DNS — resolver will throw UnknownHostException → allow
        assertDoesNotThrow {
            validator.validate("https://acme.test/h")
        }
    }

    @Test
    fun `trailing-dot loopback 127_0_0_1_dot is rejected`() {
        // "127.0.0.1." — trailing dot bypasses dotted-decimal regex without the trimEnd fix
        assertThrows(BadRequestException::class.java) {
            validator.validate("http://127.0.0.1./")
        }
    }
}
