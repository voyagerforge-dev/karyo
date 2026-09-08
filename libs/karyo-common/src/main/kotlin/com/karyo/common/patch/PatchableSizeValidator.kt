package com.karyo.common.patch

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

/**
 * Validator for [PatchableSize] -- see that annotation's KDoc for why a custom annotation
 * exists instead of reusing `jakarta.validation.constraints.Size` directly on
 * `Patchable<String>`.
 *
 * [Patchable.Absent] and [Patchable.Null] are always valid: neither has a length to measure
 * (an omitted field can't overflow a column; clearing a field is always length-legal). Only
 * [Patchable.Value] is checked, against [min]/[max] inclusive, matching `@Size` semantics.
 */
class PatchableSizeValidator : ConstraintValidator<PatchableSize, Patchable<String>> {
    private var min: Int = 0
    private var max: Int = Int.MAX_VALUE

    override fun initialize(constraintAnnotation: PatchableSize) {
        min = constraintAnnotation.min
        max = constraintAnnotation.max
    }

    override fun isValid(value: Patchable<String>?, context: ConstraintValidatorContext): Boolean =
        when (value) {
            null, is Patchable.Absent, is Patchable.Null -> true
            is Patchable.Value -> value.value.length in min..max
        }
}
