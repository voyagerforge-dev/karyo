package com.karyo.work

import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import com.karyo.work.service.WorkGroupService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

/**
 * Regression test for defense-in-depth eligibility enforcement on tap-to-claim.
 *
 * Before the fix: POST /{ref}/claim skipped the eligibility check — a Picker could claim
 * a MOVE by guessing the ref. After the fix: [WorkInboxResource.claim] calls
 * [com.karyo.work.service.WorkDispatchService.eligibleTypes] and rejects with 403.
 *
 * clientId 8500 reserved for this suite.
 */
@QuarkusTest
class WorkClaimEligibilityTest {

    @Inject lateinit var transportRepo: TransportOrderRepository
    @Inject lateinit var workGroupService: WorkGroupService
    @Inject lateinit var tenantContext: TenantContext

    @Transactional
    fun seedReleasedMove(clientId: Long): Long {
        val o = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "WI-ELIG-$clientId-${System.nanoTime() % 10_000}"
            transportType = TransportType.MOVE
            unitLoadId = 1; unitLoadLabel = "UL-ELIG"
            sourceLocationId = 1; sourceLocationName = "A-01"
            state = OrderState.RELEASED.code
            prio = 50
            executorType = "HUMAN"
        }
        transportRepo.persist(o)
        return o.id!!
    }

    /**
     * Operator "picker" belongs to a "Pickers-8500" group covering only PICK.
     * A RELEASED MOVE item exists for tenant 8500.
     * Tap-to-claim that MOVE ref as "picker" → 403 (not eligible for MOVE).
     */
    @Test
    @TestSecurity(user = "picker", roles = ["inventory-read", "task-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8500")])
    fun `claim ineligible work type returns 403`() {
        val moveId = seedReleasedMove(8500L)

        // Prime the RequestScoped TenantContext for the CDI calls below (outside HTTP request).
        tenantContext.clientId = 8500L
        val group = workGroupService.create("Pickers-8500", setOf("PICK"), null)
        workGroupService.addMember(group.id!!, "picker")

        // REST call: TenantFilter sets clientId=8500, username="picker".
        // eligibleTypes returns {PICK}; MOVE ∉ {PICK} → ForbiddenException → 403.
        given()
            .`when`().post("/api/v1/work/MOVE:$moveId/claim")
            .then().statusCode(403)
    }
}
