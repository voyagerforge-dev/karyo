package com.karyo.webhooks.spi.impl

import com.karyo.webhooks.spi.DeliveryRetryPolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class ExponentialBackoffRetryPolicy @Inject constructor(
    @ConfigProperty(name = "karyo.webhooks.backoff-base", defaultValue = "10s")
    private val baseDuration: java.time.Duration,
    @ConfigProperty(name = "karyo.webhooks.backoff-cap", defaultValue = "1h")
    private val capDuration: java.time.Duration,
) : DeliveryRetryPolicy {
    // Test-friendly secondary ctor.
    constructor(baseSeconds: Long, capSeconds: Long) :
        this(java.time.Duration.ofSeconds(baseSeconds), java.time.Duration.ofSeconds(capSeconds))

    private val baseSeconds = baseDuration.seconds
    private val capSeconds = capDuration.seconds

    override fun backoffSeconds(attempts: Int): Long {
        val exp = baseSeconds * (1L shl (attempts - 1).coerceIn(0, 30))
        return exp.coerceAtMost(capSeconds)
    }
}
