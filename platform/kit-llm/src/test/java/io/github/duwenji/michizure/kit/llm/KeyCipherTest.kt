package io.github.duwenji.michizure.kit.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** Names the phone has already stored (RFC-002 R-8): renaming them loses every saved API key. */
class KeyCipherTest {
    @Test
    fun `api keys stay in the same Keystore entry`() {
        assertEquals("sanpo_api_keys", KeyCipher.ALIAS)
    }
}
