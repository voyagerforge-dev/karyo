package com.karyo.layout.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class LocationResponse(
    val id: Long,
    val name: String,
    val scanCode: String?,
    val locationType: LocationTypeResponse,
    val area: AreaResponse,
    val zone: ZoneResponse?,
    val locationCluster: LocationClusterResponse?,
    val allocation: BigDecimal,
    val lockType: Int,
    val lockTypeName: String,
    val orderIndex: Int,
    // Jackson's legacy bean-getter name mangling lowercases BOTH leading capitals of a
    // Kotlin-compiled getXPos()/getYPos()/getZPos() (any run of leading uppercase letters is
    // lowercased, not just the first), serializing these as "xpos"/"ypos"/"zpos" on the wire
    // instead of "xPos"/"yPos"/"zPos" -- discovered live via pact provider verification against a
    // real broker (2026-07-31): the raw HTTP JSON body from a running instance showed the lowercase
    // keys, which silently breaks location-form.tsx's edit prefill (reads undefined). @get:JsonProperty
    // pins the exact wire name so serialization matches the documented camelCase API convention.
    @get:JsonProperty("xPos") val xPos: Int,
    @get:JsonProperty("yPos") val yPos: Int,
    @get:JsonProperty("zPos") val zPos: Int,
    val rack: String?,
    val field: String?,
    val section: String?,
    val created: String,
    val modified: String,
    val capacity: Int?,
    val temperatureZone: String?,
    val handlingClass: String?,
    val kind: String?,
    val lastCountedAt: String?,
    val isClearing: Boolean,
    val plcCode: String?,
    val allocationState: Int,
)
