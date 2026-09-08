package com.karyo.webhooks.spi.impl

import com.karyo.webhooks.spi.WebhookSigner
import jakarta.enterprise.context.ApplicationScoped
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ApplicationScoped
class HmacSha256Signer : WebhookSigner {
    override fun sign(secret: String, timestamp: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8))
        return "sha256=" + raw.joinToString("") { "%02x".format(it) }
    }
}
