package com.karyo.inventory.service

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class DefaultStockPickerShipTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates a stock unit in ON_STOCK(300) on [ulId]. */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"SHIP","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Forward-only state change via the REST route. */
    private fun moveToState(id: Long, state: Int) {
        given().contentType(ContentType.JSON)
            .body("""{"state":$state}""")
            .`when`().post("/api/v1/stock-units/$id/change-state").then().statusCode(200)
    }

    private fun stockState(id: Long): Int =
        given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200).extract().jsonPath().getInt("state")

    private fun unitLoadState(id: Long): Int =
        given().`when`().get("/api/v1/unit-loads/$id").then().statusCode(200).extract().jsonPath().getInt("state")

    private fun stockModified(id: Long): Instant =
        Instant.parse(
            given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200)
                .extract().jsonPath().getString("modified"),
        )

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `shipContainer promotes PACKED stock on the UL past SHIPPED to DELETABLE`() {
        val itemDataId = 3300L
        val ul = createUnitLoad("UL-SHIP-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 50.0)
        // Reach PACKED(650): ON_STOCK(300) -> PICKED(600) -> PACKED(650) are both forward-only legal.
        moveToState(stockId, 600)
        moveToState(stockId, 650)

        // TenantContext is @RequestScoped (populated by TenantFilter only during REST requests);
        // for the direct SPI call set clientId to match the seeding REST calls (clientId=1).
        tenantContext.clientId = 1L

        // Review fix (defect-burndown-5, Task 1 follow-up): captured BEFORE the call so we can
        // assert the promotion actually stamps `modified` at ship time -- BaseEntity.modified is
        // a plain field with no @PreUpdate/listener, so this only holds if changeState()
        // explicitly reassigns it.
        val beforeShip = Instant.now()
        val count = stockPicker.shipContainer(ul)

        assertThat(count).isEqualTo(1)
        // A1 (defect-burndown-5): the DB end state after ship is DELETABLE(1000), not
        // SHIPPED(680) -- SHIPPED remains the recorded ship state in the journal (see the
        // dedicated journal/outbox test below), but the stock rows are promoted ship-time.
        assertThat(stockState(stockId)).isEqualTo(1000) // DELETABLE
        assertThat(stockModified(stockId))
            .`as`("shipContainer's DELETABLE promotion must stamp modified at ship time, not " +
                "leave it at whatever value it had from an earlier state change")
            .isAfterOrEqualTo(beforeShip)
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `shipContainer leaves non-PACKED stock on the UL untouched`() {
        val itemDataId = 3301L
        val ul = createUnitLoad("UL-SHIP-${System.nanoTime()}")

        // One PACKED(650) unit and one PICKED(600) unit on the same container.
        val packedId = createStock(ul, itemDataId, 50.0)
        moveToState(packedId, 600)
        moveToState(packedId, 650)
        val pickedId = createStock(ul, itemDataId, 20.0)
        moveToState(pickedId, 600)

        tenantContext.clientId = 1L

        val count = stockPicker.shipContainer(ul)

        // Only the PACKED unit ships (and is promoted to DELETABLE); the PICKED unit is left at 600.
        assertThat(count).isEqualTo(1)
        assertThat(stockState(packedId)).isEqualTo(1000) // DELETABLE
        assertThat(stockState(pickedId)).isEqualTo(600) // still PICKED
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `shipContainer journals and outboxes both the SHIPPED and the DELETABLE transition`() {
        val itemDataId = 3303L
        val ul = createUnitLoad("UL-SHIP-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 40.0)
        moveToState(stockId, 600)
        moveToState(stockId, 650)

        tenantContext.clientId = 1L
        val journalCountBefore = journalRepository.count("stockUnitId = ?1", stockId)

        val count = stockPicker.shipContainer(ul)

        assertThat(count).isEqualTo(1)
        assertThat(stockState(stockId)).isEqualTo(1000) // DELETABLE
        // The unit load itself goes terminal too: nothing else keeps it occupied.
        assertThat(unitLoadState(ul)).isEqualTo(1000)

        // Two NEW journal rows: PACKED->SHIPPED (no activity marker, unchanged from before this
        // sprint) then SHIPPED->DELETABLE (the new cleanup marker), in that order.
        val journalRows = journalRepository.find("stockUnitId = ?1 order by id", stockId).list()
        assertThat(journalRows.size)
            .`as`("both the ship transition and the cleanup transition must be journaled")
            .isEqualTo(journalCountBefore.toInt() + 2)
        assertThat(journalRows[journalRows.size - 2].activityCode)
            .`as`("the SHIPPED entry keeps its pre-existing (unmarked) activity code")
            .isNull()
        assertThat(journalRows.last().activityCode)
            .`as`("the DELETABLE entry is the subsequent, distinctly-marked cleanup transition")
            .isEqualTo("SHIP_CLEANUP")

        // Outbox has a StateChanged row per transition, each attributed to the stock unit's own
        // clientId (the entity owner), matching every other changeState-driven transition.
        val stateChangedRows = outboxEvents.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", stockId, "StateChanged",
        ).list()
        val shippedRow = stateChangedRows.find { it.payload.contains("\"newState\": 680") }
        val deletableRow = stateChangedRows.find { it.payload.contains("\"newState\": 1000") }
        assertThat(shippedRow).`as`("must publish a StateChanged row for the ship transition").isNotNull
        assertThat(deletableRow).`as`("must publish a StateChanged row for the cleanup transition").isNotNull
        assertThat(shippedRow!!.tenantId).isEqualTo(1L)
        assertThat(deletableRow!!.tenantId).isEqualTo(1L)
    }
}
