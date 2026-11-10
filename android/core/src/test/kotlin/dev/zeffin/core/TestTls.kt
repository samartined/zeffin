// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * The tests' TLS server: a self-signed P-256 certificate for 127.0.0.1, made once, and the pin the pairing QR code
 * would carry for it. Test sources only.
 */
object TestTls {
    val held: HeldCertificate = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("127.0.0.1").build()
    val key = ServerAuth.PinnedKey.of(held.keyPair.public.encoded)
    /** Another server's key. */
    val other = ServerAuth.PinnedKey.of(HeldCertificate.Builder().ecdsa256().build().keyPair.public.encoded)
}

/** Serves https with [cert] (the tests' own by default). */
fun MockWebServer.tls(cert: HeldCertificate = TestTls.held) = apply {
    useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
}

/** ServerHttp over [net] that pins [key] for every server. */
fun testHttp(net: PrivateNetwork = FakeNetwork(), key: ServerAuth? = TestTls.key) = ServerHttp(net, { key })
