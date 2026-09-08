package com.karyo.orders.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.karyo.common.patch.Patchable
import com.karyo.common.patch.PatchableSize
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * Pre-release header edits only. Orders are editable while in CREATED state;
 * once released the header (and lines) are frozen — the service rejects the
 * update with 409. Line edits are not supported in v1.2 (cancel + recreate).
 *
 * `notes`/`pickingHint`/`packingHint`/`shippingHint`/`externalNumber` are [Patchable] (D2,
 * 2026-07-25): absent = leave unchanged, explicit JSON `null` = clear, a value = set. Length
 * limits on the `Patchable<String>` fields are enforced via [PatchableSize] (review-fix round,
 * 2026-07-25) rather than the standard `@Size` -- Hibernate Validator does not auto-discover
 * a validator for an existing built-in constraint against a new type (confirmed: `@field:Size`
 * on a `Patchable<String>` throws `UnexpectedTypeException`/`HV000030` at request time);
 * [PatchableSize] is our own annotation with its own `@Constraint(validatedBy = ...)`, which
 * IS always discovered. Limits match the original `@Size` values from before D2 (git history:
 * `notes` 2000, `pickingHint`/`packingHint`/`shippingHint` 500, `externalNumber` 100).
 *
 * `destinationLocationId`/`senderName` join the tri-state fields (:1457, 2026-08-17): absent =
 * leave unchanged, explicit JSON `null` = clear, a value = set (and, for `destinationLocationId`,
 * revalidate via [com.karyo.orders.service.DestinationLocationResolver] -- `null` is always
 * valid, so the clear path bypasses validation naturally). `orderStrategyId` stays plain
 * nullable + `?.let` merge -- it does not adopt `Patchable` in this round.
 */
data class UpdateDeliveryOrderRequest(
    @field:Size(max = 255) val customerName: String? = null,
    @field:Size(max = 255) val street: String? = null,
    @field:Size(max = 40) val streetNumber: String? = null,
    @field:Size(max = 40) val zipCode: String? = null,
    @field:Size(max = 120) val city: String? = null,
    @field:Size(max = 80) val country: String? = null,
    @field:Size(max = 60) val phone: String? = null,
    @field:Size(max = 120) val email: String? = null,
    @field:PatchableSize(max = 100)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val externalNumber: Patchable<String> = Patchable.Absent,
    val deliveryDate: LocalDate? = null,
    val prio: Int? = null,
    @field:PatchableSize(max = 2000)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val notes: Patchable<String> = Patchable.Absent,
    @field:PatchableSize(max = 500)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val pickingHint: Patchable<String> = Patchable.Absent,
    @field:PatchableSize(max = 500)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val packingHint: Patchable<String> = Patchable.Absent,
    @field:PatchableSize(max = 500)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val shippingHint: Patchable<String> = Patchable.Absent,
    val orderStrategyId: Long? = null,
    /** Row 10: which StorageLocation inside this warehouse the order's work is bound for; validated via StorageLocationLookup. */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val destinationLocationId: Patchable<Long> = Patchable.Absent,
    /** Row 10: the party named as sender on this order's outbound paperwork. */
    @field:PatchableSize(max = 255)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val senderName: Patchable<String> = Patchable.Absent,
)
