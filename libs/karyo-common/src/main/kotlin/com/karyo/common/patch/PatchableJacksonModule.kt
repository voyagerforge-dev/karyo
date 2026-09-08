package com.karyo.common.patch

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.ContextualSerializer

/**
 * Jackson deserializer for [Patchable]. The house's first custom deserializer (D2).
 *
 * Contextual: on first lookup Jackson hands us the raw `Patchable::class` binding with no
 * type parameter, so [createContextual] resolves the actual `T` from the enclosing property's
 * generic type (e.g. `Patchable<String>` -> `String`) and looks up (and caches) a delegate
 * deserializer for it, mirroring the classic "generic wrapper type" Jackson pattern.
 *
 * The tri-state hinges on which deserializer method Jackson calls for a given property:
 *  - Property absent from JSON entirely -> neither method below runs; the Kotlin data-class
 *    constructor falls back to the property's default (`Patchable.Absent`) via its own
 *    call-with-defaults machinery (see [Patchable] KDoc). Nothing to do here.
 *  - Property present as JSON `null` -> Jackson calls [getNullValue], which returns
 *    [Patchable.Null] -- a real, non-null object instance. Because the substituted value is
 *    non-null, `jackson-module-kotlin`'s `NullIsSameAsDefault` feature (enabled elsewhere in
 *    this app's other per-module `JacksonConfig`s, on the SAME shared ObjectMapper) never
 *    engages -- that feature only intercepts when the *deserializer's* null substitution is
 *    itself JVM `null`. Pinned by `PatchableTest`.
 *  - Property present with a value -> Jackson calls [deserialize], which delegates to the
 *    resolved value deserializer and wraps the result in [Patchable.Value].
 */
class PatchableDeserializer(
    private val valueDeserializer: JsonDeserializer<*>? = null,
    private val valueType: JavaType? = null,
) : JsonDeserializer<Patchable<*>>(), ContextualDeserializer {

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): JsonDeserializer<*> {
        val wrapperType = property?.type ?: ctxt.contextualType ?: return this
        val contained = wrapperType.containedType(0) ?: return this
        val delegate = ctxt.findContextualValueDeserializer(contained, property)
        return PatchableDeserializer(delegate, contained)
    }

    override fun getNullValue(ctxt: DeserializationContext): Patchable<*> = Patchable.Null

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Patchable<*> {
        val type = valueType ?: ctxt.constructType(Any::class.java)
        val deser = valueDeserializer
        val value: Any = if (deser != null) {
            deser.deserialize(p, ctxt) as Any
        } else {
            ctxt.readValue(p, type)
        }
        return Patchable.Value(value)
    }
}

/**
 * Jackson serializer for [Patchable] -- the mirror of [PatchableDeserializer], needed so a
 * `Patchable<T>`-bearing DTO round-trips through Jackson in EITHER direction (tests and any
 * future caller that builds one of these DTOs as a Kotlin object and lets Jackson serialize
 * it, rather than hand-writing JSON, hit this path).
 *
 * [isEmpty] returning `true` for [Patchable.Absent] is what makes the property vanish from
 * the output entirely under `@get:JsonInclude(JsonInclude.Include.NON_EMPTY)` (the annotation
 * every adopting DTO property carries) -- Jackson only calls [serialize] at all for a
 * non-empty value, so [Patchable.Absent] never needs (and doesn't get) a wire representation.
 * [Patchable.Null] serializes as a literal JSON `null`; [Patchable.Value] delegates to the
 * resolved serializer for `T`.
 */
class PatchableSerializer(
    private val valueSerializer: JsonSerializer<Any>? = null,
) : JsonSerializer<Patchable<*>>(), ContextualSerializer {

    override fun createContextual(prov: SerializerProvider, property: BeanProperty?): JsonSerializer<*> {
        val wrapperType = property?.type ?: return this
        val contained = wrapperType.containedType(0) ?: return this
        @Suppress("UNCHECKED_CAST")
        val delegate = prov.findValueSerializer(contained, property) as JsonSerializer<Any>
        return PatchableSerializer(delegate)
    }

    override fun serialize(value: Patchable<*>, gen: JsonGenerator, serializers: SerializerProvider) {
        when (value) {
            is Patchable.Absent -> gen.writeNull() // Only reached without NON_EMPTY; still valid JSON.
            is Patchable.Null -> gen.writeNull()
            is Patchable.Value -> {
                val v = value.value
                val ser = valueSerializer
                if (ser != null) ser.serialize(v, gen, serializers) else serializers.defaultSerializeValue(v, gen)
            }
        }
    }

    override fun isEmpty(provider: SerializerProvider, value: Patchable<*>): Boolean = value is Patchable.Absent
}

/** Registers [PatchableDeserializer]/[PatchableSerializer] for every `Patchable<T>` property, app-wide. */
class PatchableModule : SimpleModule("PatchableModule") {
    init {
        @Suppress("UNCHECKED_CAST")
        addDeserializer(Patchable::class.java as Class<Patchable<*>>, PatchableDeserializer())
        @Suppress("UNCHECKED_CAST")
        addSerializer(Patchable::class.java as Class<Patchable<*>>, PatchableSerializer())
    }
}
