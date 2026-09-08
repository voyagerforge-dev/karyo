package com.karyo.auth.api.v1

import com.karyo.auth.config.SystemPropertyService
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

private const val BASE = "/api/v1/system-properties"

/** BOOLEAN catalog key, `ownerWritable=false` — the ops-controlled over-receipt hard stop. */
private const val BOOL_KEY = "karyo.receiving.allow-over-receipt"

/**
 * STRING catalog key marked `secret` + `ownerWritable=false` — stored values must never
 * surface in the effective view, and only ops may write it (SC20 SSRF posture: the alert
 * delivery scheduler POSTs server-side to this URL).
 */
private const val SLACK_KEY = "karyo.alerts.slack.webhook-url"

/** STRING catalog key, non-secret + ownerWritable, unconsumed as of SC16 — stored-row flows. */
private const val EMAIL_KEY = "karyo.alerts.email.recipients"

/** The literal the effective view substitutes for a secret key's value. */
private const val MASK = "••••••"

/**
 * SC16 REST surface. Same principal shapes as ClientResourceTest: both `client_id` and
 * `principal_kind` claims on every request (TenantFilter reads them separately), admin
 * principals carrying the ADMIN + user-admin composite pair a real token would.
 *
 * Shared-test-DB discipline: catalog stored-row flows use [SLACK_KEY] (consumed only by the
 * SC20 slack alert channel, which no resource test triggers) and clean up their rows in the
 * same test; non-catalog keys are nanoTime-unique.
 */
@QuarkusTest
class SystemPropertyResourceTest {

    @Inject
    lateinit var propertyService: SystemPropertyService

    private fun suffix() = System.nanoTime().toString().takeLast(8)

