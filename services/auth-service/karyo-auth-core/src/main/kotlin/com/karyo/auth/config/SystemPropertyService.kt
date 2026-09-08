package com.karyo.auth.config

import com.karyo.auth.dto.PropertySource
import com.karyo.auth.dto.SystemPropertyView
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.ConfigProvider
import java.time.Instant

/**
 * SC16 runtime-config store: typed getters over a per-key fallback ladder plus the
 * admin-facing effective view and upsert/delete.
 *
 * Fallback ladder (per key, null-context rows):
 * 1. stored row for the exact client
 * 2. stored client-0 (instance-wide) row
 * 3. MicroProfile config value for the same key (application.yaml / env var)
 * 4. the caller-supplied default
 *
 * A stored row whose value is null never satisfies a rung — `DELETE` is the way to unset.
 *
 * No caching in v1: every read is a bounded indexed lookup and the consumers are
 * per-request admin/config paths (the over-receipt guard fires only on an actual
 * over-receipt). StockUnit-style hot paths are NOT expected consumers yet; if one ever
 * appears, add a short-TTL Caffeine layer rather than widening this class's contract.
 *
 * Reads are `@Transactional` too — direct-CDI test/consumer sequences (read → write → read)
 * otherwise reuse a stale no-tx ambient session and see pre-write state.
 */
@ApplicationScoped
class SystemPropertyService(
    private val repository: SystemPropertyRepository,
    private val catalog: SystemPropertyCatalog,
) {

    @Transactional
    fun getString(key: String, clientId: Long, default: String?): String? {
        val (value, source) = resolveWithSource(key, clientId)
        return if (source == PropertySource.DEFAULT) default else value
    }

    /** Parses "true"/"false" case-insensitively; any other resolved value → [default]. */
    @Transactional
    fun getBoolean(key: String, clientId: Long, default: Boolean): Boolean =
        when (getString(key, clientId, null)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

    /** Parses a base-10 integer; any non-numeric resolved value → [default]. */
    @Transactional
    fun getInt(key: String, clientId: Long, default: Int): Int =
        getString(key, clientId, null)?.trim()?.toIntOrNull() ?: default

    /**
     * Upsert (find-then-update, so `@Version` optimistic locking stays honest). Catalog keys
     * are type-validated; unknown keys are stored as-is. Throws [IllegalArgumentException]
     * on a catalog type violation (mapped to 422 at the REST layer).
     */
    @Transactional
    fun set(clientId: Long, key: String, context: String?, value: String, description: String? = null): SystemProperty {
        validate(key, value)
        val def = catalog.byKey(key)
        val row = repository.findRow(clientId, key, context) ?: SystemProperty().also {
            it.clientId = clientId
            it.propertyKey = key
            it.propertyContext = context
            repository.persist(it)
        }
        row.propertyValue = value
        row.propertyGroup = def?.group ?: row.propertyGroup
        description?.let { row.description = it }
        row.modified = Instant.now()
        return row
    }

    /** Removes the stored row (the key reverts to its fallback). False if no row existed. */
    @Transactional
    fun delete(clientId: Long, key: String, context: String?): Boolean {
        val row = repository.findRow(clientId, key, context) ?: return false
        repository.delete(row)
        return true
    }

    /**
     * The admin screen's list: every catalog key with metadata + resolved effective value +
     * source, plus any stored rows the catalog does not already represent (non-catalog keys,
     * and catalog keys stored under a non-null context).
     */
    @Transactional
    fun effectiveView(clientId: Long): List<SystemPropertyView> {
        val catalogViews = catalog.all().map { def -> resolveView(def, clientId) }
        val extras = repository.listByClient(clientId)
            .filter { catalog.byKey(it.propertyKey) == null || it.propertyContext != null }
            .map { viewOf(it) }
        return (catalogViews + extras).map(::masked)
    }

    /**
     * Write-only semantics for secret catalog keys: the effective view shows [SECRET_MASK]
     * instead of any present value — for ALL principals, SYS included (the source label stays
     * truthful). Only the view is masked: the typed getters / [RuntimePropertyLookup] ladder
     * hand in-process consumers the real value, and non-catalog rows are never masked.
     */
    private fun masked(view: SystemPropertyView): SystemPropertyView {
        val def = catalog.byKey(view.key) ?: return view
        return if (def.secret && view.value != null) view.copy(value = SECRET_MASK) else view
    }

    /** A stored row rendered as a view (source = which scope the row itself lives in). */
    fun viewOf(row: SystemProperty): SystemPropertyView {
        val def = catalog.byKey(row.propertyKey)
        return SystemPropertyView(
            key = row.propertyKey,
            context = row.propertyContext,
            clientId = row.clientId,
            value = row.propertyValue,
            source = if (row.clientId == 0L) PropertySource.SYSTEM else PropertySource.CLIENT,
            type = def?.type?.name,
            group = def?.group ?: row.propertyGroup,
            description = row.description ?: def?.description,
            defaultValue = def?.defaultValue,
            secret = def?.secret ?: false,
            ownerWritable = def?.ownerWritable ?: true,
        )
    }

    private fun resolveView(def: PropertyDefinition, clientId: Long): SystemPropertyView {
        val (value, source) = resolveWithSource(def.key, clientId)
        return SystemPropertyView(
            key = def.key,
            context = null,
            clientId = clientId,
            value = if (source == PropertySource.DEFAULT) def.defaultValue else value,
            source = source,
            type = def.type.name,
            group = def.group,
            description = def.description,
            defaultValue = def.defaultValue,
            secret = def.secret,
            ownerWritable = def.ownerWritable,
        )
    }

    private fun resolveWithSource(key: String, clientId: Long): Pair<String?, PropertySource> {
        repository.findValue(clientId, key, null)?.let {
            return it to if (clientId == 0L) PropertySource.SYSTEM else PropertySource.CLIENT
        }
        if (clientId != 0L) {
            repository.findValue(0L, key, null)?.let { return it to PropertySource.SYSTEM }
        }
        ConfigProvider.getConfig().getOptionalValue(key, String::class.java).orElse(null)?.let {
            return it to PropertySource.CONFIG
        }
        return null to PropertySource.DEFAULT
    }

    private fun validate(key: String, value: String) {
        val def = catalog.byKey(key) ?: return
        val valid = when (def.type) {
            PropertyType.STRING -> true
            PropertyType.BOOLEAN -> value.lowercase() in BOOLEAN_LITERALS
            PropertyType.INTEGER -> value.trim().toIntOrNull() != null
        }
        require(valid) { "Value '$value' is not a valid ${def.type} for catalog key '$key'" }
    }

    companion object {
        /** The literal shown in the effective view wherever a secret catalog key has a value. */
        const val SECRET_MASK = "••••••"

        private val BOOLEAN_LITERALS = setOf("true", "false")
    }
}
