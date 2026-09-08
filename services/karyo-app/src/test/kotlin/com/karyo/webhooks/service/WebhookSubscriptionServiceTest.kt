package com.karyo.webhooks.service

import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Service-layer cross-tenant isolation tests.
 *
 * @TestSecurity pins clientId=0 for REST-layer tests so these run at the service layer instead,
 * passing clientId explicitly — the established pattern in this repo.  @TestTransaction rolls
 * back every test so tenant-99 rows do not leak into the shared Testcontainers DB.
 */
@QuarkusTest
class WebhookSubscriptionServiceTest {

    @Inject
    lateinit var service: WebhookSubscriptionService

    @Inject
    lateinit var repo: WebhookSubscriptionRepository

    /** Helper: persist a subscription directly under the given tenant (bypasses URL validation). */
    private fun persistSub(clientId: Long): WebhookSubscription =
        WebhookSubscription().apply {
            this.clientId = clientId
            name = "sub-tenant$clientId"
            targetUrl = "https://tenant$clientId.test/hook"
            secret = "shh-$clientId"
            eventTypes = listOf("*")
            active = true
        }.also { repo.persist(it) }

    @Test
    @TestTransaction
    fun `tenant 1 cannot read a subscription owned by tenant 99`() {
        val sub = persistSub(99L)
        val id = sub.id!!

        assertThrows(NotFoundException::class.java) {
            service.get(1L, id)
        }
    }

    @Test
    @TestTransaction
    fun `tenant 1 cannot update a subscription owned by tenant 99`() {
        val sub = persistSub(99L)
        val id = sub.id!!

        assertThrows(NotFoundException::class.java) {
            service.update(1L, id, name = "hijacked", url = null, eventTypes = null, active = null)
        }
    }

    @Test
    @TestTransaction
    fun `tenant 1 cannot delete a subscription owned by tenant 99`() {
        val sub = persistSub(99L)
        val id = sub.id!!

        assertThrows(NotFoundException::class.java) {
            service.delete(1L, id)
        }
    }
}