    // ── effective view ────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `effective view lists catalog keys with metadata and source labels`() {
        given().`when`().get(BASE)
            .then().statusCode(200)
            // BOOL_KEY is set in application.yaml, so its unstored source is CONFIG.
            .body("find { it.key == '$BOOL_KEY' }.type", equalTo("BOOLEAN"))
            .body("find { it.key == '$BOOL_KEY' }.group", equalTo("Receiving"))
            .body("find { it.key == '$BOOL_KEY' }.source", equalTo("CONFIG"))
            .body("find { it.key == '$BOOL_KEY' }.value", equalTo("true"))
            .body("find { it.key == '$BOOL_KEY' }.defaultValue", equalTo("true"))
            // Fix-round contract fields: the hard stop is ops-controlled, the webhook write-only.
            .body("find { it.key == '$BOOL_KEY' }.ownerWritable", equalTo(false))
            .body("find { it.key == '$SLACK_KEY' }.secret", equalTo(true))
            // The email recipients key exists nowhere → catalog DEFAULT (null value).
            .body("find { it.key == '$EMAIL_KEY' }.source", equalTo("DEFAULT"))
            .body("find { it.key == '$EMAIL_KEY' }.value", nullValue())
            .body("find { it.key == '$EMAIL_KEY' }.description", notNullValue())
    }

    // ── CRUD round-trip (non-catalog key) ─────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `sys admin round-trips a non-catalog property`() {
        val key = "karyo.test.rest.${suffix()}"

        given().contentType(ContentType.JSON)
            .body("""{"value":"v1","description":"a custom knob"}""")
            .`when`().put("$BASE/$key")
            .then().statusCode(200)
            .body("key", equalTo(key))
            .body("value", equalTo("v1"))
            .body("clientId", equalTo(0))
            .body("source", equalTo("SYSTEM"))
            .body("type", nullValue())

        // Upsert: same row, new value; shows up as a stored extra in the effective view.
        given().contentType(ContentType.JSON)
            .body("""{"value":"v2"}""")
            .`when`().put("$BASE/$key")
            .then().statusCode(200).body("value", equalTo("v2"))

        given().`when`().get(BASE)
            .then().statusCode(200)
            .body("findAll { it.key == '$key' }.size()", equalTo(1))
            .body("find { it.key == '$key' }.value", equalTo("v2"))
            .body("find { it.key == '$key' }.description", equalTo("a custom knob"))

        given().`when`().delete("$BASE/$key")
            .then().statusCode(204)

        // Second delete: nothing stored any more → 404 ProblemDetail.
        given().`when`().delete("$BASE/$key")
            .then().statusCode(404)
            .body("type", equalTo("https://karyo.com/errors/system-property-not-found"))
    }

    // ── catalog type validation ───────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `non-boolean value on a BOOLEAN catalog key is refused with 422`() {
        given().contentType(ContentType.JSON)
            .body("""{"value":"abc"}""")
            .`when`().put("$BASE/$BOOL_KEY")
            .then().statusCode(422)
            .body("type", equalTo("https://karyo.com/errors/system-property-invalid-value"))
    }

    // ── OWNER vs SYS scoping ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `client-0 row surfaces as SYSTEM fallback in another client's view until it stores its own`() {
        // SYS stores the instance-wide row…
        given().contentType(ContentType.JSON)
            .body("""{"value":"ops@example.com"}""")
            .`when`().put("$BASE/$EMAIL_KEY")
            .then().statusCode(200).body("source", equalTo("SYSTEM"))

        try {
            // …which client 1's effective view resolves as SYSTEM…
            given().`when`().get("$BASE?clientId=1")
                .then().statusCode(200)
                .body("find { it.key == '$EMAIL_KEY' }.source", equalTo("SYSTEM"))
                .body("find { it.key == '$EMAIL_KEY' }.value", equalTo("ops@example.com"))

            // …until SYS stores a client-1 row, which then wins as CLIENT.
            given().contentType(ContentType.JSON)
                .body("""{"value":"acme@example.com","clientId":1}""")
                .`when`().put("$BASE/$EMAIL_KEY")
                .then().statusCode(200).body("source", equalTo("CLIENT")).body("clientId", equalTo(1))

            given().`when`().get("$BASE?clientId=1")
                .then().statusCode(200)
                .body("find { it.key == '$EMAIL_KEY' }.source", equalTo("CLIENT"))
                .body("find { it.key == '$EMAIL_KEY' }.value", equalTo("acme@example.com"))
        } finally {
            given().`when`().delete("$BASE/$EMAIL_KEY?clientId=1").then().statusCode(204)
            given().`when`().delete("$BASE/$EMAIL_KEY").then().statusCode(204)
        }
    }

    // ── fix round: secret masking (write-only keys) ───────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `a secret key's stored value is masked in the effective view - SYS included`() {
        val rawValue = "https://hooks.example/T000/B000/deadbeef"
        given().contentType(ContentType.JSON)
            .body("""{"value":"$rawValue"}""")
            .`when`().put("$BASE/$SLACK_KEY")
            .then().statusCode(200)

        try {
            given().`when`().get(BASE)
                .then().statusCode(200)
                .body("find { it.key == '$SLACK_KEY' }.value", equalTo(MASK))
                .body("find { it.key == '$SLACK_KEY' }.source", equalTo("SYSTEM"))
                .body("findAll { it.value == '$rawValue' }.size()", equalTo(0))
        } finally {
            given().`when`().delete("$BASE/$SLACK_KEY").then().statusCode(204)
        }
    }

    /**
     * The exact leak path of the finding: an OWNER user-admin's GET resolving the operator's
     * instance-wide (client-0) webhook. The row is seeded via direct CDI because the REST
     * identity of this test IS the owner principal under attack.
     */
    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin never sees the operator's instance-wide secret unmasked`() {
        val rawValue = "https://hooks.example/T000/B000/cafebabe"
        propertyService.set(0L, SLACK_KEY, null, rawValue)

        try {
            given().`when`().get(BASE)
                .then().statusCode(200)
                .body("find { it.key == '$SLACK_KEY' }.value", equalTo(MASK))
                .body("find { it.key == '$SLACK_KEY' }.source", equalTo("SYSTEM"))
                .body("findAll { it.value == '$rawValue' }.size()", equalTo(0))
        } finally {
            propertyService.delete(0L, SLACK_KEY, null)
        }
    }

    /**
     * The client-1 row is seeded via direct CDI: since the SC20 SSRF fix the slack key is
     * `ownerWritable=false`, so an OWNER PUT can no longer create it — only SYS (who can
     * target any client) or the service layer can. The masking contract is unchanged.
     */
    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin sees its own client's stored secret masked too`() {
        val rawValue = "https://hooks.example/T111/B111/0wnersecret"
        propertyService.set(1L, SLACK_KEY, null, rawValue)

        try {
            given().`when`().get(BASE)
                .then().statusCode(200)
                .body("find { it.key == '$SLACK_KEY' }.value", equalTo(MASK))
                .body("find { it.key == '$SLACK_KEY' }.source", equalTo("CLIENT"))
                .body("findAll { it.value == '$rawValue' }.size()", equalTo(0))
        } finally {
            propertyService.delete(1L, SLACK_KEY, null)
        }
    }

    /**
     * SC20 SSRF posture pin: the alert-delivery scheduler POSTs server-side to whatever this
     * key stores, so URL authority must stay with ops — an OWNER admin writing it would be
     * blind SSRF (internal endpoints, metadata IPs). Must stay `ownerWritable=false`.
     */
    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin cannot write or delete the slack webhook URL - 403`() {
        given().contentType(ContentType.JSON)
            .body("""{"value":"http://keycloak:8080/internal"}""")
            .`when`().put("$BASE/$SLACK_KEY")
            .then().statusCode(403)
            .body("type", equalTo("https://karyo.com/errors/system-property-owner-forbidden"))

        given().`when`().delete("$BASE/$SLACK_KEY")
            .then().statusCode(403)
            .body("type", equalTo("https://karyo.com/errors/system-property-owner-forbidden"))
    }

    // ── fix round: operator-controlled keys (ownerWritable=false) ─────────

    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin cannot write or delete the over-receipt hard stop - 403`() {
        given().contentType(ContentType.JSON)
            .body("""{"value":"true"}""")
            .`when`().put("$BASE/$BOOL_KEY")
            .then().statusCode(403)
            .body("type", equalTo("https://karyo.com/errors/system-property-owner-forbidden"))

        given().`when`().delete("$BASE/$BOOL_KEY")
            .then().statusCode(403)
            .body("type", equalTo("https://karyo.com/errors/system-property-owner-forbidden"))
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `sys retains per-client control of an operator-controlled key`() {
        try {
            given().contentType(ContentType.JSON)
                .body("""{"value":"false","clientId":1}""")
                .`when`().put("$BASE/$BOOL_KEY")
                .then().statusCode(200)
                .body("clientId", equalTo(1))
                .body("value", equalTo("false"))
        } finally {
            // Must not linger: a stored false row for client 1 would flip the real
            // receiving flow for every other test touching client 1.
            given().`when`().delete("$BASE/$BOOL_KEY?clientId=1").then().statusCode(204)
        }
    }

    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin can still write an ownerWritable catalog key`() {
        try {
            given().contentType(ContentType.JSON)
                .body("""{"value":"owner-alerts@acme.example"}""")
                .`when`().put("$BASE/$EMAIL_KEY")
                .then().statusCode(200)
                .body("source", equalTo("CLIENT"))
                .body("clientId", equalTo(1))
        } finally {
            given().`when`().delete("$BASE/$EMAIL_KEY").then().statusCode(204)
        }
    }

    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin reads and writes its own scope`() {
        val key = "karyo.test.owner.${suffix()}"

        // clientId 1 in the body is its own client — allowed (and equivalent to omitting it).
        given().contentType(ContentType.JSON)
            .body("""{"value":"mine","clientId":1}""")
            .`when`().put("$BASE/$key")
            .then().statusCode(200).body("clientId", equalTo(1)).body("source", equalTo("CLIENT"))

        given().`when`().get(BASE)
            .then().statusCode(200)
            .body("find { it.key == '$key' }.value", equalTo("mine"))

        given().`when`().delete("$BASE/$key").then().statusCode(204)
    }

    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner admin cannot target another client - 422 on write, read and delete`() {
        given().contentType(ContentType.JSON)
            .body("""{"value":"hijack","clientId":2}""")
            .`when`().put("$BASE/karyo.test.owner-hijack")
            .then().statusCode(422)
            .body("type", equalTo("https://karyo.com/errors/system-property-target-forbidden"))

        given().`when`().get("$BASE?clientId=2")
            .then().statusCode(422)
            .body("type", equalTo("https://karyo.com/errors/system-property-target-forbidden"))

        given().`when`().delete("$BASE/karyo.test.owner-hijack?clientId=2")
            .then().statusCode(422)
    }

    // ── RBAC ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `a viewer gets 403 on every verb`() {
        given().`when`().get(BASE).then().statusCode(403)
        given().contentType(ContentType.JSON).body("""{"value":"x"}""")
            .`when`().put("$BASE/karyo.test.viewer").then().statusCode(403)
        given().`when`().delete("$BASE/karyo.test.viewer").then().statusCode(403)
    }
}
