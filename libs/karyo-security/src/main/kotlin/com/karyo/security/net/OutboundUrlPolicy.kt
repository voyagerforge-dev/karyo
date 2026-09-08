package com.karyo.security.net

import jakarta.enterprise.context.ApplicationScoped
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * SSRF guard for any URL Karyo POSTs to server-side (webhook subscription targets, Slack alert
 * delivery webhooks, ...). Neutral home for the private-IP / reserved-hostname blocklist so
 * every outbound integration shares one policy instead of re-implementing it.
 *
 * Rejects:
 *  - non-http(s) schemes (file:, gopher:, etc.)
 *  - loopback addresses (127.x.x.x / ::1)
 *  - link-local addresses (169.254.x.x / fe80::/10), including the AWS EC2 metadata IP
 *  - private/site-local IPv4 ranges (10/8, 172.16/12, 192.168/16)
 *  - IPv6 unique-local (fc00::/7)
 *  - any-local / multicast
 *  - literal hostnames "localhost" and "metadata.google.internal"
 *
 * For non-IP hostnames a best-effort DNS resolve is attempted; if resolution fails (no DNS,
 * offline, test environment) the URL is ALLOWED.
 *
 * [check] returns a human-readable rejection reason, or null when the URL is acceptable.
 * Callers translate the reason into their own exception type (e.g. `BadRequestException` for
 * webhook subscriptions, `DeliveryRejected` for Slack alert delivery); this class stays
 * transport/framework-neutral (no `jakarta.ws.rs` dependency).
 *
 * ## SSRF threat model (v1 scope)
 *
 * The two active defences are:
 *   1. This create-time/delivery-time host validation (schema, IP-range, known-bad hostname
 *      checks above).
 *   2. Callers configure their outbound HTTP client with `followRedirects(NEVER)`; redirect
 *      chains cannot be used to bypass this check.
 *
 * **Accepted residual risks under the v1 threat model:**
 *  - DNS-rebinding (TOCTOU): the hostname passes validation but its DNS record is swapped to a
 *    private IP between validation and the actual outbound request.
 *  - Non-dotted-decimal IP encodings: integer form (e.g. 2130706433 for 127.0.0.1), hex-octet
 *    (0x7f.0.0.1), and octal-octet (0177.0.0.1) are NOT detected as IP literals by
 *    [looksLikeIpLiteral] and therefore bypass rejection.
 *
 * These risks are accepted because: (a) the URLs this guard covers are gated to trusted roles
 * (the `user-admin` platform role for webhook subscriptions; an operator-only, ownerWritable
 * false runtime property for the Slack webhook), and (b) Karyo uses silo deployment (one
 * instance per company), so the blast radius of a misconfigured target is limited to that
 * operator's own internal network.
 */
@ApplicationScoped
class OutboundUrlPolicy {

    fun check(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "url is not a valid URI: ${e.message}"
        }

        val scheme = uri.scheme?.lowercase() ?: return "url has no scheme"
        if (scheme != "http" && scheme != "https") {
            return "url scheme must be http(s), got: $scheme"
        }

        // Normalize: strip a trailing dot (e.g. "127.0.0.1." → "127.0.0.1") before any checks.
        // Without this, dotted-decimal regexes miss the trailing-dot form and it falls through to
        // the hostname path where a DNS failure would allow it.
        val host = (uri.host ?: return "url has no host").trimEnd('.')
        return checkHost(host)
    }

    /** Fast-reject known-bad literal hostnames (case-insensitive), then delegate by host shape. */
    private fun checkHost(host: String): String? {
        val hostLower = host.lowercase()
        if (hostLower == "localhost" || hostLower == "metadata.google.internal") {
            return "url host is not allowed: $host"
        }
        return if (looksLikeIpLiteral(host)) checkIpLiteral(host) else checkHostname(host)
    }

    /** Parses [host] without a DNS lookup; rejects immediately if it's a private/reserved IP. */
    private fun checkIpLiteral(host: String): String? {
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            return "url contains an invalid IP address: $host"
        }
        return if (isPrivate(addr)) {
            "url resolves to a private/reserved address and is not allowed: $host"
        } else {
            null
        }
    }

    /**
     * Best-effort DNS resolve for a non-IP-literal [host]; if resolution fails (no DNS, offline,
     * test environment) the URL is ALLOWED. Note: DNS-rebinding (TOCTOU) is an accepted residual
     * risk; see class KDoc.
     */
    private fun checkHostname(host: String): String? {
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (_: UnknownHostException) {
            // DNS unavailable or unknown host: allow.
            return null
        }
        return addrs.firstOrNull { isPrivate(it) }
            ?.let { "url host $host resolves to a private/reserved address" }
    }

    /**
     * Returns true if the host string is an IP literal rather than a DNS name.
     * IPv4: four dotted decimal octets.
     * IPv6: contains ':' (URI strips the surrounding brackets).
     *
     * LIMITATION: non-standard IP encodings, namely integer form (e.g. 2130706433 for
     * 127.0.0.1), hex-octet form (0x7f.0.0.1), and octal-octet form (0177.0.0.1), are NOT
     * detected as IP literals here and are NOT blocked. This is an accepted residual risk
     * under the v1 threat model; see class KDoc for rationale.
     */
    private fun looksLikeIpLiteral(host: String): Boolean =
        host.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")) || host.contains(':')

    /**
     * Returns true for any address that must not be an outbound target.
     *
     * Coverage:
     *  - isLoopbackAddress  : 127.0.0.0/8, ::1
     *  - isLinkLocalAddress : 169.254.0.0/16 (incl. EC2 metadata 169.254.169.254), fe80::/10
     *  - isSiteLocalAddress : 10/8, 172.16/12, 192.168/16 (IPv4); deprecated fec0::/10 (IPv6)
     *  - isAnyLocalAddress  : 0.0.0.0, ::
     *  - isMulticastAddress : 224.0.0.0/4, ff00::/8
     *  - IPv6 unique-local  : fc00::/7 (not covered by isSiteLocalAddress in Java)
     */
    private fun isPrivate(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.isMulticastAddress ||
            isIpv6UniqueLocal(addr)

    /** IPv6 unique-local (fc00::/7): high byte has top 7 bits = 1111110x. Not covered by [InetAddress.isSiteLocalAddress]. */
    private fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        if (addr !is Inet6Address) return false
        val highByte = addr.address[0].toInt() and 0xFF
        return highByte and 0xFE == 0xFC
    }
}
