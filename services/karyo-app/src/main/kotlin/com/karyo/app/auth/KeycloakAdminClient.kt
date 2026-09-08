package com.karyo.app.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** One Keycloak admin event, reduced to the fields the auth-audit poller consumes. */
data class KcEvent(
    /** Event timestamp, epoch millis (Keycloak server clock). */
    val time: Long,
    /** Raw Keycloak event type: LOGIN, LOGOUT or LOGIN_ERROR. */
    val type: String,
    /** Keycloak user id; null on failed logins for unknown users. */
    val userId: String?,
    /** Keycloak session id; null on some LOGIN_ERROR events. */
    val sessionId: String?,
    val ipAddress: String?,
    /** `details.username` when present (set on direct-grant logins and most errors). */
    val username: String?,
)

/** Keycloak user fields the poller needs: username + the `client_id` tenant attribute. */
data class KcUser(val username: String?, val clientId: Long?)

/**
 * A page-cap-aware batch from [KeycloakAdminClient.fetchEvents]: [events] is oldest-first,
 * filtered to `time >= afterMs`. [truncated] is true when the [KeycloakAdminClient.MAX_PAGES]
 * hard cap was hit before a short page or a page older than the cursor was seen: there may be
 * MORE matching events older than the oldest entry in [events] that this batch never fetched.
 * The caller must not advance its cursor past that oldest entry when [truncated].
 */
data class KcEventBatch(val events: List<KcEvent>, val truncated: Boolean)

/**
 * Minimal Keycloak admin Events API client over the JDK [HttpClient] (the
 * `WebhookHttpClient` idiom — no new Quarkus extensions).
 *
 * Reuses the app's own OIDC configuration verbatim: the `karyo-backend` confidential
 * client (`quarkus.oidc.client-id` + `quarkus.oidc.credentials.secret`) authenticates via
 * a client-credentials grant against the configured `quarkus.oidc.auth-server-url`, and
 * the realm admin base is that same URL with `/realms/` replaced by `/admin/realms/`
 * (which preserves any HTTP relative-path prefix, e.g. prod's `/auth`). The service
 * account needs two `realm-management` client roles (granted in both realm JSON files):
 * `view-events` for the events feed and `view-users` for resolving a user's username and
 * `client_id` attribute — Keycloak 403s `/admin/realms/karyo/users/{id}` on events-only.
 *
 * Access tokens are cached until 30s before their `expires_in` deadline. All failures
 * surface as exceptions; the caller ([KeycloakEventPoller]) logs and swallows them.
 */
@ApplicationScoped
class KeycloakAdminClient(
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    authServerUrl: String,
    @ConfigProperty(name = "quarkus.oidc.client-id")
    private val clientId: String,
    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    private val clientSecret: String,
    private val objectMapper: ObjectMapper,
) {
    private val baseUrl = authServerUrl.trimEnd('/')
    private val tokenEndpoint = "$baseUrl/protocol/openid-connect/token"
    private val adminBase = baseUrl.replaceFirst("/realms/", "/admin/realms/")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private data class CachedToken(val accessToken: String, val refreshAfter: Instant)

    @Volatile
    private var cachedToken: CachedToken? = null

    /**
     * Fetches LOGIN/LOGOUT/LOGIN_ERROR admin events with `time >= afterMs`, paging with
     * `first`/`max` (Keycloak returns events newest-first) until a short page is seen (no
     * more results) or a page's oldest event is already older than [afterMs] (the remaining
     * pages, if any, are entirely before the cursor). The API's `dateFrom`/`dateTo` params
     * are day-granular, so the cursor filter is deliberately done client-side, not server-side.
     *
     * Hard-capped at [MAX_PAGES] pages ([MAX_EVENTS] × [MAX_PAGES] events): on cap-hit,
     * [KcEventBatch.truncated] is true, and there may be older matching events this call
     * never saw. The caller must advance its cursor conservatively in that case (see
     * [KcEventBatch]), not to the max time of what happened to come back.
     */
    fun fetchEvents(afterMs: Long): KcEventBatch {
        val all = mutableListOf<KcEvent>()
        var truncated = false
        for (page in 0 until MAX_PAGES) {
            val first = page * MAX_EVENTS
            val url = "$adminBase/events?type=LOGIN&type=LOGOUT&type=LOGIN_ERROR&max=$MAX_EVENTS&first=$first"
            val body = get(url) ?: error("Keycloak events endpoint returned 404: $url")
            val pageEvents = objectMapper.readTree(body).map(::toKcEvent)
            all += pageEvents
            val oldestInPage = pageEvents.lastOrNull()
            val shortPage = pageEvents.size < MAX_EVENTS
            val pastCursor = oldestInPage != null && oldestInPage.time < afterMs
            if (shortPage || pastCursor) break
            if (page == MAX_PAGES - 1) truncated = true
        }
        val filtered = all.filter { it.time >= afterMs }.sortedBy { it.time }
        return KcEventBatch(filtered, truncated)
    }

    private fun toKcEvent(node: JsonNode): KcEvent = KcEvent(
        time = node.path("time").asLong(),
        type = node.path("type").asText(""),
        userId = node.path("userId").textValue(),
        sessionId = node.path("sessionId").textValue(),
        ipAddress = node.path("ipAddress").textValue(),
        username = node.path("details").path("username").textValue(),
    )

    /**
     * Resolves a Keycloak user's username and `client_id` attribute; null when the user no
     * longer exists. Callers cache per poll run ([KeycloakEventPoller] keeps a per-run map).
     */
    fun fetchUser(userId: String): KcUser? {
        val encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8)
        val body = get("$adminBase/users/$encoded") ?: return null
        val node = objectMapper.readTree(body)
        return KcUser(
            username = node.path("username").textValue(),
            clientId = firstAttribute(node, "client_id")?.toLongOrNull(),
        )
    }

    private fun firstAttribute(user: JsonNode, name: String): String? =
        user.path("attributes").path(name).firstOrNull()?.textValue()

    /** GET with a bearer admin token; returns the body, null on 404, throws otherwise. */
    private fun get(url: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer ${token()}")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            // 401 = token problem, 403 = missing view-events role — keep them tellable apart.
            else -> error("Keycloak admin API HTTP ${response.statusCode()} for GET $url")
        }
    }

    private fun token(): String {
        cachedToken?.let { if (Instant.now().isBefore(it.refreshAfter)) return it.accessToken }
        val form = "grant_type=client_credentials" +
            "&client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}" +
            "&client_secret=${URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(tokenEndpoint))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Keycloak token endpoint HTTP ${response.statusCode()} for client '$clientId'"
        }
        val node = objectMapper.readTree(response.body())
        val accessToken = node.path("access_token").textValue()
            ?: error("Keycloak token response carried no access_token")
        val expiresIn = node.path("expires_in").asLong(DEFAULT_EXPIRES_IN_S)
        val refreshAfter = Instant.now().plusSeconds(maxOf(expiresIn - REFRESH_EARLY_S, MIN_TOKEN_TTL_S))
        cachedToken = CachedToken(accessToken, refreshAfter)
        return accessToken
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val MAX_EVENTS = 200
        /** Hard cap on pages per [fetchEvents] call: 25 × [MAX_EVENTS] = 5000 events per poll. */
        private const val MAX_PAGES = 25
        private const val DEFAULT_EXPIRES_IN_S = 60L
        private const val REFRESH_EARLY_S = 30L
        private const val MIN_TOKEN_TTL_S = 5L
    }
}
