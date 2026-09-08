package com.karyo.common.patch

/**
 * Tri-state wrapper for PATCH/merge-semantics request DTOs (D2, 2026-07-25).
 *
 * Plain nullable fields (`String? = null`) applied with `?.let { ... }` cannot distinguish
 * "the client didn't send this field" (leave unchanged) from "the client explicitly sent
 * `null`" (clear the value) -- both deserialize to Kotlin `null`. `Patchable<T>` recovers the
 * distinction:
 *  - [Absent] -- the field was not present in the request JSON at all. Deserializes here via
 *    the Kotlin data-class default (`= Patchable.Absent`), NOT via [PatchableDeserializer].
 *  - [Null] -- the field was present with an explicit JSON `null`. Deserializes here via
 *    [PatchableDeserializer.getNullValue].
 *  - [Value] -- the field was present with a value. Deserializes here via
 *    [PatchableDeserializer.deserialize].
 *
 * Adopted by [com.karyo.orders.dto.UpdateDeliveryOrderRequest] (notes/pickingHint/packingHint/
 * shippingHint/externalNumber) and [com.karyo.product.dto.UpdateProductRequest] (description/
 * defaultPackagingUnitId); later DTOs adopt on touch, per the WORKLIST decision.
 */
sealed class Patchable<out T> {

    /** Field absent from the request JSON -- leave the current value unchanged. */
    object Absent : Patchable<Nothing>()

    /** Field present with an explicit JSON `null` -- clear the current value. */
    object Null : Patchable<Nothing>()

    /** Field present with a value -- set the current value to it. */
    data class Value<T>(val value: T) : Patchable<T>()

    /**
     * Applies tri-state semantics without an intermediate `when`: [current] runs when the
     * field was omitted (defaults to a no-op -- i.e. leave the target as-is), [clear] runs on
     * an explicit `null`, [set] runs with the deserialized value.
     */
    inline fun apply(current: () -> Unit = {}, clear: () -> Unit, set: (T) -> Unit) {
        when (this) {
            is Absent -> current()
            is Null -> clear()
            is Value -> set(value)
        }
    }
}
