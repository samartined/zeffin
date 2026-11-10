// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which VPN route reaches the server: only one inside the private ranges (docs/architecture.md, "The private network's
 * boundary").
 */
class PrivateNetworkTest {
    private val ranges = TailscaleNetwork.ranges
    private val server = literalAddress("100.98.12.7")!!
    private fun p(vararg s: String) = s.map(IpPrefix::parse)

    @Test fun `a full-tunnel VPN's default route never counts`() {
        assertEquals(null to NoTunnel.Why.NOT_COVERED, chooseRoute(server, ranges, mapOf("vpn" to p("0.0.0.0/0", "::/0"))))
        assertEquals(null to NoTunnel.Why.NOT_COVERED, chooseRoute(server, ranges, mapOf("vpn" to p("100.0.0.0/8"))))
    }

    @Test fun `the whole range or a peer's own route counts`() {
        assertEquals("vpn" to null, chooseRoute(server, ranges, mapOf("vpn" to p("100.64.0.0/10"))))
        assertEquals("vpn" to null, chooseRoute(server, ranges, mapOf("vpn" to p("0.0.0.0/0", "100.98.12.7/32"))))
    }

    @Test fun `the longest prefix wins among several VPNs`() {
        val vpns = mapOf("wide" to p("100.64.0.0/10"), "peer" to p("100.98.12.7/32"), "full" to p("0.0.0.0/0"))
        assertEquals("peer" to null, chooseRoute(server, ranges, vpns))
    }

    @Test fun `IPv6 the same way`() {
        val v6 = literalAddress("[fd7a:115c:a1e0::1]")!!
        assertEquals("vpn" to null, chooseRoute(v6, ranges, mapOf("vpn" to p("fd7a:115c:a1e0::/48"))))
        assertEquals(null to NoTunnel.Why.NOT_COVERED, chooseRoute(v6, ranges, mapOf("vpn" to p("::/0", "100.64.0.0/10"))))
    }

    @Test fun `no VPN, a route elsewhere, or an address outside the ranges`() {
        assertEquals(null to NoTunnel.Why.NO_VPN, chooseRoute(server, ranges, emptyMap<String, List<IpPrefix>>()))
        assertEquals(null to NoTunnel.Why.NOT_COVERED, chooseRoute(server, ranges, mapOf("vpn" to p("100.100.0.0/16"))))
        assertEquals(null to NoTunnel.Why.NOT_PRIVATE,
            chooseRoute(literalAddress("192.168.1.2")!!, ranges, mapOf("vpn" to p("0.0.0.0/0"))))
    }

    @Test fun `only literal addresses, never a name`() {
        assertNull(literalAddress("my-server"))
        assertNull(literalAddress("100.64.1.1.attacker.example"))
        assertNull(literalAddress("fe80::1%wlan0"))
        assertNull(literalAddress("g::1"))
        assertNull(literalAddress("256.1.1.1"))
        assertTrue(TailscaleNetwork.isPrivate("[fd7a:115c:a1e0::1]"))
        assertFalse(TailscaleNetwork.isPrivate("my-server"))
    }

    @Test fun `a prefix lies within another`() {
        assertTrue(IpPrefix.parse("100.98.12.7/32").within(IpPrefix.parse("100.64.0.0/10")))
        assertFalse(IpPrefix.parse("100.0.0.0/8").within(IpPrefix.parse("100.64.0.0/10")))
        assertFalse(IpPrefix.parse("100.128.0.0/16").within(IpPrefix.parse("100.64.0.0/10")))
    }
}
