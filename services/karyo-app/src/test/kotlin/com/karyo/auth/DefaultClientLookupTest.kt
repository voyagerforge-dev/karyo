package com.karyo.auth

import com.karyo.auth.dto.CreateClientRequest
import com.karyo.auth.service.ClientService
import com.karyo.auth.spi.ClientLookup
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class DefaultClientLookupTest {

    @Inject
    lateinit var lookup: ClientLookup

    @Inject
    lateinit var clientService: ClientService

    @Inject
    lateinit var tenantContext: TenantContext

    private fun suffix() = System.nanoTime().toString().takeLast(8)

    @Test
    fun `returns names for the seeded clients`() {
        tenantContext.principalKind = PrincipalKind.OPS

        val names = lookup.findNamesByIds(setOf(0L, 1L, 2L))

        assertThat(names).containsEntry(0L, "System")
        assertThat(names).containsEntry(1L, "ACME Corporation")
        assertThat(names).containsEntry(2L, "Globex Corporation")
    }

    @Test
    fun `omits unknown ids instead of throwing`() {
        tenantContext.principalKind = PrincipalKind.OPS

        val names = lookup.findNamesByIds(setOf(1L, 999_999L))

        assertThat(names).containsKey(1L)
        assertThat(names).doesNotContainKey(999_999L)
    }

    @Test
    fun `returns an empty map for empty input`() {
        assertThat(lookup.findNamesByIds(emptySet())).isEmpty()
    }

    @Test
    fun `exists distinguishes a seeded client from an unknown id`() {
        assertThat(lookup.exists(1L)).isTrue()
        assertThat(lookup.exists(999_999L)).isFalse()
    }

    @Test
    fun `isActive is true for an ACTIVE client`() {
        tenantContext.principalKind = PrincipalKind.OPS
        val s = suffix()
        val created = clientService.create(CreateClientRequest(name = "Lookup Active $s", number = "LA$s"))

        assertThat(lookup.isActive(created.id)).isTrue()
    }

    @Test
    fun `isActive is false for an INACTIVE client and for an unknown id`() {
        tenantContext.principalKind = PrincipalKind.OPS
        val s = suffix()
        val created = clientService.create(CreateClientRequest(name = "Lookup Retired $s", number = "LR$s"))
        clientService.deactivate(created.id)

        assertThat(lookup.isActive(created.id)).isFalse()
        assertThat(lookup.isActive(-99L)).isFalse()
    }

    @Test
    fun `an owner principal cannot read another goods owner's name`() {
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val names = lookup.findNamesByIds(setOf(1L, 2L))

        assertThat(names).containsKey(1L)
        assertThat(names).doesNotContainKey(2L)
    }
}
