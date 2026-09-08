package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The vendor public key has exactly one declared home, and this side reads it rather than
 * restating it.
 *
 * The key used to be a literal in [VendorKey], with a second literal in another language's
 * verifier and nothing making the two agree; a rotation that touched one and not the other left
 * every re-minted licence looking forged on the side that was missed. Both now load
 * `vendor-public-key.txt`, so the value the loader produces below IS the file's content.
 */
class VendorKeyTest {

    private val keyFile = File(KEY_RESOURCE_PATH)

    @Test
    fun `the bundled key comes from the shared file, not from this module's source`() {
        assertTrue(keyFile.isFile, "missing $KEY_RESOURCE_PATH (working dir ${File(".").absolutePath})")

        assertEquals(keyFile.readText().trim(), VendorKey.PUBLIC_KEY_BASE64)
    }

    @Test
    fun `the file holds the key alone, and surrounding whitespace is not part of it`() {
        val key = "MCowBQYDK2VwAyEAsynthetic0000000000000000000000000000000000="

        assertEquals(key, VendorKey.parse("$key\n"))
        assertEquals(key, VendorKey.parse("  \n\t $key \t\n\n"))
    }

    @Test
    fun `a file with no key in it fails loudly rather than verifying nothing`() {
        assertThrows<IllegalStateException> { VendorKey.parse("") }
        assertThrows<IllegalStateException> { VendorKey.parse("  \n\t\n") }
    }

    @Test
    fun `the bundled key is a usable Ed25519 public key`() {
        val decoded = Base64.getDecoder().decode(VendorKey.PUBLIC_KEY_BASE64)
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(decoded))

        assertNotNull(key)
        assertEquals("EdDSA", key.algorithm)
    }

    @Test
    fun `an absent override resolves to the bundled key and any other value wins`() {
        assertEquals(VendorKey.PUBLIC_KEY_BASE64, VendorKey.resolve(VendorKey.NOT_OVERRIDDEN))
        assertEquals("a-rotated-key", VendorKey.resolve("a-rotated-key"))
        assertFalse(
            VendorKey.PUBLIC_KEY_BASE64 == VendorKey.NOT_OVERRIDDEN,
            "the sentinel must never be mistakeable for the key itself",
        )
    }

    private companion object {
        const val KEY_RESOURCE_PATH =
            "src/main/resources/com/karyo/license/${VendorKey.KEY_RESOURCE}"
    }
}
