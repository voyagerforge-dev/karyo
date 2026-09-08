package com.karyo.inventory.service

import com.karyo.inventory.api.spi.ReservationRequest
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.inventory.api.vo.StockSelectionRequest
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
import java.math.BigDecimal

@QuarkusTest
class StockSelectionExcludeTest {

    @Inject
    lateinit var stockReserver: StockReserver

    @Inject
    lateinit var stockSelectionService: StockSelectionService

    @Inject
    lateinit var tenantContext: TenantContext

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,""" +
                    """"storageLocationName":"A-01-01"}""",
            )
            .`when`().post("/api/v1/unit-loads").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"EXC","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
        ],
    )
    fun `excludeStockUnitIds skips the excluded unit during selection`() {
        val itemDataId = 8800L + (System.nanoTime() % 100_000)
        val a = createStock(createUnitLoad("UL-A-${System.nanoTime()}"), itemDataId, 60.0)
        val b = createStock(createUnitLoad("UL-B-${System.nanoTime()}"), itemDataId, 40.0)
        tenantContext.clientId = 1L

        val outcome = stockReserver.reserve(
            ReservationRequest(itemDataId = itemDataId, amount = BigDecimal(30), excludeStockUnitIds = listOf(a)),
        )

        // Excluding A (FIFO-first) forces the reservation onto B.
        assertThat(outcome.shortfall).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(outcome.reservations.map { it.stockUnitId }).containsExactly(b)
        assertThat(outcome.reservations.first().amount).isEqualByComparingTo(BigDecimal(30))
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
        ],
    )
    fun `stock on a locked unit load is excluded from selection via the cascaded stock lock`() {
        val itemDataId = 8900L + (System.nanoTime() % 100_000)
        val ulId = createUnitLoad("UL-LOCKSEL-${System.nanoTime()}")
        createStock(ulId, itemDataId, 50.0)

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$ulId/lock")
            .then().statusCode(200)

        val response = stockSelectionService.selectStock(
            StockSelectionRequest(itemDataId = itemDataId, amount = BigDecimal(10), clientId = 1L),
        )

        assertThat(response.stocks).isEmpty()
        assertThat(response.fullyFulfilled).isFalse()
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
        ],
    )
    fun `stock arriving on an already-locked pallet is excluded from selection`() {
        val itemDataId = 9000L + (System.nanoTime() % 100_000)
        val ulId = createUnitLoad("UL-LATEARRIVAL-${System.nanoTime()}")

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$ulId/lock")
            .then().statusCode(200)

        // Late arrival: stock lands on the ALREADY-LOCKED pallet. Its own lockType is 0 —
        // the F1 gap — so this must be excluded solely by the unitLoad.lockType predicate.
        val stockId = createStock(ulId, itemDataId, 50.0)
        val landedLockType = given()
            .`when`().get("/api/v1/stock-units/$stockId")
            .then().statusCode(200)
            .extract().jsonPath().getInt("lockType")
        assertThat(landedLockType).isEqualTo(0)

        val response = stockSelectionService.selectStock(
            StockSelectionRequest(itemDataId = itemDataId, amount = BigDecimal(10), clientId = 1L),
        )

        assertThat(response.stocks).isEmpty()
        assertThat(response.fullyFulfilled).isFalse()
    }

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
        ],
    )
    fun `useLockedStock strategy still reaches stock on a locked pallet`() {
        val itemDataId = 9100L + (System.nanoTime() % 100_000)
        val ulId = createUnitLoad("UL-USELOCKED-${System.nanoTime()}")

        given().contentType(ContentType.JSON).body("""{"lockType":1}""")
            .`when`().post("/api/v1/unit-loads/$ulId/lock")
            .then().statusCode(200)

        val stockId = createStock(ulId, itemDataId, 50.0)

        val response = stockSelectionService.selectStock(
            StockSelectionRequest(
                itemDataId = itemDataId,
                amount = BigDecimal(10),
                clientId = 1L,
                useLockedStock = true,
            ),
        )

        assertThat(response.fullyFulfilled).isTrue()
        assertThat(response.stocks.map { it.stockUnitId }).contains(stockId)
    }
}
