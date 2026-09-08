package com.karyo.security

import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A pick container is attributed to the owner named by the pick order (the work item),
 * not to the picker who happens to be executing it.
 */
@QuarkusTest
class WorkItemAttributionTest {

    @Inject
    lateinit var stockPicker: StockPicker

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Test
    @TestSecurity(user = "ops", roles = ["inventory-write", "ADMIN"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick container is attributed to the work item's owner`() {
        val suffix = System.nanoTime().toString().takeLast(8)
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val ulId = stockPicker.createPickContainer(
            clientId = 2L,
            unitLoadTypeId = unitLoadTypeRepository.listAll().first().id!!,
            locationId = 1L,
            locationName = "WIA-STAGING",
            labelId = "WIA-PO-$suffix",
        )

        assertThat(unitLoadRepository.findById(ulId)!!.clientId).isEqualTo(2L)
    }
}
