package com.karyo.demo

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.greaterThan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Task 7 capstone: proves one `POST /api/v1/demo/seed` lights up every screen the demo needs to
 * showcase in the free tier: outbound history, operational KPIs, and all 6 monitor input
 * conditions. It then proves `POST /api/v1/demo/reset` empties the operational tables again.
 * Paid-engine assertions live in `DemoPaidEnginesIT`, which the public snapshot excludes.
 */
@QuarkusTest
@TestProfile(DemoEverythingAliveIT.DemoOn::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DemoEverythingAliveIT {
    class DemoOn : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "karyo.demo.enabled" to "on",
            "karyo.demo.history-days" to "40",
        )
    }

    @Inject lateinit var em: EntityManager

    @Test @Order(1) @TestSecurity(user = "a", roles = ["ADMIN"])
    fun `seed populates and returns a non-trivial summary`() {
        given().`when`().post("/api/v1/demo/seed")
            .then().statusCode(200)
            .body("orders", greaterThan(0))
            .body("picks", greaterThan(0))
            .body("shipments", greaterThan(0))
    }

    // insights/kpis (a v1.5 holdover, predating the VIEWER/OPERATOR/... role scheme) is
    // literally @RolesAllowed("inventory-read"), not "VIEWER" — added alongside VIEWER so this
    // one actor can read every screen the seed lights up.
    @Test @Order(2) @TestSecurity(user = "a", roles = ["VIEWER", "inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `free analytics return after seed`() {
        given().`when`().get("/api/v1/insights/kpis?range=30D").then().statusCode(200)
    }

    @Test @Order(3)
    fun `all six monitor conditions have data`() {
        // expiry, bin-below-reorder, putaway-backlog, count-variance, stuck-order, shrinkage
        fun q(sql: String) = (em.createNativeQuery(sql).singleResult as Number).toInt()
        assertTrue(q("SELECT count(*) FROM karyo.stock_units WHERE client_id=1 AND best_before <= CURRENT_DATE + 7") >= 1)
        assertTrue(q("SELECT count(*) FROM karyo.fix_assignments WHERE client_id=1 AND min_amount IS NOT NULL") >= 1)
        assertTrue(q("SELECT count(*) FROM karyo.transport_orders WHERE client_id=1 AND transport_type='PUTAWAY' AND finished IS NULL") >= 15)
        assertTrue(q("SELECT count(*) FROM karyo.count_lines WHERE client_id=1 AND counted_amount <> planned_amount") >= 1)
        assertTrue(q("SELECT count(*) FROM karyo.delivery_orders WHERE client_id=1 AND state>0 AND state<700 AND modified < NOW() - INTERVAL '24 hours'") >= 1)
        assertTrue(q("SELECT count(*) FROM karyo.inventory_journals WHERE client_id=1 AND amount < 0") >= 1)
    }

    @Test @Order(4) @TestSecurity(user = "a", roles = ["ADMIN"])
    fun `reset empties the operational tables`() {
        given().`when`().post("/api/v1/demo/reset").then().statusCode(204)
        val picks = (em.createNativeQuery("SELECT count(*) FROM karyo.picks").singleResult as Number).toInt()
        val stock = (em.createNativeQuery("SELECT count(*) FROM karyo.stock_units").singleResult as Number).toInt()
        assertEquals(0, picks)
        assertEquals(0, stock)
    }
}
