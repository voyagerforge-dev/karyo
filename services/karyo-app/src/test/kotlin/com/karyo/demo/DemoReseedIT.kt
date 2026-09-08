package com.karyo.demo

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Task 9 (defect-burndown): `POST /api/v1/demo/seed` must be safely re-runnable with no reset
 * in between -- before this task it 500'd on a `unit_loads` duplicate-key violation because
 * [com.karyo.demo.gen.InventoryGenerator] is deterministic and non-idempotent by design (see its
 * KDoc). The fix makes `DemoResource.seed` reset first, as two separate bean calls, so a second
 * `seed()` call converges on the same catalog shape instead of colliding with the first one's
 * rows. Short (40-day) history window for speed, same idiom as [DemoEverythingAliveIT].
 */
@QuarkusTest
@TestProfile(DemoReseedIT.DemoOn::class)
class DemoReseedIT {
    class DemoOn : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "karyo.demo.enabled" to "on",
            "karyo.demo.history-days" to "40",
        )
    }

    private fun seedViaRest(): Response =
        given().`when`().post("/api/v1/demo/seed")

    @Test
    @TestSecurity(user = "a", roles = ["ADMIN"])
    fun `seeding twice without a reset succeeds and converges`() {
        val first = seedViaRest()
        first.then().statusCode(200)

        val second = seedViaRest() // FAILS today: 500 unit_loads duplicate key
        second.then().statusCode(200)

        assertThat(second.jsonPath().getInt("locations"))
            .isEqualTo(first.jsonPath().getInt("locations"))
        assertThat(second.jsonPath().getInt("skus"))
            .isEqualTo(first.jsonPath().getInt("skus"))
    }
}
