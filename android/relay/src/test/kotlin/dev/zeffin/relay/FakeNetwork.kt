// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.IpPrefix
import dev.zeffin.core.NoTunnel
import dev.zeffin.core.PrivateNetwork
import dev.zeffin.core.Route
import dev.zeffin.core.ServerAuth
import dev.zeffin.core.ServerHttp
import kotlinx.coroutines.flow.MutableSharedFlow
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A private network for the tests on the JVM, up or down; test sources only (production builds TailscaleNetwork). */
class FakeNetwork(@Volatile var down: NoTunnel.Why? = null) : PrivateNetwork {
    override val name = "Test network"
    override val ranges = listOf(IpPrefix.parse("127.0.0.0/8"))
    override val changes = MutableSharedFlow<Unit>()
    override fun route(host: String) = down?.let { Route.Down(it) } ?: Route.Through { }
}

/** The tests' TLS server: a self-signed P-256 certificate for 127.0.0.1, made once, and its pin. Test sources only. */
object TestTls {
    val held: HeldCertificate = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("127.0.0.1").build()
    val serverCerts: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
    val key = ServerAuth.PinnedKey.of(held.keyPair.public.encoded)
    /** Another server's key. */
    val other = ServerAuth.PinnedKey.of(HeldCertificate.Builder().ecdsa256().build().keyPair.public.encoded)
}

/** Serves https and wss with the tests' certificate. */
fun MockWebServer.tls() = apply { useHttps(TestTls.serverCerts.sslSocketFactory()) }

/**
 * ServerHttp over a [FakeNetwork] that accepts the loopback the tests' servers listen on, and pins [key] (the tests'
 * own certificate's by default) for every server.
 */
fun testHttp(net: PrivateNetwork = FakeNetwork(), relayPingMs: Long = 20_000L, key: ServerAuth? = TestTls.key) =
    ServerHttp(net, { key }, relayPingMs = relayPingMs)
