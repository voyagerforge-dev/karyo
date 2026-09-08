package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LicenseEditionTest {
    @Test
    fun `a build with no installed engine is the free community edition`() {
        assertEquals("community", LicenseEdition.of(emptySet()))
    }

    @Test
    fun `a build carrying an installed engine is the commercial edition`() {
        assertEquals("commercial", LicenseEdition.of(setOf("monitors")))
    }
}
