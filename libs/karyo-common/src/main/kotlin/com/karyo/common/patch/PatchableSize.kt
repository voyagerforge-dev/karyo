package com.karyo.common.patch

import jakarta.validation.Constraint
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Length constraint for `Patchable<String>` fields (D2 follow-up, review-fix round,
 * 2026-07-25).
 *
 * The standard `jakarta.validation.constraints.Size` cannot be applied directly to a
 * `Patchable<String>` field: Hibernate Validator only resolves a `@Size` validator for the
 * type(s) it has built in (`CharSequence`/`Collection`/`Map`/array) or for extra types
 * registered ahead of time via XML/`ConstraintMapping`/`ConstraintDefinitionContributor` --
 * a plain CDI-visible `ConstraintValidator<Size, Patchable<String>>` bean is NOT
 * auto-discovered for an existing built-in constraint (confirmed with a probe: Quarkus/
 * Hibernate Validator throws `UnexpectedTypeException`/`HV000030` at request time). A custom
 * annotation sidesteps that entirely -- `@Constraint(validatedBy = [...])` on OUR OWN
 * annotation is the standard, always-discovered wiring, no CDI/ServiceLoader tricks needed.
 *
 * [Patchable.Absent]/[Patchable.Null] are always valid: there is nothing to measure (a field
 * that isn't there, or is being cleared, can never violate a length limit). Only
 * [Patchable.Value] is measured against [min]/[max], mirroring `@Size`'s own semantics.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PatchableSizeValidator::class])
annotation class PatchableSize(
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val message: String = "size must be between {min} and {max}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
