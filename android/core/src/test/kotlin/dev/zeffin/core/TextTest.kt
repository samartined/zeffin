// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun cleansUntrustedNames() {
        assertEquals("Demo[31m", cleanText("Demo\u001b[31m"))
        assertEquals("x".repeat(64), cleanText("x".repeat(200)))
        assertNull(cleanText("\n\t "))
        assertEquals("ab", cleanText("a‮b"))
    }

    @Test fun masksTokens() {
        assertEquals("•••• 2b7e", maskToken("abcdefgh2b7e"))
        assertEquals("", maskToken(null))
    }

    @Test fun closeReasonsFit() {
        val r = closeReason("ñ".repeat(100))
        assertTrue(r.length <= 123 && r.toByteArray().size <= 123)
        val whole = "Flipper: disconnected: the Flipper stopped answering: out of range or off (status 8)"
        assertEquals(whole, closeReason(whole))  // 84 characters: no longer cut at 64
    }
}
