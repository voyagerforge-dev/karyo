package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateStorageStrategyRequest
import com.karyo.layout.dto.StorageStrategyResponse
import com.karyo.layout.service.StorageStrategyService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T
private fun anyLong(): Long = ArgumentMatchers.anyLong()

@QuarkusTest
class StorageStrategyResourceTest {

    @InjectMock
    lateinit var strategyService: StorageStrategyService

    private fun sampleStrategy() = StorageStrategyResponse(
        id = 1L, name = "Default Strategy",
        zoneId = null, mixItem = true, mixClient = false,
        nearPickingLocation = false, sorts = null,
        onlyClientLocation = false, manualSearch = false,
        useAreaStrategyDate = false, useItemDataArea = false,
        areas = emptyList(),
        created = "2026-03-03T00:00:00Z", modified = "2026-03-03T00:00:00Z",
    )

    private fun flaggedStrategy() = StorageStrategyResponse(
        id = 2L, name = "Flagged Strategy",
        zoneId = null, mixItem = true, mixClient = false,
        nearPickingLocation = false, sorts = null,
        onlyClientLocation = true, manualSearch = true,
        useAreaStrategyDate = true, useItemDataArea = true,
        areas = emptyList(),
        created = "2026-03-03T00:00:00Z", modified = "2026-03-03T00:00:00Z",
    )

    // ── 1. Can list strategies (tenant-scoped) ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list strategies - 200`() {
        doReturn(listOf(sampleStrategy())).`when`(strategyService).listByClient(1L)

        given()
            .`when`().get("/api/v1/storage-strategies")
            .then()
            .statusCode(200)
            .body("$", hasSize<Any>(1))
            .body("[0].name", `is`("Default Strategy"))
    }

    // ── 2. Can create strategy ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create strategy - 201`() {
        doReturn(sampleStrategy()).`when`(strategyService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateStorageStrategyRequest(name = "Default Strategy"))
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(201)
            .body("name", `is`("Default Strategy"))
    }

    // ── 3. V312 flags round-trip (all four true) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create sends and returns all four V312 flags - 201`() {
        var sent: CreateStorageStrategyRequest? = null
        doAnswer { invocation ->
            sent = invocation.getArgument(0)
            flaggedStrategy()
        }.`when`(strategyService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(
                CreateStorageStrategyRequest(
                    name = "Flagged Strategy",
                    onlyClientLocation = true,
                    manualSearch = true,
                    useAreaStrategyDate = true,
                    useItemDataArea = true,
                )
            )
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(201)
            .body("onlyClientLocation", `is`(true))
            .body("manualSearch", `is`(true))
            .body("useAreaStrategyDate", `is`(true))
            .body("useItemDataArea", `is`(true))

        assertThat(sent).isNotNull
        assertThat(sent!!.onlyClientLocation).isTrue()
        assertThat(sent!!.manualSearch).isTrue()
        assertThat(sent!!.useAreaStrategyDate).isTrue()
        assertThat(sent!!.useItemDataArea).isTrue()
    }

    // ── 4. V312 flags defaults when omitted - 201 ──
    // Row 17 (defect-burndown-4, Task 11) flipped CreateStorageStrategyRequest.onlyClientLocation's
    // default to true; the other three V312 flags are unaffected and still default false.

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create defaults onlyClientLocation to true and the other three V312 flags to false when omitted - 201`() {
        var sent: CreateStorageStrategyRequest? = null
        doAnswer { invocation ->
            sent = invocation.getArgument(0)
            sampleStrategy()
        }.`when`(strategyService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Default Strategy"}""")
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(201)
            // The response body just echoes the mocked sampleStrategy() (onlyClientLocation =
            // false there), so this assertion is about the round-trip wiring, not the DTO
            // default -- the DTO default itself is asserted on `sent` below.
            .body("onlyClientLocation", `is`(false))
            .body("manualSearch", `is`(false))
            .body("useAreaStrategyDate", `is`(false))
            .body("useItemDataArea", `is`(false))

        assertThat(sent).isNotNull
        assertThat(sent!!.onlyClientLocation).isTrue()
        assertThat(sent!!.manualSearch).isFalse()
        assertThat(sent!!.useAreaStrategyDate).isFalse()
        assertThat(sent!!.useItemDataArea).isFalse()
    }

    // ── 5. L6 sorts — save-time validation (Task 5) ──
    //
    // The service is @InjectMock here (resource-layer test), so these two prove the REST
    // wiring/exception-mapping for `LayoutException.InvalidSortTokens` specifically (400,
    // detail names the bad token). The actual parser/validator logic (real service, real
    // rejection of an unknown token, real acceptance + round-trip of a valid list) is
    // covered end-to-end in [StorageStrategyResourceUpdateTest].

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create - service rejecting an unknown sorts token maps to 400 with the detail message`() {
        doAnswer {
            throw com.karyo.layout.exception.LayoutException.InvalidSortTokens(listOf("NOT_A_REAL_SORT"))
        }.`when`(strategyService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Bad Sorts","sorts":"CLIENT,NOT_A_REAL_SORT"}""")
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(400)
            .body("detail", org.hamcrest.Matchers.containsString("NOT_A_REAL_SORT"))
            .body("detail", org.hamcrest.Matchers.containsString("CAPACITY"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with a valid sorts list - round-trips through the response`() {
        val withSorts = sampleStrategy().copy(sorts = "CLIENT,ZONE,NAME")
        var sent: CreateStorageStrategyRequest? = null
        doAnswer { invocation ->
            sent = invocation.getArgument(0)
            withSorts
        }.`when`(strategyService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Good Sorts","sorts":"CLIENT,ZONE,NAME"}""")
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(201)
            .body("sorts", `is`("CLIENT,ZONE,NAME"))

        assertThat(sent).isNotNull
        assertThat(sent!!.sorts).isEqualTo("CLIENT,ZONE,NAME")
    }
}
