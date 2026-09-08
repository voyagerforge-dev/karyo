package com.karyo.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.karyo.common.patch.Patchable
import com.karyo.common.patch.PatchableModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-Jackson round-trip test for [Patchable] (D2) -- no Quarkus, no HTTP. Fast feedback
 * loop for the deserializer itself, run directly via
 * `./gradlew :services:karyo-app:test --tests "com.karyo.common.PatchableTest"`.
 *
 * Two [ObjectMapper]s are exercised: [plainMapper] (bare `KotlinModule`) and [nullSafeMapper]
 * (`KotlinModule` with `NullIsSameAsDefault` enabled, matching how the app's other per-module
 * `JacksonConfig`s register the Kotlin module on the SAME shared production ObjectMapper).
 * Both must produce identical tri-state results -- see [PatchableDeserializer]'s KDoc for why
 * that flag doesn't interfere with an explicit `null`.
 */
class PatchableTest {

    data class Probe(
        val a: Patchable<String> = Patchable.Absent,
        val b: Patchable<Long> = Patchable.Absent,
    )

    private fun mapper(nullIsSameAsDefault: Boolean): ObjectMapper {
        val kotlinModule = if (nullIsSameAsDefault) {
            KotlinModule.Builder().enable(KotlinFeature.NullIsSameAsDefault).build()
        } else {
            KotlinModule.Builder().build()
        }
        return ObjectMapper()
            .registerModule(kotlinModule)
            .registerModule(PatchableModule())
    }

    private val plainMapper = mapper(nullIsSameAsDefault = false)
    private val nullSafeMapper = mapper(nullIsSameAsDefault = true)

    @Test
    fun `absent field deserializes to Absent`() {
        val probe = plainMapper.readValue("""{}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Absent)
        assertThat(probe.b).isEqualTo(Patchable.Absent)
    }

    @Test
    fun `explicit null deserializes to Null`() {
        val probe = plainMapper.readValue("""{"a":null}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Null)
        assertThat(probe.b).isEqualTo(Patchable.Absent)
    }

    @Test
    fun `value deserializes to Value`() {
        val probe = plainMapper.readValue("""{"a":"x","b":7}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Value("x"))
        assertThat(probe.b).isEqualTo(Patchable.Value(7L))
    }

    @Test
    fun `all three states can appear together`() {
        val probe = plainMapper.readValue("""{"a":null}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Null)
        assertThat(probe.b).isEqualTo(Patchable.Absent)
    }

    // ── Same trio again with NullIsSameAsDefault enabled (production topology) ──────────

    @Test
    fun `absent field deserializes to Absent even with NullIsSameAsDefault enabled`() {
        val probe = nullSafeMapper.readValue("""{}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Absent)
    }

    @Test
    fun `explicit null still deserializes to Null with NullIsSameAsDefault enabled`() {
        val probe = nullSafeMapper.readValue("""{"a":null}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Null)
    }

    @Test
    fun `value still deserializes to Value with NullIsSameAsDefault enabled`() {
        val probe = nullSafeMapper.readValue("""{"a":"x","b":7}""", Probe::class.java)

        assertThat(probe.a).isEqualTo(Patchable.Value("x"))
        assertThat(probe.b).isEqualTo(Patchable.Value(7L))
    }
}
