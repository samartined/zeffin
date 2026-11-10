// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressesTest {
    @Test fun acceptsOnlyTlsToAPrivateAddress() {
        val base = Addresses.parseBase("https://100.98.12.7:9434")
        assertEquals("https://100.98.12.7:9434", Addresses.display(base))
        assertEquals("wss://100.98.12.7:9434/phone", Addresses.phoneUrl(base))
        assertEquals("wss://100.98.12.7:9434/app", Addresses.appUrl(base))
        assertEquals("https://100.98.12.7:9434/phone/check", Addresses.checkUrl(base).toString())
        assertEquals(base, Addresses.parseBase("wss://100.98.12.7:9434/phone"))
        assertEquals(base, Addresses.parseBase(" wss://100.98.12.7:9434/app "))
    }

    @Test fun ipv6GetsSingleBrackets() {
        val base = Addresses.parseBase("wss://[fd7a:115c:a1e0::1]:9434/phone")
        assertEquals("wss://[fd7a:115c:a1e0::1]:9434/phone", Addresses.phoneUrl(base))
        assertEquals("[fd7a:115c:a1e0::1]:9434", Addresses.hostPort(base))
    }

    @Test fun refusesCleartextNamesOtherAddressesAndEmbeddedCredentials() {
        val bad = mapOf(
            "http://100.98.12.7:9434" to Addresses.Problem.CLEARTEXT,
            "ws://100.98.12.7:9434/phone" to Addresses.Problem.CLEARTEXT,
            "wss://my-server.example.ts.net/phone" to Addresses.Problem.NOT_PRIVATE,
            "https://my-server:9434" to Addresses.Problem.NOT_PRIVATE,
            "wss://192.168.1.5:9434/phone" to Addresses.Problem.NOT_PRIVATE,
            "wss://100.128.0.1/phone" to Addresses.Problem.NOT_PRIVATE,
            "wss://100.63.0.1/phone" to Addresses.Problem.NOT_PRIVATE,
            "wss://100.64.1.1.attacker.example/phone" to Addresses.Problem.NOT_PRIVATE,
            "https://[fd00::1]:9434" to Addresses.Problem.NOT_PRIVATE,
            "wss://token@100.64.1.1/phone" to Addresses.Problem.CREDENTIALS,
            "wss://100.64.1.1/phone?token=abc" to Addresses.Problem.QUERY,
            "wss://100.64.1.1/wrong" to Addresses.Problem.OTHER_PATH,
            "100.64.0.1:9434" to Addresses.Problem.NO_SCHEME,
        )
        for ((s, problem) in bad) {
            assertEquals(s, problem, assertThrows(s, Addresses.Invalid::class.java) { Addresses.parseBase(s) }.problem)
        }
        for (s in listOf("wss://100.64.300.1/phone", "ftp://100.64.0.1/")) {
            assertThrows(s, IllegalArgumentException::class.java) { Addresses.parseBase(s) }
        }
    }

    @Test fun tailnetRanges() {
        assertTrue(TailscaleNetwork.isPrivate("100.64.0.0"))
        assertTrue(TailscaleNetwork.isPrivate("100.127.255.255"))
        assertFalse(TailscaleNetwork.isPrivate("100.128.0.0"))
        assertTrue(TailscaleNetwork.isPrivate("fd7a:115c:a1e0:ab12::1"))
        assertFalse(TailscaleNetwork.isPrivate("fd7a:115c:a1e1::1"))
    }
}
