package com.karyo.auth.service

import com.karyo.auth.config.SystemPropertyService
import com.karyo.auth.spi.RuntimePropertyLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process [RuntimePropertyLookup]: a thin delegate onto [SystemPropertyService]'s
 * typed getters (which own the fallback ladder). Unscoped by contract — see the SPI KDoc.
 */
@ApplicationScoped
class DefaultRuntimePropertyLookup(
    private val service: SystemPropertyService,
) : RuntimePropertyLookup {

    override fun getString(key: String, clientId: Long, default: String?): String? =
        service.getString(key, clientId, default)

    override fun getBoolean(key: String, clientId: Long, default: Boolean): Boolean =
        service.getBoolean(key, clientId, default)

    override fun getInt(key: String, clientId: Long, default: Int): Int =
        service.getInt(key, clientId, default)
}
