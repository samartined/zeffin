// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * ServerHttp talks only to the pinned key, over TLS 1.3, only through the tunnel and never through a proxy; anything else
 * fails before the request is written.
 */
class ServerHttpTest {
    private lateinit var server: MockWebServer
    private val token = "a".repeat(43)

    @Before fun setUp() {
        server = MockWebServer().tls().apply { start() }
    }

    @After fun tearDown() = server.close()

    private fun url(path: String = "/app/upload") = server.url(path).newBuilder().host("127.0.0.1").build()
    private fun request(path: String = "/app/upload") = Request.Builder().url(url(path)).header("Authorization", "Bearer $token").build()
    private fun ok() = server.enqueue(MockResponse.Builder().code(201).body("{}").build())
    private fun call(http: ServerHttp, use: ServerHttp.Use = ServerHttp.Use.TRANSFER) =
        runCatching { http.newCall(use, request()).execute().use { it.code } }

    // ---- the pinned key

    @Test fun `the pinned key is trusted, through the tunnel`() {
        val net = FakeNetwork()
        ok()
        assertEquals(201, call(testHttp(net)).getOrThrow())
        assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
        assertEquals(1, net.binds.get())  // the socket was bound to the tunnel's network
    }

    @Test fun `another key fails the handshake before the request is written`() {
        ok()
        val e = call(testHttp(key = TestTls.other)).exceptionOrNull()
        assertTrue(e.toString(), e is ServerKeyChanged)
        assertEquals(0, server.requestCount)
    }

