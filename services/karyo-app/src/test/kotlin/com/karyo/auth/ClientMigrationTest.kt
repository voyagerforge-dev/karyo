package com.karyo.auth

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class ClientMigrationTest {

    @Inject
    lateinit var em: EntityManager

    @Test
    fun `seeds the system, ACME and GLOBEX clients`() {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            "SELECT id, name, number FROM karyo.clients WHERE id IN (0, 1, 2) ORDER BY id"
        ).resultList as List<Array<Any>>

        assertThat(rows).hasSize(3)
        assertThat(rows[0][0] as Number).isEqualTo(0L)
        assertThat(rows[0][2]).isEqualTo("SYS")
        assertThat(rows[1][2]).isEqualTo("ACME")
        assertThat(rows[2][2]).isEqualTo("GLOBEX")
    }

    @Test
    @Transactional
    fun `generated ids start at 100 so they never collide with seeded rows`() {
        val id = em.createNativeQuery(
            """
            INSERT INTO karyo.clients (version, created, modified, name, number, code, state)
            VALUES (0, now(), now(), 'Seq Guard Co', 'SEQGUARD', 'SG', 100)
            RETURNING id
            """.trimIndent()
        ).singleResult as Number

        assertThat(id.toLong()).isGreaterThanOrEqualTo(100L)
    }
}
