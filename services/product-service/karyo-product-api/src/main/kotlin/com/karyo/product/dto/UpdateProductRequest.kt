package com.karyo.product.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.karyo.common.patch.Patchable
import com.karyo.common.patch.PatchableSize
import jakarta.validation.constraints.*
import java.math.BigDecimal

/**
 * `description`/`defaultPackagingUnitId` are [Patchable] (D2, 2026-07-25): absent = leave
 * unchanged, explicit JSON `null` = clear, a value = set. The remaining fields stay plain
 * nullable + `?.let` merge and adopt `Patchable` on next touch (see the WORKLIST decision).
 * The 2000-char limit on `description` is enforced via [PatchableSize] (review-fix round,
 * 2026-07-25) rather than the standard `@Size` -- Hibernate Validator does not auto-discover
 * a validator for an existing built-in constraint against a new type (confirmed: `@field:Size`
 * on a `Patchable<String>` throws `UnexpectedTypeException`/`HV000030` at request time);
 * [PatchableSize] is our own annotation with its own `@Constraint(validatedBy = ...)`, which
 * IS always discovered. `defaultPackagingUnitId` is a `Patchable<Long>` -- it never carried
 * `@Size` (only string-length fields do), so nothing to restore there.
 */
data class UpdateProductRequest(
    @field:Size(max = 255) val name: String? = null,
    @field:PatchableSize(max = 2000)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val description: Patchable<String> = Patchable.Absent,
    val state: Int? = null,
    val weight: BigDecimal? = null,
    val height: BigDecimal? = null,
    val width: BigDecimal? = null,
    val depth: BigDecimal? = null,
    val lotMandatory: Boolean? = null,
    val bestBeforeMandatory: Boolean? = null,
    @field:Min(0) val shelflife: Int? = null,
    val serialNoRecordType: String? = null,
    val defaultUnitLoadTypeId: Long? = null,
    val defaultStorageStrategyId: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val defaultPackagingUnitId: Patchable<Long> = Patchable.Absent,
    val zoneId: Long? = null,
    @field:Size(max = 100) val tradeGroup: String? = null,
    @field:Size(max = 500) val imageUrl: String? = null,
)