    @Test fun `another key opens no WebSocket`() {
        val failed = CompletableFuture<Throwable>()
        testHttp(key = TestTls.other).newWebSocket(ServerHttp.Use.APP_SOCKET, request("/app"), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed.complete(t)
            }
        })
        assertTrue(failed.get(5, TimeUnit.SECONDS) is ServerKeyChanged)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the pairing's first request is checked against the QR code's key`() {
        ok()
        val pair = Request.Builder().url(url("/app/pair")).build()
        val e = runCatching { testHttp(key = null).newPairCall(pair, TestTls.other).execute() }.exceptionOrNull()
        assertTrue(e.toString(), e is ServerKeyChanged)
        assertEquals(0, server.requestCount)
        testHttp(key = null).newPairCall(pair, TestTls.key).execute().use { assertEquals(201, it.code) }
    }

    @Test fun `only the key counts, not the certificate around it`() {
        val pair = TestTls.held.keyPair
        val certs = mapOf(
            "renewed" to HeldCertificate.Builder().keyPair(pair).addSubjectAlternativeName("127.0.0.1").build(),
            "expired" to HeldCertificate.Builder().keyPair(pair).validityInterval(1_000L, 2_000L).build(),
            "no name" to HeldCertificate.Builder().keyPair(pair).build(),
            "another name" to HeldCertificate.Builder().keyPair(pair).addSubjectAlternativeName("my-server").build(),
            "a CA" to HeldCertificate.Builder().keyPair(pair).certificateAuthority(0).build(),
        )
        for ((what, cert) in certs) {
            MockWebServer().tls(cert).use { s ->
                s.start()
                s.enqueue(MockResponse.Builder().code(201).build())
                val u = s.url("/x").newBuilder().host("127.0.0.1").build()
                testHttp().newCall(ServerHttp.Use.TRANSFER, Request.Builder().url(u).build()).execute().use { assertEquals(what, 201, it.code) }
            }
        }
    }

    @Test fun `a server that offers only TLS 1_2 is refused`() {
        val tls12 = HandshakeCertificates.Builder().heldCertificate(TestTls.held).build().sslSocketFactory().onlyTls12()
        MockWebServer().apply { useHttps(tls12) }.use { s ->
            s.start()
            s.enqueue(MockResponse.Builder().code(201).build())
            val u = s.url("/x").newBuilder().host("127.0.0.1").build()
            val e = runCatching { testHttp().newCall(ServerHttp.Use.TRANSFER, Request.Builder().url(u).build()).execute() }.exceptionOrNull()
            assertTrue(e.toString(), e is SSLException)
            assertEquals(0, s.requestCount)
        }
    }

    @Test fun `a new key is never served by a pooled socket or a resumed session of the old one`() {
        var key: ServerAuth = TestTls.key
        val http = ServerHttp(FakeNetwork(), { key })
        repeat(2) { ok() }
        assertEquals(201, call(http).getOrThrow())
        http.dropConnections()
        assertEquals(201, call(http).getOrThrow())  // the same pin again: a fresh socket, a resumed session, still fine
        key = TestTls.other
        val e = call(http).exceptionOrNull()  // the old pin's socket and session are not this pin's
        assertTrue(e.toString(), e is ServerKeyChanged)
        assertEquals(2, server.requestCount)
    }

    // ---- nothing without the key or in cleartext

    @Test fun `no key for this server, nothing is sent`() {
        val net = FakeNetwork()
        ok()
        val e = call(testHttp(net, key = null)).exceptionOrNull()
        assertTrue(e.toString(), e is NoServerKey)
        assertEquals(0, server.requestCount)
        assertEquals(0, net.routes.get())
    }

    @Test fun `cleartext is refused before any socket`() {
        MockWebServer().use { plain ->
            plain.start()
            plain.enqueue(MockResponse.Builder().code(201).build())
            val net = FakeNetwork()
            val u = plain.url("/app/upload").newBuilder().host("127.0.0.1").build()
            val e = runCatching { testHttp(net).newCall(ServerHttp.Use.TRANSFER, Request.Builder().url(u).header("Authorization", "Bearer $token").build())
                .execute() }.exceptionOrNull()
            assertTrue(e.toString(), e is NoServerKey)
            assertEquals(0, plain.requestCount)
            assertEquals(0, net.routes.get())
        }
    }

    @Test fun `a server that answers in cleartext gets no request, no fallback`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { plain ->
            val got = ByteArrayOutputStream()
            val reader = thread {
                runCatching {
                    plain.accept().use { s ->
                        s.soTimeout = 2_000
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n".toByteArray())
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = s.getInputStream().read(buf)
                            if (n < 0) break
                            got.write(buf, 0, n)
                        }
                    }
                }
            }
            val u = "https://127.0.0.1:${plain.localPort}/app/upload".toHttpUrl()
            val e = runCatching { testHttp().newCall(ServerHttp.Use.TRANSFER, Request.Builder().url(u).header("Authorization", "Bearer $token").build())
                .execute() }.exceptionOrNull()
            reader.join(5_000)
            assertTrue(e.toString(), e is IOException)
            val seen = String(got.toByteArray(), Charsets.ISO_8859_1)
            assertFalse("only a TLS hello reached it", seen.contains("Bearer") || seen.contains("HTTP/1.1"))
        }
    }

    // ---- the tunnel and the proxy

    @Test fun `without the tunnel nothing reaches the server`() {
        val net = FakeNetwork(NoTunnel.Why.NO_VPN)
        val e = call(testHttp(net)).exceptionOrNull()
        assertEquals(NoTunnel.Why.NO_VPN, (e as NoTunnel).why)
        assertEquals(0, server.requestCount)
        assertEquals(0, net.binds.get())
    }

    @Test fun `without the tunnel no WebSocket is opened`() {
        val failed = CompletableFuture<Throwable>()
        testHttp(FakeNetwork(NoTunnel.Why.NOT_COVERED)).newWebSocket(ServerHttp.Use.APP_SOCKET, request("/app"), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed.complete(t)
            }
        })
        assertTrue(failed.get(5, TimeUnit.SECONDS) is NoTunnel)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the socket is refused when the tunnel goes between the check and the connect`() {
        val net = FakeNetwork()
        net.before = { n -> if (n >= 2) net.down = NoTunnel.Why.NO_VPN }  // the interceptor passes, the connect does not
        val e = call(testHttp(net), ServerHttp.Use.RELAY_CHECK).exceptionOrNull()
        assertTrue(e.toString(), e is NoTunnel)
        assertEquals(0, server.requestCount)
        assertEquals(0, net.binds.get())
    }

    @Test fun `TLS asks the private network too`() {
        val net = FakeNetwork(NoTunnel.Why.NO_VPN)
        val pair = Request.Builder().url("https://127.0.0.1:1/app/pair".toHttpUrl()).build()
        val e = runCatching { testHttp(net).newPairCall(pair, TestTls.key).execute() }.exceptionOrNull()
        assertTrue(e.toString(), e is NoTunnel)
        assertEquals(1, net.routes.get())
    }

    @Test fun `never through a proxy`() {
        val proxy = MockWebServer().apply { start() }
        val before = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        try {
            ok()
            proxy.enqueue(MockResponse.Builder().body("{}").build())
            call(testHttp(), ServerHttp.Use.RELAY_CHECK).getOrThrow()
            assertEquals(0, proxy.requestCount)
            assertEquals(1, server.requestCount)
        } finally {
            ProxySelector.setDefault(before)
            proxy.close()
        }
    }

    @Test fun `the pooled sockets go when the tunnel changes`() {
        val net = FakeNetwork()
        val http = testHttp(net)
        repeat(2) { ok() }
        call(http).getOrThrow()
        http.dropConnections()
        call(http).getOrThrow()
        assertEquals(2, net.binds.get())  // a new socket, bound again
    }
}

/** The same server sockets, with only TLS 1.2 enabled. */
private fun SSLSocketFactory.onlyTls12(): SSLSocketFactory = object : SSLSocketFactory() {
    private fun SSLSocket.v12() = apply { enabledProtocols = arrayOf("TLSv1.2") }
    override fun getDefaultCipherSuites(): Array<String> = this@onlyTls12.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = this@onlyTls12.supportedCipherSuites
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean) =
        (this@onlyTls12.createSocket(s, host, port, autoClose) as SSLSocket).v12()
    override fun createSocket(host: String?, port: Int) = (this@onlyTls12.createSocket(host, port) as SSLSocket).v12()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
        (this@onlyTls12.createSocket(host, port, localHost, localPort) as SSLSocket).v12()
    override fun createSocket(host: InetAddress?, port: Int) = (this@onlyTls12.createSocket(host, port) as SSLSocket).v12()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) =
        (this@onlyTls12.createSocket(address, port, localAddress, localPort) as SSLSocket).v12()
}
