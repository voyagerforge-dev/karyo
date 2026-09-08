package com.karyo.webhooks.service

import com.karyo.security.net.OutboundUrlPolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException

/**
 * SSRF guard for webhook subscription targetUrl.
 *
 * Thin `BadRequestException` wrapper over the shared [OutboundUrlPolicy] (libs/karyo-security),
 * which carries the full blocklist (schemes, private/reserved IP ranges, known-bad hostnames)
 * and the documented residual-risk threat model; see its KDoc.
 */
@ApplicationScoped
class WebhookUrlValidator(
    private val policy: OutboundUrlPolicy = OutboundUrlPolicy(),
) {

    fun validate(url: String) {
        policy.check(url)?.let { throw BadRequestException(it) }
    }
}
