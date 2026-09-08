package com.karyo.product

import com.karyo.product.spi.SubstitutionLookup
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

@QuarkusTest
class ItemSubstitutionTest {

    @Inject
    lateinit var substitutionLookup: SubstitutionLookup

    @Inject
    lateinit var tenantContext: TenantContext

    @Test
    @TestSecurity(user = "mgr", roles = ["product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create substitution then resolve it via the lookup, priority-ordered`() {
        // primary 9001 substitutes -> 9003 (prio 2), 9002 (prio 1)
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":9001,"substituteItemDataId":9003,"priority":2}""")
            .`when`().post("/api/v1/item-substitutions").then().statusCode(201)
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":9001,"substituteItemDataId":9002,"priority":1}""")
            .`when`().post("/api/v1/item-substitutions").then().statusCode(201)

        // REST list
        given().`when`().get("/api/v1/item-substitutions?itemDataId=9001")
            .then().statusCode(200)
            .body("size()", `is`(2))
            .body("[0].substituteItemDataId", `is`(9002))  // priority 1 first

        // SPI: active substitutes ordered by priority.
        // TenantContext is @RequestScoped and only populated by TenantFilter during HTTP
        // requests; a direct SPI call has no JWT, so set the clientId explicitly to match
        // the rows created above (client_id=1 from the JWT claim).
        tenantContext.clientId = 1L
        val subs = substitutionLookup.findSubstitutes(9001)
        assertThat(subs.map { it.substituteItemDataId }).containsExactly(9002L, 9003L)
    }
}
