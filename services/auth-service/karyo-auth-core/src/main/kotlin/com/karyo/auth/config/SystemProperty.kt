package com.karyo.auth.config

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * One stored runtime-config value (SC16, myWMS SystemProperty behavioral parity).
 *
 * Rows are scoped by `(client_id, property_key, property_context)` — unique with
 * `NULLS NOT DISTINCT` (V1202), so at most one null-context row exists per client/key.
 * Client 0 rows act as the instance-wide (SYSTEM) fallback for every goods owner.
 *
 * Catalog metadata (type, group, default) is code-owned in [SystemPropertyCatalog], never
 * stored — a row only carries the value plus optional free-text description/group so that
 * non-catalog (extension/custom) keys remain self-describing.
 */
@Entity
@Table(name = "system_properties")
class SystemProperty : TenantEntity() {

    @Column(name = "property_key", nullable = false, length = 255)
    lateinit var propertyKey: String

    /** Optional finer scope (e.g. a workstation); null = the client-wide row. */
    @Column(name = "property_context", length = 255)
    var propertyContext: String? = null

    @Column(name = "property_value", length = 2000)
    var propertyValue: String? = null

    @Column(length = 2000)
    var description: String? = null

    @Column(name = "property_group", length = 255)
    var propertyGroup: String? = null
}
