// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.NoTunnel
import dev.zeffin.link.AppLink
import dev.zeffin.link.Conn
import dev.zeffin.link.LinkEvent
import dev.zeffin.relay.NetError
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** AppLink's connection loop against a real WebSocket server: reconnection, backoff, a refused token and a lost pong. */
class AppLinkLoopTest {
    private lateinit var server: MockWebServer
    private val token = "a".repeat(43)
    private val events = CopyOnWriteArrayList<LinkEvent>()
    private lateinit var link: AppLink

    @Before fun setUp() {
        server = MockWebServer().tls()
        server.start()
    }

    @After fun tearDown() {
        if (::link.isInitialized) link.stop()
        server.close()
    }

    private fun url() = server.base("/app").toString().replace("https://", "wss://")

    /** A link with short waits, subscribed before it connects (as AppGraph is). */
    private fun start(pingEveryMs: Long = 60_000, firstWaitMs: Long = 50, maxWaitMs: Long = 200, net: FakeNetwork = FakeNetwork(),
                      key: dev.zeffin.core.ServerAuth? = TestTls.key): AppLink {
        link = AppLink(testHttp(net, key = key), pingEveryMs = pingEveryMs, firstWaitMs = firstWaitMs, maxWaitMs = maxWaitMs)
        link.onEvents { events += it }
        link.start(url(), token)
        return link
    }

    /** The server side: says hello on open and answers pings, unless told to stay silent or to close at once. */
    private class Peer(val pong: Boolean = true, val closeAtOnce: Boolean = false) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"hello","protocol":1,"server":{"version":"0.1.0","name":"my-server"},"features":["chat"]}""")
            if (closeAtOnce) webSocket.close(1000, null)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (pong && text.contains("\"ping\"")) webSocket.send("""{"type":"pong"}""")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun upgrade(peer: Peer = Peer()) = MockResponse.Builder().webSocketUpgrade(peer).build()

    private fun waitFor(what: String, seconds: Int = 5, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what: ${link.state.value.conn}, $events")
            Thread.sleep(10)
        }
    }

    @Test fun theFirstHelloIsNeverLost() {
        server.enqueue(upgrade())
        start()
        waitFor("the hello") { events.any { it is LinkEvent.Hello } }
        assertEquals(setOf("chat"), (events.first() as LinkEvent.Hello).features)
        assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
        waitFor("connected") { link.state.value.conn == Conn.CONNECTED }
    }

    @Test fun withoutTheTunnelNothingIsSentAndItConnectsWhenItIsBack() {
        val net = FakeNetwork(NoTunnel.Why.NO_VPN)
        start(firstWaitMs = 60_000, maxWaitMs = 60_000, net = net)
        waitFor("offline") { link.state.value.conn == Conn.OFFLINE }
        assertEquals(NetError.NoTunnel(NoTunnel.Why.NO_VPN), link.state.value.error)
        assertEquals(0, server.requestCount)
        server.enqueue(upgrade())
        net.down = null
        link.networkChanged()  // what AppGraph does when the private network changes: no wait for the backoff
        waitFor("connected") { link.state.value.conn == Conn.CONNECTED }
        assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
    }

    @Test fun aStoppedLinkIgnoresItsOldSocket() {
        // The server answers late: the link is stopped (a new pairing being saved) before the socket opens.
        server.enqueue(MockResponse.Builder().headersDelay(500, TimeUnit.MILLISECONDS).webSocketUpgrade(Peer()).build())
        start()
        waitFor("the request") { server.requestCount == 1 }
        link.stop()
        Thread.sleep(1_000)
        assertEquals(Conn.STOPPED, link.state.value.conn)
        assertNull(link.state.value.serverName)
        assertTrue(events.none { it is LinkEvent.Hello })
    }

    @Test fun aClosedConnectionReconnects() {
        server.enqueue(upgrade(Peer(closeAtOnce = true)))
        server.enqueue(upgrade())
        start()
        waitFor("two hellos") { events.count { it is LinkEvent.Hello } == 2 }
        assertTrue(events.indexOf(LinkEvent.Disconnected) in 1 until events.lastIndexOf(events.last { it is LinkEvent.Hello }))
        assertEquals(2, server.requestCount)
        waitFor("connected again") { link.state.value.conn == Conn.CONNECTED }
    }

    @Test fun failuresWaitLongerEachTimeUpToTheMaximum() {
        repeat(5) { server.enqueue(MockResponse.Builder().code(503).build()) }
        server.enqueue(upgrade())
        val times = mutableListOf<Long>()
        start(firstWaitMs = 100, maxWaitMs = 400)
        repeat(6) {
            server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("request ${it + 1} never came")
            times += System.nanoTime() / 1_000_000
        }
        val gaps = times.zipWithNext { a, b -> b - a }
        // 100, 200, 400, then the maximum: 400, 400
        assertTrue("gaps $gaps", gaps[0] >= 90 && gaps[1] >= 180 && gaps[2] >= 360 && gaps[3] >= 360 && gaps[4] >= 360)
        assertTrue("gaps $gaps", gaps[3] < 1_500 && gaps[4] < 1_500)
        waitFor("connected after the failures") { link.state.value.conn == Conn.CONNECTED }
    }

    @Test fun aRefusedTokenWaitsForRetry() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(upgrade())
        start()
        waitFor("rejected") { link.state.value.conn == Conn.REJECTED }
        Thread.sleep(500)  // ten times the first wait: it does not try again by itself
        assertEquals(1, server.requestCount)
        assertEquals(Conn.REJECTED, link.state.value.conn)
        assertNull(link.state.value.error)
        link.retry()
        waitFor("connected after retry") { link.state.value.conn == Conn.CONNECTED }
        assertEquals(2, server.requestCount)
    }

    @Test fun anotherKeyIsAHardStopThatTheNetworkDoesNotRetry() {
        server.enqueue(upgrade())
        start(key = TestTls.other)
        waitFor("key changed") { link.state.value.conn == Conn.KEY_CHANGED }
        assertEquals(NetError.KeyChanged, link.state.value.error)
        link.networkChanged()  // the network coming back is no reason to knock again
        Thread.sleep(500)  // ten times the first wait: it does not try again by itself
        assertEquals(Conn.KEY_CHANGED, link.state.value.conn)
        assertEquals(0, server.requestCount)
        link.stop()
        assertEquals("a stop keeps it, for the screens", Conn.KEY_CHANGED, link.state.value.conn)
    }

    @Test fun withoutAKeyNothingIsSent() {
        server.enqueue(upgrade())
        start(key = null)
        waitFor("needs pairing") { link.state.value.conn == Conn.NEEDS_PAIRING }
        Thread.sleep(300)
        assertEquals(Conn.NEEDS_PAIRING, link.state.value.conn)
        assertEquals(0, server.requestCount)
    }

    @Test fun aMissingPongReconnects() {
        server.enqueue(upgrade(Peer(pong = false)))
        server.enqueue(upgrade())
        start(pingEveryMs = 200)
        waitFor("a second connection") { server.requestCount == 2 && events.count { it is LinkEvent.Hello } == 2 }
        assertTrue(events.contains(LinkEvent.Disconnected))
        waitFor("connected again") { link.state.value.conn == Conn.CONNECTED }
        waitFor("a measured latency") { link.state.value.latencyMs != null }
    }
}
