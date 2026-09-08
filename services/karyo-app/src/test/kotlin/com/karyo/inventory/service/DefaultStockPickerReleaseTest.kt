package com.karyo.inventory.service

import com.karyo.inventory.api.spi.ReservationRequest
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockReserver
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class DefaultStockPickerReleaseTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var stockReserver: StockReserver

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
                """{"itemDataId":$itemDataId,"itemDataNumber":"RSV","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun reservedAmount(id: Long): Float =
        given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200)
            .extract().jsonPath().getFloat("reservedAmount")

    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
        ],
    )
    fun `releaseUnpickedReservation frees the reserved remainder, coerced at zero`() {
        // Unique itemDataId so the reservation lands on the stock unit we create (no FIFO ambiguity).
        val itemDataId = 7700_000L + (System.nanoTime() % 100_000)
        val ul = createUnitLoad("UL-RSV-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 100.0)
        tenantContext.clientId = 1L
        stockReserver.reserve(ReservationRequest(itemDataId = itemDataId, amount = BigDecimal(60)))
        assertThat(reservedAmount(stockId)).isEqualTo(60.0f)

        stockPicker.releaseUnpickedReservation(stockId, BigDecimal(20))
        assertThat(reservedAmount(stockId)).isEqualTo(40.0f)

        // Releasing more than what remains reserved is coerced at zero here by the PICKER's own
        // `coerceAtMost(reserved)` before it ever calls into the service — never negative. This is
        // structurally legal: the picker always clamps to the LIVE reservedAmount it just read, so
        // it can't ask the service to over-release. `StockService.releaseReservation` itself no
        // longer clamps; it refuses an amount greater than what's reserved (defect row 1).
        stockPicker.releaseUnpickedReservation(stockId, BigDecimal(1000))
        assertThat(reservedAmount(stockId)).isEqualTo(0.0f)
    }

    /**
     * Row :2030: the explicit-`clientId` overload must resolve its scope from the passed
     * `clientId` ALONE -- proven from both sides, see the twin test in
     * `DefaultStockPickerPackTest` for why one side on its own would be ambiguous.
     *
     * The foreign-call contract here is HARDER than the container methods': this path goes
     * through `StockService.findByIdForWrite`, which throws [InventoryException.NotFound] for a
     * row its write scope does not permit (a foreign row is reported as absent, never as a
     * distinct "forbidden"). So a foreign `clientId` throws rather than quietly releasing
     * nothing, and `reservedAmount` is untouched.
     */
    @Test
    @TestSecurity(user = "op", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "1"),
            Claim(key = "tenant_code", value = "ACME"),
        ],
    )
    fun `releaseUnpickedReservation with explicit clientId ignores the ambient tenant`() {
        val itemDataId = 7710_000L + (System.nanoTime() % 100_000)
        val ul = createUnitLoad("UL-RSV-X-${System.nanoTime()}")
        val stockId = createStock(ul, itemDataId, 100.0)
        stockReserver.reserve(ReservationRequest(itemDataId = itemDataId, amount = BigDecimal(60)), OWNER_CLIENT)
        assertThat(reservedAmount(stockId)).isEqualTo(60.0f)

        // Ambient holds the RIGHT owner; the foreign explicit clientId must still refuse.
        tenantContext.clientId = OWNER_CLIENT
        assertThatThrownBy { stockPicker.releaseUnpickedReservation(stockId, BigDecimal(20), FOREIGN_CLIENT) }
            .isInstanceOf(InventoryException.NotFound::class.java)
        assertThat(reservedAmount(stockId)).`as`("a refused release changes nothing").isEqualTo(60.0f)

        // Ambient holds the unprimed default (client 0); the owner explicit clientId still works.
        tenantContext.clientId = 0L
        stockPicker.releaseUnpickedReservation(stockId, BigDecimal(20), OWNER_CLIENT)
        assertThat(reservedAmount(stockId)).isEqualTo(40.0f)
    }

    private companion object {
        const val OWNER_CLIENT = 1L
        const val FOREIGN_CLIENT = 9977L
    }
}
