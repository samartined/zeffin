// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The private network's boundary (docs/architecture.md, "The private network's boundary"), read from the main
 * sources of the three modules, resources and manifests included:
 * - no OkHttpClient outside core's ServerHttp, so every request to the server goes only through the tunnel;
 * - the private network is named only in core's TailscaleNetwork: its ranges and its name (the words take it from there);
 *   CoreGraph only chooses it, by the class's name;
 * - only ServerHttp decides what TLS trusts (the pinned key, read by nothing else), the app allows no
 *   cleartext, and only PairingQr reads the pairing payload.
 */
class ServerBoundaryTest {
    private val modules = listOf(File("src/main"), File("../core/src/main"), File("../relay/src/main"))
    private val serverHttp = "dev/zeffin/core/ServerHttp.kt"
    private val network = "dev/zeffin/core/TailscaleNetwork.kt"
    private val graph = "dev/zeffin/core/CoreGraph.kt"
    private val pairing = "dev/zeffin/core/Pairing.kt"

    private fun sources() = modules.flatMap { m -> m.walk().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList() }

    private fun path(f: File) = f.invariantSeparatorsPath

    @Test
    fun `the sources are there`() {
        assertTrue(sources().any { path(it).endsWith(serverHttp) })
        assertTrue(sources().any { path(it).endsWith(network) })
    }

    @Test
    fun `only ServerHttp holds an OkHttpClient`() {
        val found = sources().filter { !path(it).endsWith(serverHttp) && it.readText().contains("OkHttpClient") }.map(::path)
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `only TailscaleNetwork names the private network`() {
        val literals = listOf(Regex("tailscale", RegexOption.IGNORE_CASE), Regex("tailnet", RegexOption.IGNORE_CASE), Regex("""100\.64\b"""),
            Regex("fd7a:115c", RegexOption.IGNORE_CASE))
        val found = sources().filter { !path(it).endsWith(network) }.flatMap { f ->
            val text = f.readText().let { if (path(f).endsWith(graph)) it.replace(Regex("""\bTailscaleNetwork\b"""), "") else it }
            literals.filter { it.containsMatchIn(text) }.map { "${path(f)}: ${it.pattern}" }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `only ServerHttp decides what TLS trusts`() {
        val apis = listOf("TrustManager", "SSLContext", "SSLSocketFactory", "sslSocketFactory", "HostnameVerifier", "hostnameVerifier",
            "CertificatePinner", "certificatePinner", "X509Certificate", "networkSecurityConfig", "ConnectionSpec")
        val found = sources().filter { !path(it).endsWith(serverHttp) }.flatMap { f ->
            val text = f.readText()
            apis.filter { text.contains(it) }.map { "${path(f)}: $it" }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `the app allows no cleartext`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:usesCleartextTraffic="false""""))
        val found = sources().filter { it.readText().contains("""usesCleartextTraffic="true"""") || it.readText().contains("cleartextTrafficPermitted") }
        assertEquals(emptyList<File>(), found)
    }

    @Test
    fun `only PairingQr reads the pairing payload`() {
        val found = sources().filter { !path(it).endsWith(pairing) && Regex("""\["fb"]|"fb"\s*[:)]""").containsMatchIn(it.readText()) }.map(::path)
        assertEquals(emptyList<String>(), found)
    }
}
