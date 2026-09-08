package com.karyo.product.api.v1

import com.karyo.product.dto.ProductResponse
import com.karyo.product.spi.BarcodeResolver
import jakarta.enterprise.context.ApplicationScoped

/**
 * Test-only [BarcodeResolver] exercising the SC18 [BarcodeResolver.structuralError] pre-check.
 * Inert (returns null from [structuralError]) unless a test sets [structuralErrorFor] to the
 * exact barcode under test, so it never affects any other ProductResourceTest case. priority()
 * = 1 sorts it ahead of the built-in DefaultBarcodeResolver (DEFAULT_PRIORITY = MAX_VALUE),
 * matching [TestOverrideResolver][com.karyo.orders.service.TestOverrideResolver]'s convention.
 */
@ApplicationScoped
class TestStructuralErrorResolver : BarcodeResolver {
    override fun priority(): Int = 1

    override fun resolve(barcode: String, clientId: Long): ProductResponse? = null

    override fun structuralError(barcode: String): String? =
        if (barcode == structuralErrorFor) structuralErrorMessage else null

    companion object {
        @JvmStatic
        var structuralErrorFor: String? = null

        @JvmStatic
        var structuralErrorMessage: String = "bad format"
    }
}
