package com.karyo.auth.config

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SystemPropertyRepository : PanacheRepository<SystemProperty> {

    /** Single-row lookup honoring a null context (null matches only the null-context row). */
    fun findRow(clientId: Long, key: String, context: String?): SystemProperty? =
        if (context == null) {
            find(
                "clientId = ?1 and propertyKey = ?2 and propertyContext is null",
                clientId, key,
            ).firstResult()
        } else {
            find(
                "clientId = ?1 and propertyKey = ?2 and propertyContext = ?3",
                clientId, key, context,
            ).firstResult()
        }

    fun findValue(clientId: Long, key: String, context: String?): String? =
        findRow(clientId, key, context)?.propertyValue

    fun listByClient(clientId: Long): List<SystemProperty> =
        list("clientId = ?1 order by propertyKey, propertyContext nulls first", clientId)
}
