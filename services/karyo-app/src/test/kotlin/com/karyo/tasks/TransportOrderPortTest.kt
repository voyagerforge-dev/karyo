package com.karyo.tasks

import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import com.karyo.tasks.spi.ReplenishmentTaskCommand
import com.karyo.tasks.spi.TransportOrderPort
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration test for [TransportOrderPort] — verifies that [com.karyo.tasks.service.DefaultTransportOrderPort]
 * wires correctly to [com.karyo.tasks.service.TaskService] and mints a REPLENISH
 * transport order with the expected order-number prefix, state, and fixAssignmentId linkage.
 *
 * UL seeding: POST /api/v1/unit-loads (same pattern as [com.karyo.inventory.api.v1.UnitLoadResourceTest]).
 * unitLoadTypeId=1 is seeded by V105 migration (always present in test DB).
 *
 * TenantContext note: [com.karyo.inventory.service.DefaultUnitLoadLookup] checks
 * `ul.clientId == tenantContext.clientId`. TenantContext is @RequestScoped and is only
 * populated by TenantFilter during REST calls — direct CDI calls bypass it. Following the
 * established test pattern (DefaultStockPickerTest, PickConfirmServiceTest, etc.) we inject
 * TenantContext and set clientId=1 before the port call to match the seeded UL's client.
 */
@QuarkusTest
class TransportOrderPortTest {

    @Inject
    lateinit var port: TransportOrderPort

    @Inject
    lateinit var tenantContext: TenantContext

    /**
     * Seeds a UnitLoad via the REST API for clientId=1 (from the JWT) and returns its id.
     * The UL is placed at a dummy location (id=100, name="RESERVE-A"); the tasks module
     * only needs label + locationId + locationName from UnitLoadInfo, so no real layout seed required.
     * unitLoadTypeId=1 is the default type from the V105 seed migration.
     */
    private fun seedUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":100,"storageLocationName":"RESERVE-A"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `createReplenishment mints a RELEASED REPLENISH task carrying the fixAssignmentId`() {
        val s = System.nanoTime()
        val ulId = seedUnitLoad("UL-REPL-PORT-$s")

        // TenantContext is @RequestScoped and only populated by TenantFilter during HTTP requests.
        // Set clientId=1 so DefaultUnitLoadLookup can resolve the seeded UL for the same tenant.
        tenantContext.clientId = 1L

        val ref = port.createReplenishment(
            ReplenishmentTaskCommand(
                clientId = 1,
                unitLoadId = ulId,
                destinationLocationId = 42,
                destinationLocationName = "PICK-A",
                fixAssignmentId = 1234,
            ),
        )

        assertThat(ref.orderNumber).startsWith("RP-")
        assertThat(ref.state).isEqualTo(OrderState.RELEASED.code)
        assertThat(port.hasOpenReplenishment(1234, 1)).isTrue()
        assertThat(port.hasOpenReplenishment(1234, 9999)).isFalse() // tenant-scoped
    }
}
