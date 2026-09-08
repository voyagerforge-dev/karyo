package com.karyo.layout.api.v1

import com.karyo.common.pagination.paginatedResponse
import com.karyo.layout.dto.*
import com.karyo.layout.service.LocationService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import com.karyo.layout.exception.LayoutException
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import java.math.BigDecimal

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T
private fun anyLong(): Long = ArgumentMatchers.anyLong()

@QuarkusTest
class LocationResourceTest {

    @InjectMock
    lateinit var locationService: LocationService

    private fun sampleLocation() = LocationResponse(
        id = 1L,
        name = "A-01-01",
        scanCode = "A-01-01",
        locationType = LocationTypeResponse(
            1L, "Pallet Rack", null, null, null, BigDecimal("1000"), null, null,
            "2026-03-03T00:00:00Z", "2026-03-03T00:00:00Z",
        ),
        area = AreaResponse(1L, "Storage", listOf("STORAGE"), "2026-03-03T00:00:00Z", "2026-03-03T00:00:00Z"),
        zone = null,
        locationCluster = null,
        allocation = BigDecimal.ZERO,
        lockType = 0,
        lockTypeName = "UNLOCKED",
        orderIndex = 0,
        xPos = 0, yPos = 0, zPos = 0,
        rack = null, field = null, section = null,
        created = "2026-03-03T00:00:00Z",
        modified = "2026-03-03T00:00:00Z",
        capacity = null,
        temperatureZone = null,
        handlingClass = null,
        kind = null,
        lastCountedAt = null,
        isClearing = false,
        plcCode = null,
        allocationState = 0,
    )

