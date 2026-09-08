package com.karyo.layout.dto

import jakarta.validation.constraints.*
import java.math.BigDecimal

data class CreateLocationTypeRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val height: BigDecimal? = null,
    val width: BigDecimal? = null,
    val depth: BigDecimal? = null,
    val liftingCapacity: BigDecimal? = null,
    /** L4: cap on total weight across every location sharing this type's (area, rack, field)
     * group. Null = unrestricted. Also doubles as the PUT/update body (mirrors the sibling
     * strategy resource's create-doubles-as-update DTO shape). */
    val fieldLiftingCapacity: BigDecimal? = null,
    /** L4: cap on total weight across every location sharing this type's (area, section) group.
     * Null = unrestricted. */
    val sectionLiftingCapacity: BigDecimal? = null,
)
