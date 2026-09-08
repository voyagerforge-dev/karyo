package com.karyo.slotting

import com.karyo.slotting.spi.SkuSlot
import com.karyo.slotting.spi.SkuVelocity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlottingContractTest {
    @Test
    fun `value types construct`() {
        assertEquals("SKU-A", SkuVelocity("SKU-A", 12).sku)
        assertEquals(5, SkuSlot("SKU-A", "L-1", 5, "Z1").orderIndex)
    }
}
