package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockPicker
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
class DefaultStockPickerTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    /** A "Pick Bin" UL (type 2, aggregate_stocks=TRUE) — the accumulating pick-container case. */
    private fun createPickBin(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":2,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"PICK","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun stockState(id: Long): Int =
        given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200).extract().jsonPath().getInt("state")

    private fun stockAmount(id: Long): Float =
        given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200).extract().jsonPath().getFloat("amount")

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pickStock moves amount to PICKED and decrements the source`() {
        val itemDataId = 3200L
        val sourceUl = createUnitLoad("UL-PICK-SRC-${System.nanoTime()}")
        val targetUl = createUnitLoad("UL-PICK-TGT-${System.nanoTime()}")
        val sourceStock = createStock(sourceUl, itemDataId, 100.0)

        // TenantContext is @RequestScoped and is populated by TenantFilter only during REST
        // requests. For the direct SPI call we set clientId explicitly so DefaultStockPicker
        // can find the stock unit it just created (clientId=1 from the seeding REST calls above).
        tenantContext.clientId = 1L

        val pickedId = stockPicker.pickStock(sourceStock, BigDecimal(40), targetUl)

        // Source decremented to 60; the picked stock (40) sits on the target UL in PICKED(600).
        assertThat(stockAmount(sourceStock)).isEqualTo(60.0f)
        assertThat(stockState(pickedId)).isEqualTo(600)
        assertThat(stockAmount(pickedId)).isEqualTo(40.0f)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `accumulating picks of the same item onto a Pick Bin merge without a re-flip error`() {
        // The pick container aggregates: a second pick of the same SKU merges into the first
        // (already-PICKED) stock. Without the idempotency guard, re-flipping it to PICKED would
        // throw a forward-only violation. This proves the guard.
        val itemDataId = 3201L
        val src1 = createUnitLoad("UL-ACC-SRC1-${System.nanoTime()}")
        val src2 = createUnitLoad("UL-ACC-SRC2-${System.nanoTime()}")
        val pickBin = createPickBin("UL-ACC-BIN-${System.nanoTime()}")
        val stock1 = createStock(src1, itemDataId, 40.0)
        val stock2 = createStock(src2, itemDataId, 30.0)
        tenantContext.clientId = 1L

        val first = stockPicker.pickStock(stock1, BigDecimal(40), pickBin)
        val second = stockPicker.pickStock(stock2, BigDecimal(30), pickBin)

        // Both picks merged into one stock on the bin: same unit, accumulated 70, still PICKED.
        assertThat(second).isEqualTo(first)
        assertThat(stockState(first)).isEqualTo(600)
        assertThat(stockAmount(first)).isEqualTo(70.0f)
    }
}
