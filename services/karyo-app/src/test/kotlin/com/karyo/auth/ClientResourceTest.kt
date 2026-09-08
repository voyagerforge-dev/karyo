package com.karyo.auth

import com.karyo.events.outbox.OutboxEventRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * REST surface for goods-owner administration.
 *
 * Both `client_id` and `principal_kind` are supplied on every request: `TenantFilter` reads
 * them separately, and an owner-scoping test that omits `principal_kind` would not actually
 * exercise owner scoping.
 *
 * Admin principals carry both ADMIN and user-admin because that is what a real token looks like:
 * ADMIN is a Keycloak composite that expands to include user-admin (and VIEWER). Writes require
 * user-admin; reads require one of the broad roles.
 */
@QuarkusTest
class ClientResourceTest {

    @Inject
    lateinit var outboxEventRepository: OutboxEventRepository

    private fun suffix() = System.nanoTime().toString().takeLast(6)

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `admin creates and reads back a client`() {
        val s = suffix()
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Rest Co $s","number":"RC$s","email":"ops@rest.co"}""")
            .`when`().post("/api/v1/clients")
            .then().statusCode(201)
            .body("number", equalTo("RC$s"))
            .body("state", equalTo("ACTIVE"))
            .body("id", notNullValue())
            .extract().path<Int>("id")

        given().`when`().get("/api/v1/clients/$id")
            .then().statusCode(200).body("name", equalTo("Rest Co $s"))
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `duplicate number is rejected with 409`() {
        val s = suffix()
        val body = """{"name":"Dup Co $s","number":"DC$s"}"""
        given().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/v1/clients").then().statusCode(201)

        given().contentType(ContentType.JSON).body("""{"name":"Other $s","number":"DC$s"}""")
            .`when`().post("/api/v1/clients").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `the system client cannot be modified`() {
        given().contentType(ContentType.JSON).body("""{"name":"Hijacked"}""")
            .`when`().put("/api/v1/clients/0").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal sees only its own client`() {
        given().`when`().get("/api/v1/clients")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].id", equalTo(1))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal gets 404 for another client`() {
        given().`when`().get("/api/v1/clients/2").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `a non-admin cannot create a client`() {
        given().contentType(ContentType.JSON).body("""{"name":"Nope","number":"NOPE1"}""")
            .`when`().post("/api/v1/clients").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `consistency report is reachable and well formed`() {
        given().`when`().get("/api/v1/clients/consistency")
            .then().statusCode(200).body("danglingClientIds", notNullValue())
    }

    /**
     * A dangling-id scan is a full-schema `information_schema` sweep, same class of platform
     * administration as create(). An owner principal must not run it even while holding
     * user-admin, matching the create() guard.
     */
    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal cannot run the consistency report`() {
        given().`when`().get("/api/v1/clients/consistency")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `admin deactivates and reactivates a client`() {
        val s = suffix()
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Toggle Co $s","number":"TC$s","email":"ops@toggle.co"}""")
            .`when`().post("/api/v1/clients")
            .then().statusCode(201)
            .extract().path<Int>("id")

        given().contentType(ContentType.JSON).`when`().post("/api/v1/clients/$id/deactivate")
            .then().statusCode(200).body("state", equalTo("INACTIVE"))

        given().contentType(ContentType.JSON).`when`().post("/api/v1/clients/$id/reactivate")
            .then().statusCode(200).body("state", equalTo("ACTIVE"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `a non-admin cannot deactivate a client`() {
        given().contentType(ContentType.JSON).`when`().post("/api/v1/clients/1/deactivate")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `the system client cannot be deactivated`() {
        given().contentType(ContentType.JSON).`when`().post("/api/v1/clients/0/deactivate")
            .then().statusCode(403)
    }

    // ---- write-path tenant scoping -------------------------------------------------------
    //
    // Reads were scoped from the start; writes were not. Because every mutation endpoint echoes
    // the full ClientResponse back, an unscoped write was also an unscoped read: an owner
    // principal 404'd on GET /clients/2 but got 200 with Globex's name/code/email/phone/fax from
    // PUT /clients/2. Out-of-scope writes answer 404, matching the read path — a 403 would
    // confirm the row exists to a principal not entitled to know that.

    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal gets 404 updating another client`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"Hijacked by ACME"}""")
            .`when`().put("/api/v1/clients/2")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal gets 404 deactivating another client`() {
        given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/clients/2/deactivate")
            .then().statusCode(404)
    }

    /** The scope restricts rather than blocks: an owner may still administer its own row. */
    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal can still update its own client`() {
        // Writes back ACME's seeded values so the shared test DB is left untouched.
        given().contentType(ContentType.JSON)
            .body("""{"name":"ACME Corporation","code":"ACME"}""")
            .`when`().put("/api/v1/clients/1")
            .then().statusCode(200)
            .body("id", equalTo(1))
            .body("name", equalTo("ACME Corporation"))
    }

    /** Creating a goods owner is platform administration — not something a goods owner does. */
    @Test
    @TestSecurity(user = "owner-admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "principal_kind", value = "owner")])
    fun `an owner principal cannot create a client`() {
        val s = suffix()
        given().contentType(ContentType.JSON)
            .body("""{"name":"Owner Made $s","number":"OM$s"}""")
            .`when`().post("/api/v1/clients")
            .then().statusCode(403)
    }

    /** An ops principal is owner-blind on writes and must stay that way. */
    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `an ops principal can still update another client`() {
        val s = suffix()
        val id = given().contentType(ContentType.JSON)
            .body("""{"name":"Ops Target $s","number":"OT$s"}""")
            .`when`().post("/api/v1/clients")
            .then().statusCode(201)
            .extract().path<Int>("id")

        given().contentType(ContentType.JSON)
            .body("""{"name":"Ops Renamed $s","code":"OT$s"}""")
            .`when`().put("/api/v1/clients/$id")
            .then().statusCode(200)
            .body("name", equalTo("Ops Renamed $s"))
    }

    /**
     * A `user-admin`-only principal must be able to write. The frontend AdminGuard gates
     * the admin routes on that composite role, so requiring ADMIN here would let a user through the
     * guard only to have every write 403.
     */
    @Test
    @TestSecurity(user = "useradmin", roles = ["user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `a user-admin principal can create a client`() {
        val s = suffix()
        given().contentType(ContentType.JSON)
            .body("""{"name":"UserAdmin Co $s","number":"UA$s"}""")
            .`when`().post("/api/v1/clients")
            .then().statusCode(201)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ADMIN", "user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `creating a client writes an outbox event`() {
        val s = suffix()

        val id = given().contentType(ContentType.JSON)
            .body("""{"name":"Outbox Co $s","number":"OB$s"}""")
            .`when`().post("/api/v1/clients")
            .then().statusCode(201)
            .extract().path<Int>("id")

        // Pinned to this client's own aggregateId, not a bare eventType count — a count-only
        // assertion can't tell "our create published" from "some other test's create published"
        // if the suite ever runs in parallel.
        assertThat(
            outboxEventRepository.count("eventType = ?1 and aggregateId = ?2", "ClientCreated", id.toLong()),
        ).isEqualTo(1L)
    }
}