    // ── 1. Can create location ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create location - 201`() {
        doReturn(sampleLocation()).`when`(locationService).createLocation(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateLocationRequest(name = "A-01-01", locationTypeId = 1L, areaId = 1L))
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("name", `is`("A-01-01"))
    }

    // ── 2. Can get location by ID ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get location by ID - 200`() {
        doReturn(sampleLocation()).`when`(locationService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/locations/1")
            .then()
            .statusCode(200)
            .body("name", `is`("A-01-01"))
            // Always-run pin for the wire name (2026-07-31): Jackson getter-mangling once
            // serialized xPos/yPos/zPos as lowercase xpos/ypos/zpos. Fixture value is 0.
            .body("xPos", `is`(0))
    }

    // ── 3. Can lock location ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can lock location - 200`() {
        val locked = sampleLocation().copy(lockType = 7, lockTypeName = "STOCKTAKING")
        doReturn(locked).`when`(locationService).lockLocation(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(LockLocationRequest(lockType = 7))
            .`when`().post("/api/v1/locations/1/lock")
            .then()
            .statusCode(200)
            .body("lockType", `is`(7))
            .body("lockTypeName", `is`("STOCKTAKING"))
    }

    // ── 3b. Can update isClearing via PUT (mocked-service pass-through) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can set isClearing via update location - 200`() {
        val updated = sampleLocation().copy(isClearing = true)
        doReturn(updated).`when`(locationService).updateLocation(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(UpdateLocationRequest(isClearing = true))
            .`when`().put("/api/v1/locations/1")
            .then()
            .statusCode(200)
            .body("isClearing", `is`(true))
    }

    // ── 3c. plcCode + allocationState round-trip through create + get (L3, Task 6) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create carries plcCode and allocationState through to the response - 201`() {
        val withFields = sampleLocation().copy(plcCode = "PLC-42", allocationState = 1)
        doReturn(withFields).`when`(locationService).createLocation(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateLocationRequest(name = "A-01-01", locationTypeId = 1L, areaId = 1L, plcCode = "PLC-42", allocationState = 1))
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(201)
            .body("plcCode", `is`("PLC-42"))
            .body("allocationState", `is`(1))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `get location surfaces plcCode and allocationState, defaulting honestly when unset`() {
        doReturn(sampleLocation()).`when`(locationService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/locations/1")
            .then()
            .statusCode(200)
            .body("plcCode", nullValue())
            .body("allocationState", `is`(0))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can update plcCode and allocationState via update location - 200`() {
        val updated = sampleLocation().copy(plcCode = "PLC-99", allocationState = 1)
        doReturn(updated).`when`(locationService).updateLocation(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(UpdateLocationRequest(plcCode = "PLC-99", allocationState = 1))
            .`when`().put("/api/v1/locations/1")
            .then()
            .statusCode(200)
            .body("plcCode", `is`("PLC-99"))
            .body("allocationState", `is`(1))
    }

    // ── 3d. ?plcCode= list filter is passed through to the service (L3, Task 6) ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `list locations passes the plcCode query filter through to the service`() {
        val page = paginatedResponse(listOf(sampleLocation()), 0, 20, 1)
        doReturn(page).`when`(locationService)
            .listLocationsPaginated(anyLong(), anyObj(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/locations?plcCode=PLC-42")
            .then()
            .statusCode(200)

        verify(locationService).listLocationsPaginated(eq(1L), anyObj(), anyObj(), eq("PLC-42"), anyObj())
    }

    // ── 3e. Task 10: capacity/temperatureZone/handlingClass/kind/isClearing round-trip
    //       through create (previously orphaned -- DTO didn't carry them at all) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create carries V309 metadata and isClearing through to the response - 201`() {
        val withFields = sampleLocation().copy(
            capacity = 6,
            temperatureZone = "CHILLED",
            handlingClass = "HAZMAT",
            kind = "RESERVE",
            isClearing = true,
        )
        doReturn(withFields).`when`(locationService).createLocation(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(
                CreateLocationRequest(
                    name = "A-01-01",
                    locationTypeId = 1L,
                    areaId = 1L,
                    capacity = 6,
                    temperatureZone = "CHILLED",
                    handlingClass = "HAZMAT",
                    kind = "RESERVE",
                    isClearing = true,
                ),
            )
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(201)
            .body("capacity", `is`(6))
            .body("temperatureZone", `is`("CHILLED"))
            .body("handlingClass", `is`("HAZMAT"))
            .body("kind", `is`("RESERVE"))
            .body("isClearing", `is`(true))
    }

    // ── 3f. Task 10: capacity/temperatureZone/handlingClass/kind round-trip through update
    //       (previously response-only -- DTO didn't carry them, the form could never set them) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can update V309 metadata via update location - 200`() {
        val updated = sampleLocation().copy(
            capacity = 8,
            temperatureZone = "FROZEN",
            handlingClass = "HIGH_VALUE",
            kind = "PICK_FACE",
        )
        doReturn(updated).`when`(locationService).updateLocation(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(
                UpdateLocationRequest(
                    capacity = 8,
                    temperatureZone = "FROZEN",
                    handlingClass = "HIGH_VALUE",
                    kind = "PICK_FACE",
                ),
            )
            .`when`().put("/api/v1/locations/1")
            .then()
            .statusCode(200)
            .body("capacity", `is`(8))
            .body("temperatureZone", `is`("FROZEN"))
            .body("handlingClass", `is`("HIGH_VALUE"))
            .body("kind", `is`("PICK_FACE"))
    }

    // ── 3g. isClearing conflict surfaces as a clean 409 (A2-1's singleton, now reachable from
    //       both create and update) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `setting isClearing on update when another location already holds it - 409`() {
        doThrow(LayoutException.LocationInUse(1L, "another location is already configured as the clearing location"))
            .`when`(locationService).updateLocation(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(UpdateLocationRequest(isClearing = true))
            .`when`().put("/api/v1/locations/1")
            .then()
            .statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.endsWith("location-in-use"))
    }

    // ── 3h. Task 10 fix round 1: creating with isClearing=true when another location
    //       already holds it surfaces the same clean 409 (shallow REST-mapping check --
    //       see ClearingLocationTest for the real DB-backed create-vs-create conflict +
    //       no-row-persisted assertion, mirroring this file's own doc convention of
    //       "REST pass-through here, real persistence there") ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `creating a location with isClearing=true when another location already holds it - 409`() {
        doThrow(LayoutException.LocationInUse(0L, "another location is already configured as the clearing location"))
            .`when`(locationService).createLocation(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateLocationRequest(name = "A-01-02", locationTypeId = 1L, areaId = 1L, isClearing = true))
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.endsWith("location-in-use"))
    }

    // ── 4. Can lookup by scan code ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can lookup by scan code - 200`() {
        doReturn(sampleLocation()).`when`(locationService).findByScanCode("A-01-01", 1L)

        given()
            .`when`().get("/api/v1/locations/by-scan-code/A-01-01")
            .then()
            .statusCode(200)
            .body("scanCode", `is`("A-01-01"))
    }

    // ── 5. Read-only user cannot create location ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `read-only user cannot create location - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateLocationRequest(name = "A-01-01", locationTypeId = 1L, areaId = 1L))
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(403)
    }

    // ── 6. Unauthenticated request returns 401 ──

    @Test
    fun `unauthenticated request returns 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateLocationRequest(name = "A-01-01", locationTypeId = 1L, areaId = 1L))
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(401)
    }
}
