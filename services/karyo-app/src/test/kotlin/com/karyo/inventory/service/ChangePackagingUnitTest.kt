package com.karyo.inventory.service

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/** Row 15: `changePackagingUnit` reclassify-in-place. */
@QuarkusTest
class ChangePackagingUnitTest {

    private fun createUnitLoad(label: String, unitLoadTypeId: Long = 1): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":$unitLoadTypeId,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, name: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"$name","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createPackagingUnit(productId: Long, name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","amount":1.0}""")
            .`when`().post("/api/v1/products/$productId/packaging-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStockUnit(itemDataId: Long, itemDataNumber: String, unitLoadId: Long, amount: Double = 10.0): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$itemDataNumber","amount":$amount,"unitLoadId":$unitLoadId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun getStock(id: Long): ValidatableResponse =
        given().`when`().get("/api/v1/stock-units/$id").then().statusCode(200)

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `changing the packaging unit keeps the amount and returns the new classification`() {
        val suffix = "${System.nanoTime()}".takeLast(10)
        val itemUnitId = createItemUnit("CPU-IU-$suffix")
        val productId = createProduct("CPU-SKU-$suffix", "Widget CPU $suffix", itemUnitId)
        val puId = createPackagingUnit(productId, "Case-$suffix")
        val ulId = createUnitLoad("UL-CPU-OK-$suffix")
        val suId = createStockUnit(productId, "CPU-SKU-$suffix", ulId)

        val before = getStock(suId).extract().jsonPath()

        given()
            .contentType(ContentType.JSON)
            .body("""{"packagingUnitId":$puId}""")
            .`when`().post("/api/v1/stock-units/$suId/change-packaging-unit")
            .then().statusCode(200)
            .body("packagingUnitId", equalTo(puId.toInt()))

        assertThat(getStock(suId).extract().jsonPath().getDouble("amount"))
            .isEqualTo(before.getDouble("amount"))
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `a packaging unit belonging to a different item is refused with 400`() {
        val suffix = "${System.nanoTime()}".takeLast(10)
        val itemUnitId = createItemUnit("CPUM-IU-$suffix")
        val productA = createProduct("CPU-MIS-A-$suffix", "Product A $suffix", itemUnitId)
        val productB = createProduct("CPU-MIS-B-$suffix", "Product B $suffix", itemUnitId)
        val puId = createPackagingUnit(productA, "Case-$suffix")
        val ulId = createUnitLoad("UL-CPU-MIS-$suffix")
        val suId = createStockUnit(productB, "CPU-MIS-B-$suffix", ulId)

        given()
            .contentType(ContentType.JSON)
            .body("""{"packagingUnitId":$puId}""")
            .`when`().post("/api/v1/stock-units/$suId/change-packaging-unit")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `an unknown packaging unit id is refused with 400`() {
        val suffix = "${System.nanoTime()}".takeLast(10)
        val ulId = createUnitLoad("UL-CPU-UNK-$suffix")
        val suId = createStockUnit(10L, "CPU-UNK-$suffix", ulId)

        given()
            .contentType(ContentType.JSON)
            .body("""{"packagingUnitId":987654321}""")
            .`when`().post("/api/v1/stock-units/$suId/change-packaging-unit")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `a null packaging unit id clears the classification`() {
        val suffix = "${System.nanoTime()}".takeLast(10)
        val itemUnitId = createItemUnit("CPUC-IU-$suffix")
        val productId = createProduct("CPU-CLR-$suffix", "Widget CLR $suffix", itemUnitId)
        val puId = createPackagingUnit(productId, "Case-$suffix")
        val ulId = createUnitLoad("UL-CPU-CLR-$suffix")
        val suId = createStockUnit(productId, "CPU-CLR-$suffix", ulId)

        given()
            .contentType(ContentType.JSON)
            .body("""{"packagingUnitId":$puId}""")
            .`when`().post("/api/v1/stock-units/$suId/change-packaging-unit")
            .then().statusCode(200)
            .body("packagingUnitId", equalTo(puId.toInt()))

        given()
            .contentType(ContentType.JSON)
            .body("""{"packagingUnitId":null}""")
            .`when`().post("/api/v1/stock-units/$suId/change-packaging-unit")
            .then().statusCode(200)
            .body("packagingUnitId", nullValue())
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `a stock unit at or past PICKED is refused with 422`() {
        val suffix = "${System.nanoTime()}".takeLast(10)
        val itemUnitId = createItemUnit("CPUP-IU-$suffix")
        val productId = createProduct("CPU-PICK-$suffix", "Widget PICK $suffix", itemUnitId)
        val puId = createPackagingUnit(productId, "Case-$suffix")
        val ulId = createUnitLoad("UL-CPU-PICK-$suffix")
        val suId = createStockUnit(productId, "CPU-PICK-$suffix", ulId)

        given()
            .contentType(ContentType.JSON)
            .body("""{"state":600}""")
            .`when`().post("/api/v1/stock-units/$suId/change-state")
            .then().statusCode(200)
            .body("state", equalTo(600))

        given()
            .contentType(ContentType.JSON)
            .body("""{"packagingUnitId":$puId}""")
            .`when`().post("/api/v1/stock-units/$suId/change-packaging-unit")
            .then().statusCode(422)
    }
}
