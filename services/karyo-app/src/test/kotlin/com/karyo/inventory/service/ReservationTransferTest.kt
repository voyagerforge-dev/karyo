package com.karyo.inventory.service

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.exception.InventoryException
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
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Exercises [StockService] reserve/release/transfer directly — no REST round-trip. This used to
 * go through the (now deleted) `/api/internal/stock-units/{id}/...` router; converted 1:1, same
 * scenarios/assertions. Seed data is still created via `/api/v1` REST endpoints since that
 * surface stays.
 *
 * TenantContext is @RequestScoped and is normally populated by TenantFilter on HTTP requests
 * only. Since these calls now go directly into the service, clientId is primed manually to
 * match the seed data's owner (client_id=1, via the @OidcSecurity claim on each test).
 */
@QuarkusTest
class ReservationTransferTest {

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxEventRepository: OutboxEventRepository

    private fun createUnitLoad(label: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(
        unitLoadId: Long,
        itemDataId: Long,
        itemNumber: String,
        amount: Double,
        state: Int = 300,
        lotNumber: String? = null,
    ): Long {
        val lotField = if (lotNumber != null) ""","lotNumber":"$lotNumber"""" else ""
        return given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,"unitLoadId":$unitLoadId,"state":$state$lotField}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `reserve stock reduces available amount`() {
        val ul = createUnitLoad("UL-RESERVE-${System.nanoTime()}")
        val suId = createStock(ul, 5001L, "RESERVE-001", 100.0)
        tenantContext.clientId = 1L

        val response = stockService.reserveStock(suId, BigDecimal("30"), "order-123", tenantContext)

        assertThat(response.stockUnitId).isEqualTo(suId)
        assertThat(response.reservedAmount).isEqualByComparingTo(BigDecimal("30.0"))
        assertThat(response.newAvailableAmount).isEqualByComparingTo(BigDecimal("70.0"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `reserve fails when insufficient available`() {
        val ul = createUnitLoad("UL-RESERVE-FAIL-${System.nanoTime()}")
        val suId = createStock(ul, 5002L, "RESERVE-002", 10.0)
        tenantContext.clientId = 1L

        assertThrows<InventoryException.InsufficientStock> {
            stockService.reserveStock(suId, BigDecimal("50"), "order-456", tenantContext)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `release reservation reduces reservedAmount and publishes an AmountChanged outbox event`() {
        val ul = createUnitLoad("UL-RELEASE-${System.nanoTime()}")
        val suId = createStock(ul, 5004L, "RELEASE-001", 100.0)
        tenantContext.clientId = 1L

        // reserveStock already publishes one AmountChanged event for this aggregate; count it
        // as a baseline so the assertion below is about what releaseReservation itself publishes.
        stockService.reserveStock(suId, BigDecimal("30"), "order-789", tenantContext)
        val countAfterReserve = outboxEventRepository.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", suId, "AmountChanged",
        )

        stockService.releaseReservation(suId, BigDecimal("30"), "order-789", tenantContext)

        val countAfterRelease = outboxEventRepository.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", suId, "AmountChanged",
        )
        assertThat(countAfterRelease)
            .`as`("releaseReservation must publish its own AmountChanged outbox event, same as reserveStock")
            .isEqualTo(countAfterReserve + 1)

        val event = outboxEventRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3 order by created desc",
            "StockUnit", suId, "AmountChanged",
        ).firstResult()!!
        assertThat(event.tenantId)
            .`as`("outbox tenantId must be the stock's owner (client 1)")
            .isEqualTo(1L)
        assertThat(event.payload)
            .`as`("release must be distinguishable from reserve by activityCode")
            .contains("\"activityCode\": \"RELEASE\"")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `release reservation refuses an amount greater than what is reserved`() {
        val ul = createUnitLoad("UL-RELEASE-OVER-${System.nanoTime()}")
        val suId = createStock(ul, 5005L, "RELEASE-OVER-001", 100.0)
        tenantContext.clientId = 1L

        stockService.reserveStock(suId, BigDecimal("30"), "order-over-1", tenantContext)
        val countBefore = outboxEventRepository.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", suId, "AmountChanged",
        )

        assertThrows<InventoryException.InsufficientStock> {
            stockService.releaseReservation(suId, BigDecimal("40"), "order-over-1", tenantContext)
        }

        val reservedAfter = given()
            .`when`().get("/api/v1/stock-units/$suId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("reservedAmount")
        assertThat(reservedAfter)
            .`as`("an over-release must be refused, not clamped — reservedAmount stays untouched")
            .isEqualTo(30.0)

        val countAfter = outboxEventRepository.count(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", suId, "AmountChanged",
        )
        assertThat(countAfter)
            .`as`("the refusal must precede the journal/outbox writes — no new AmountChanged row")
            .isEqualTo(countBefore)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `transfer stock between unit loads`() {
        val sourceUL = createUnitLoad("UL-XFER-SRC-${System.nanoTime()}")
        val targetUL = createUnitLoad("UL-XFER-TGT-${System.nanoTime()}")
        val suId = createStock(sourceUL, 5003L, "XFER-001", 100.0)
        tenantContext.clientId = 1L

        // Transfer 40 from source to target
        val transferred = stockService.transferStock(suId, targetUL, BigDecimal("40"), "TRANSFER", tenantContext)

        assert(transferred.amount.compareTo(BigDecimal("40.0")) == 0) {
            "Expected transferred amount=40.0 but got ${transferred.amount}"
        }

        // Verify source reduced to 60
        val sourceAmount = given()
            .`when`().get("/api/v1/stock-units/$suId")
            .then()
            .statusCode(200)
            .extract().jsonPath().getDouble("amount")

        assert(sourceAmount == 60.0) { "Expected source amount=60.0 but got $sourceAmount" }
    }
}
