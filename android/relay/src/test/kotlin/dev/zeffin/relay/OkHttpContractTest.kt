// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.NoTunnel
import dev.zeffin.core.ServerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The relay's /phone and /phone/check against a real HTTPS/WebSocket server, with the pinned key. */
class OkHttpContractTest {
    private lateinit var server: MockWebServer
    private val token = "p".repeat(43)

    @Before fun setUp() {
        server = MockWebServer().tls()
        server.start()
    }

    @After fun tearDown() = server.close()

    /** Server side of a WebSocket that records what it receives. */
    private class Recorder : WebSocketListener() {
        val received = LinkedBlockingQueue<Any>()
        lateinit var ws: WebSocket
        override fun onOpen(webSocket: WebSocket, response: Response) { ws = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { received.put(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received.put(bytes.toByteArray()) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { received.put("closed $code $reason"); webSocket.close(1000, null) }
        fun next(): Any = received.poll(5, TimeUnit.SECONDS) ?: error("nothing received")
    }

    private fun url() = server.url("/phone").newBuilder().host("127.0.0.1").build().toString().replace("https://", "wss://")
    private fun checkUrl() = server.url("/phone/check").newBuilder().host("127.0.0.1").build()

    @Test fun aSessionOverRealWebSockets() = runBlocking {
        val recorder = Recorder()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(recorder).build())
        val dial = OkHttpPhoneDialer(url(), token, testHttp()).dial()
        assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
        val socket = (dial as DialResult.Open).socket
        val link = FakeLink()
        link.received.trySend(byteArrayOf(9, 9))
        val session = async(Dispatchers.Default) { RelaySession(socket, link, "0.1.0").run() }
        val first = recorder.next() as String
        assertTrue(first.startsWith("{\"type\":\"ready\""))
        assertTrue((recorder.next() as ByteArray).contentEquals(byteArrayOf(9, 9)))
        recorder.ws.send(byteArrayOf(1, 2, 3).toByteString())
        assertEquals(PhoneProtocol.WRITTEN, recorder.next())
        assertTrue(link.written.single().contentEquals(byteArrayOf(1, 2, 3)))
        recorder.ws.send("hola")
        val end = withTimeout(5_000) { session.await() }
        assertTrue(end is SessionEnd.ServerText)
        assertEquals("closed 1003 the server sent text", recorder.next()) // close reasons are always English
    }

    @Test fun refusalsKeepTheirHttpCode() = runBlocking {
        for (code in listOf(401, 409, 423)) {
            server.enqueue(MockResponse.Builder().code(code).body("no").build())
            assertEquals(DialResult.Refused(code), OkHttpPhoneDialer(url(), token, testHttp()).dial())
        }
    }

    @Test fun nothingListening() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val r = OkHttpPhoneDialer("wss://127.0.0.1:$port/phone", token, testHttp()).dial()
        assertTrue(r is DialResult.Failed)
    }

    @Test fun aServerThatStopsAnsweringPingsEndsTheSession() = runBlocking {
        val listener = ServerSocket(0)
        thread(isDaemon = true) {
            listener.accept().use { raw ->
                val s = (TestTls.serverCerts.sslSocketFactory().createSocket(raw, null, raw.port, true) as SSLSocket)
                    .apply { useClientMode = false; startHandshake() }
                val input = s.getInputStream().bufferedReader()
                var key = ""
                while (true) {
                    val line = input.readLine() ?: return@thread
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(":").trim()
                    if (line.isEmpty()) break
                }
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                s.getOutputStream().flush()
                Thread.sleep(10_000) // silent: no pong ever
            }
        }
        val socket = (OkHttpPhoneDialer("wss://127.0.0.1:${listener.localPort}/phone", token, testHttp(relayPingMs = 300)).dial()
            as DialResult.Open).socket
        val end = withTimeout(5_000) { RelaySession(socket, FakeLink(), "0.1.0").run() }
        assertTrue(end is SessionEnd.NetworkLost)
        listener.close()
    }

    @Test fun phoneCheck() = runBlocking {
        val checker = OkHttpServerChecker(checkUrl(), token, testHttp())
        server.enqueue(MockResponse.Builder().body(
            """{"server":{"name":"my-server","version":"0.1.0"},"gate":"paused","relay":{"app":"0.1.0","device":"Demo\u001b","mtu":414,"ready":true,"age_s":12.5}}""").build())
        val ok = checker.check() as CheckResult.Ok
        assertEquals(ServerInfo("my-server", "0.1.0"), ok.server)
        assertEquals("paused", ok.gate)
        assertEquals(AttachedRelay("0.1.0", "Demo", true, 12.5), ok.relay)
        assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
        server.enqueue(MockResponse.Builder().body("""{"server":{"name":"my-server","version":"0.1.0"},"gate":"active","relay":null}""").build())
        assertEquals(null, (checker.check() as CheckResult.Ok).relay)
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(CheckResult.Rejected, checker.check())
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(CheckResult.Outdated, checker.check())
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(checker.check() is CheckResult.Unreachable)
        server.enqueue(MockResponse.Builder().body("not json").build())
        assertTrue(checker.check() is CheckResult.Unreachable)
    }

    @Test fun withoutTheTunnelTheCheckSendsNothing() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val r = OkHttpServerChecker(checkUrl(), token, testHttp(FakeNetwork(NoTunnel.Why.NO_VPN))).check()
        assertEquals(CheckResult.Unreachable(NetError.NoTunnel(NoTunnel.Why.NO_VPN)), r)
        assertEquals(0, server.requestCount)
    }

    @Test fun anotherKeyIsKeyChangedAndNothingIsSent() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertEquals(CheckResult.Unreachable(NetError.KeyChanged), OkHttpServerChecker(checkUrl(), token, testHttp(key = TestTls.other)).check())
        assertEquals(DialResult.Failed(NetError.KeyChanged), OkHttpPhoneDialer(url(), token, testHttp(key = TestTls.other)).dial())
        assertEquals(0, server.requestCount)
    }

    @Test fun withoutAKeyNothingIsSent() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val none: ServerAuth? = null
        assertEquals(CheckResult.Unreachable(NetError.NoKey), OkHttpServerChecker(checkUrl(), token, testHttp(key = none)).check())
        assertEquals(DialResult.Failed(NetError.NoKey), OkHttpPhoneDialer(url(), token, testHttp(key = none)).dial())
        assertEquals(0, server.requestCount)
    }

    @Test fun withoutTheTunnelPhoneIsNeverDialed() = runBlocking {
        val r = OkHttpPhoneDialer(url(), token, testHttp(FakeNetwork(NoTunnel.Why.NOT_COVERED))).dial()
        assertEquals(DialResult.Failed(NetError.NoTunnel(NoTunnel.Why.NOT_COVERED)), r)
        assertEquals(0, server.requestCount)
    }
}
