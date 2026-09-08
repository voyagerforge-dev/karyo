package com.karyo.app

import com.karyo.inventory.repository.InactiveProductRepository
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
 * Guards the in-process CDI wiring between the product and inventory modules:
 * ProductService fires ItemDataStateChangedEvent, observed synchronously by
 * inventory's ProductStateChangedObserver which maintains the inactive_products
 * projection (replaces the former Kafka topic + consumer).
 */
@QuarkusTest
class ProductStateCdiFlowTest {

    @Inject
    lateinit var inactiveProductRepository: InactiveProductRepository

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"CDI Flow Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun updateProductState(productId: Long, state: Int) {
        given()
            .contentType(ContentType.JSON)
            .body("""{"state":$state}""")
            .`when`().put("/api/v1/products/$productId")
            .then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `product deactivation creates inactive_products row and reactivation removes it`() {
        // ItemUnit name is constrained to 20 chars -- keep the suffix short
        val suffix = System.nanoTime().toString().takeLast(10)
        val itemUnitId = createItemUnit("CU-$suffix")
        val productId = createProduct("CDI-SKU-$suffix", itemUnitId)

        // No projection row while the product is active
        assertThat(inactiveProductRepository.findByItemAndClient(productId, 1L)).isNull()

        // Deactivate (state 700) -> synchronous CDI event -> inactive_products row appears
        updateProductState(productId, 700)
        val row = inactiveProductRepository.findByItemAndClient(productId, 1L)
        assertThat(row).isNotNull
        assertThat(row!!.itemDataId).isEqualTo(productId)
        assertThat(row.clientId).isEqualTo(1L)

        // Reactivate (state 100) -> row is removed
        updateProductState(productId, 100)
        assertThat(inactiveProductRepository.findByItemAndClient(productId, 1L)).isNull()
    }
}
