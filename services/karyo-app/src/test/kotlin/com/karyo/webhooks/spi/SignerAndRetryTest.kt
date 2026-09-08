package com.karyo.webhooks.spi

import com.karyo.webhooks.spi.impl.ExponentialBackoffRetryPolicy
import com.karyo.webhooks.spi.impl.HmacSha256Signer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SignerAndRetryTest {
    // Known-answer: HMAC-SHA256(key="secret", msg="1700000000.{\"a\":1}")
    @Test fun `hmac signer produces stable known-answer signature`() {
        val sig = HmacSha256Signer().sign("secret", 1700000000L, "{\"a\":1}")
        assertEquals(
            "sha256=49f24e537407743fa4a0242bb63b94b9a47ee99cbbe071ccd8a22550ae411686",
            sig,
        )
    }

    @Test fun `signature changes with body`() {
        val s = HmacSha256Signer()
        assertNotEquals(s.sign("k", 1L, "a"), s.sign("k", 1L, "b"))
    }

    @Test fun `exponential backoff grows and is bounded`() {
        val p = ExponentialBackoffRetryPolicy(baseSeconds = 10, capSeconds = 3600)
        assertEquals(10, p.backoffSeconds(1))
        assertEquals(20, p.backoffSeconds(2))
        assertEquals(40, p.backoffSeconds(3))
        assertEquals(3600, p.backoffSeconds(20)) // capped
    }
}
