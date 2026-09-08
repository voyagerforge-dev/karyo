package com.karyo.reporting

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

@QuarkusTest
class KpiResourceTest {

    @Test @TestSecurity(user = "viewer", roles = ["inventory-read"])
    fun `returns the four tiles in fixed key order with a chart`() {
        given().get("/api/v1/insights/kpis?range=30D")
            .then().statusCode(200)
            .body("range", equalTo("30D"))
            .body("tiles.key", contains("accuracy", "throughput", "cycleTime", "utilization"))
            .body("chart.outbound", notNullValue())
            .body("tiles.find { it.key == 'utilization' }.delta", nullValue())
    }

    @Test @TestSecurity(user = "viewer", roles = ["inventory-read"])
    fun `defaults range when omitted and rejects a bad range`() {
        given().get("/api/v1/insights/kpis").then().statusCode(200)
        given().get("/api/v1/insights/kpis?range=BOGUS").then().statusCode(400)
    }

    @Test @TestSecurity(user = "nobody", roles = ["product-write"])
    fun `requires inventory-read`() {
        given().get("/api/v1/insights/kpis?range=7D").then().statusCode(403)
    }
}
