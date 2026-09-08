package com.karyo.auth.repository

import com.karyo.auth.domain.model.Client
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ClientRepository : PanacheRepository<Client> {

    fun findByNumber(number: String): Client? = find("number", number).firstResult()

    fun findByName(name: String): Client? = find("name", name).firstResult()

    fun findByIds(ids: Collection<Long>): List<Client> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    fun listAllOrdered(): List<Client> = list("order by number")

    /**
     * Every `client_id` present in operational data that has no row in `clients`.
     *
     * Because no foreign keys enforce this (design D3), the check is done by scanning
     * `information_schema` for every base table in the `karyo` schema carrying a `client_id`
     * column and unioning their distinct values. Discovering the tables rather than
     * listing them keeps the report correct as new modules add tables. Views are excluded
     * (`table_type = 'BASE TABLE'`) — `information_schema.columns` also matches views, and
     * the `karyo` schema has several reporting views that expose a `client_id` column copied
     * verbatim from an already-validated base table; scanning them would be redundant work
     * today and a latent false-positive source if a future view ever synthesizes `client_id`.
     */
    fun findDanglingClientIds(): List<Long> {
        val tables = getEntityManager().createNativeQuery(
            """
            SELECT c.table_name FROM information_schema.columns c
            JOIN information_schema.tables t
                ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.table_schema = 'karyo' AND c.column_name = 'client_id' AND t.table_type = 'BASE TABLE'
            ORDER BY c.table_name
            """.trimIndent()
        ).resultList.map { it as String }

        if (tables.isEmpty()) return emptyList()

        val union = tables.joinToString(" UNION ") {
            """SELECT DISTINCT client_id FROM karyo."$it" WHERE client_id IS NOT NULL"""
        }
        @Suppress("UNCHECKED_CAST")
        return getEntityManager().createNativeQuery(
            """
            SELECT used.client_id FROM ($union) AS used
            LEFT JOIN karyo.clients c ON c.id = used.client_id
            WHERE c.id IS NULL
            ORDER BY 1
            """.trimIndent()
        ).resultList.map { (it as Number).toLong() }
    }
}
