package com.karyo.app.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.common.patch.PatchableModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/**
 * Registers [PatchableModule] on the app-wide ObjectMapper (D2) -- the first
 * `ObjectMapperCustomizer` to live in `karyo-app` itself rather than a domain-module core;
 * each domain module core also registers its own `JacksonConfig` (auth/inventory/layout/
 * product) for the Kotlin module, and Quarkus applies every discovered
 * `ObjectMapperCustomizer` bean to the SAME shared `ObjectMapper` instance, so registration
 * order across modules doesn't matter here.
 */
@Singleton
class JacksonConfig : ObjectMapperCustomizer {
    override fun customize(objectMapper: ObjectMapper) {
        objectMapper.registerModule(PatchableModule())
    }
}
