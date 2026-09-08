package com.karyo.fulfillment

import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.fulfillment.spi.WavePickRequest
import com.karyo.fulfillment.spi.WavePickResult
import com.karyo.security.TenantContext
import io.restassured.RestAssured.given

/**
 * Builds batch-pick fixtures without the commercial wave REST resource.
 *
 * Reservations come from the Apache-2.0 delivery-order release endpoint. Batch generation then
 * uses [BatchPickPort], the public fulfillment SPI implemented by fulfillment-core. Tests of the
 * free bulk-pick endpoints must use this path so a public snapshot never needs the paid wave route.
 */
class FreeTierBatchPickFixture(
    private val batchPickPort: BatchPickPort,
    private val tenantContext: TenantContext,
) {
    fun generate(
        deliveryOrderIds: List<Long>,
        clientId: Long,
        pickMode: String,
    ): GeneratedBatchPick {
        deliveryOrderIds.forEach { orderId ->
            given().`when`().post("/api/v1/delivery-orders/$orderId/release")
                .then().statusCode(200)
        }
        tenantContext.clientId = clientId
        val waveId = System.nanoTime()
        val result = batchPickPort.generateForWave(
            WavePickRequest(waveId, clientId, pickMode, deliveryOrderIds),
        )
        return GeneratedBatchPick(waveId, result)
    }
}

data class GeneratedBatchPick(
    val waveId: Long,
    val result: WavePickResult,
)
