package com.karyo.work

import com.karyo.security.TenantContext
import com.karyo.work.service.DefaultWorkEligibilityResolver
import com.karyo.work.service.WorkGroupService
import com.karyo.work.vo.WorkType
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@QuarkusTest
class WorkEligibilityResolverTest {
    @Inject lateinit var resolver: DefaultWorkEligibilityResolver
    @Inject lateinit var groupService: WorkGroupService
    @Inject lateinit var tenantContext: TenantContext

    // clientId 8100 reserved for "no groups => all types"
    @Test
    @TestSecurity(user = "alice", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8100")])
    fun `no groups means eligible for all types`() {
        // TenantContext is @RequestScoped; populated by TenantFilter only during REST requests.
        // For direct CDI calls we prime it manually.
        tenantContext.clientId = 8100L
        assertThat(resolver.resolve("alice", 8100)).containsExactlyInAnyOrderElementsOf(WorkType.entries)
    }

    // clientId 8101 reserved for membership-union
    @Test
    @TestSecurity(user = "bob", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8101")])
    fun `member eligibility is the union of group work-types, non-member sees nothing`() {
        tenantContext.clientId = 8101L
        val pickers = groupService.create("Pickers-8101", setOf("PICK"), null)
        groupService.create("Counters-8101", setOf("COUNT"), null)
        groupService.addMember(pickers.id!!, "bob")

        assertThat(resolver.resolve("bob", 8101)).containsExactlyInAnyOrder(WorkType.PICK)
        assertThat(resolver.resolve("carol", 8101)).isEmpty()
    }

    // clientId 8102 = owner tenant, 8103 = attacker tenant
    // Regression test for cross-tenant IDOR: removeMember must reject a groupId owned by another tenant
    @Test
    @TestSecurity(user = "dave", roles = ["inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8102")])
    fun `removeMember rejects groupId belonging to a different tenant`() {
        // Create a group + member under the owner tenant (8102)
        tenantContext.clientId = 8102L
        val ownerGroup = groupService.create("OwnerGroup-8102", setOf("PICK"), null)
        groupService.addMember(ownerGroup.id!!, "dave")

        // Confirm the membership exists before the attack
        assertThat(resolver.resolve("dave", 8102)).containsExactlyInAnyOrder(WorkType.PICK)

        // Switch tenant context to simulate an attacker on tenant 8103 trying to delete
        // the owner's group membership using its ID
        tenantContext.clientId = 8103L
        assertThrows<NoSuchElementException> {
            groupService.removeMember(ownerGroup.id!!, "dave")
        }

        // Verify the owner's membership is still intact
        tenantContext.clientId = 8102L
        assertThat(resolver.resolve("dave", 8102)).containsExactlyInAnyOrder(WorkType.PICK)
    }
}
