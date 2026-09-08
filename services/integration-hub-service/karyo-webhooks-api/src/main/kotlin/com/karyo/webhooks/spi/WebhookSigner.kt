package com.karyo.webhooks.spi

/** Signs the outbound request. Returns the value for the X-Karyo-Signature header. */
interface WebhookSigner {
    fun sign(secret: String, timestamp: Long, body: String): String
}
