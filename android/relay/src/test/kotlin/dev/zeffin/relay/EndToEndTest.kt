// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.FlipperChoice
import dev.zeffin.core.ServerAuth
import dev.zeffin.core.ServerHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The relay engine against the real Python server, with the simulator as the Flipper.
 * android/scripts/e2e.sh starts both and runs this test; without them it is skipped.
 */
class EndToEndTest {
    private val url = System.getenv("FB_E2E_URL")
    /** The pin the pairing QR code would carry: the test pins the real server's key, as the app does. */
    private val key = System.getenv("FB_E2E_KEY")?.let(ServerAuth.PinnedKey::parse)
    private val phoneToken = System.getenv("FB_E2E_PHONE_TOKEN")
    private val appToken = System.getenv("FB_E2E_APP_TOKEN")
    private val flipperPort = System.getenv("FB_E2E_FLIPPER_PORT")?.toIntOrNull()

    /** A plain /app client, as the app's AppLink would see the server. */
    private class AppClient(url: String, token: String, key: ServerAuth?) : WebSocketListener() {
        private val byType = java.util.concurrent.ConcurrentHashMap<String, LinkedBlockingQueue<JsonObject>>()
        private fun queue(type: String) = byType.getOrPut(type) { LinkedBlockingQueue() }
        val ws: WebSocket = testHttp(key = key).newWebSocket(ServerHttp.Use.APP_SOCKET,
            Request.Builder().url(url).header("Authorization", "Bearer $token").build(), this)

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = Json.parseToJsonElement(text).jsonObject
            queue(m["type"]!!.jsonPrimitive.content).put(m)
        }

        /** The next message of [type] matching [check]; other types wait in their own queues. */
        fun until(type: String, seconds: Long = 15, check: (JsonObject) -> Boolean = { true }): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                val m = queue(type).poll(maxOf(left, 0), TimeUnit.NANOSECONDS) ?: error("no $type message")
                if (check(m)) return m
            }
        }

        fun drain(type: String) = queue(type).clear()

        fun gate(action: String) = ws.send("""{"type":"gate","action":"$action"}""")
    }

    private fun engine(token: String, runtime: RelayRuntime, key: ServerAuth? = this.key): RelayEngine {
        val base = url!!.toHttpUrl()
        val config = RelayConfig(base, token, FlipperChoice("00:00:00:00:00:00", "Sim"))
        val backends = object : RelayBackends {
            override fun checker(config: RelayConfig) =
                OkHttpServerChecker(base.newBuilder().encodedPath("/phone/check").build(), config.phoneToken, testHttp(key = key))
            override fun dialer(config: RelayConfig) =
                OkHttpPhoneDialer(url.replace("https://", "wss://") + "/phone", config.phoneToken, testHttp(key = key))
            override fun connector(flipper: FlipperChoice) = FlipperConnector { _, _ -> TcpFlipperLink(flipperPort!!) }
        }
        return RelayEngine({ ConfigState.Ready(config) }, backends, runtime, RelayLog(null), "0.1.0")
    }

    @Test fun relayThroughTheRealServer() = runBlocking {
        assumeTrue("set by android/scripts/e2e.sh", url != null && key != null && phoneToken != null && appToken != null && flipperPort != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RelayRuntime()
        val engine = engine(phoneToken!!, runtime)
        val app = AppClient(url!!.replace("https://", "wss://") + "/app", appToken!!, key)
        try {
            scope.launch { engine.run() }
            withTimeout(20_000) { runtime.state.first { it.stage == Stage.RUNNING } }

            // the server sees the relay with the extended ready, and the screen stream starts
            app.until("status") { it["connected"]!!.jsonPrimitive.content == "true" }
            val relay = app.until("status") { it["relay"] is JsonObject }["relay"]!!.jsonObject
            assertEquals("0.1.0", relay["app"]!!.jsonPrimitive.content)
            assertEquals("Sim", relay["device"]!!.jsonPrimitive.content)
            assertEquals("414", relay["mtu"]!!.jsonPrimitive.content)
            app.until("frame")

            // a request that round-trips through the relay (stop + start stream): a new frame comes back
            app.drain("frame")
            app.ws.send("""{"type":"screen.fresh"}""")
            app.until("frame")

            // the operator stops the agent: the server closes the session and the relay waits
            app.gate("stop")
            withTimeout(15_000) { runtime.state.first { it.problem == Problem.OPERATOR_STOPPED } }
            assertTrue(runtime.state.value.serverSide == ServerSide.STOPPED)

            // resume from the app: with the app's nudge the relay is back at once, not after 15 s
            // Older statuses (from before the stop) still say "active": wait for one after the resume.
            app.drain("status")
            app.gate("resume")
            app.until("status") { it["gate"]!!.jsonPrimitive.content == "active" }
            val t0 = System.nanoTime()
            engine.nudge()
            withTimeout(10_000) { runtime.state.first { it.stage == Stage.RUNNING } }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
            app.drain("frame")
            app.until("frame")
        } finally {
            scope.cancel()
            app.ws.close(1000, null)
        }
        delay(200)
    }

    /**
     * The remote: with the agent paused by the remote, a key goes over the relay's own link and its replies
     * come back to the relay. e2e.sh then checks that the server counted them as the relay's and that none reached
     * flipper_events.
     */
    @Test fun aKeyFromTheRemoteGoesOverTheRelaysLink() = runBlocking {
        assumeTrue("set by android/scripts/e2e.sh", url != null && key != null && phoneToken != null && appToken != null && flipperPort != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RelayRuntime()
        val remote = RelayRemote()
        val engine = engine(phoneToken!!, runtime).also { it.remote = remote }
        val app = AppClient(url!!.replace("https://", "wss://") + "/app", appToken!!, key)
        try {
            scope.launch { engine.run() }
            withTimeout(20_000) { runtime.state.first { it.stage == Stage.RUNNING } }
            // in step with the server's settle, the relay reads the stream the server started
            withTimeout(15_000) { remote.frames.first { it != null } }
            app.ws.send("""{"type":"gate","action":"pause","via":"remote"}""")
            app.until("status") { it["gate"]!!.jsonPrimitive.content == "paused" && it["gate_by"]?.jsonPrimitive?.content == "remote" }
            withTimeout(10_000) { remote.keys.first { it == RemoteKeys.Ready } }
            assertTrue(remote.press(FlipperKey.OK, long = false))
            assertTrue(remote.press(FlipperKey.BACK, long = true))
            delay(2_500)
            // both keys' RELEASE replies came within a second each: the keys never went to "waiting for the Flipper"
            assertEquals(RemoteKeys.Ready, remote.keys.value)
            assertEquals(null, remote.refused.value)
            app.gate("resume")
            app.until("status") { it["gate"]!!.jsonPrimitive.content == "active" }
            // the relay stops: the server sees the session end and writes its close line (relay_replies) for e2e.sh
            scope.cancel()
            app.until("status") { it["connected"]!!.jsonPrimitive.content == "false" }
        } finally {
            scope.cancel()
            app.ws.close(1000, null)
        }
        delay(200)
    }

    /**
     * The remote without a server: the relay's server is not running (a closed port), so the relay holds
     * the simulator's link for the remote alone, syncs on its own stop, starts its own stream and presses keys with no
     * /phone at all; closing the remote stops its stream and gives the link back to the cycle.
     */
    @Test fun withoutAServerTheRemotePressesThroughTheSimulator() = runBlocking {
        assumeTrue("set by android/scripts/e2e.sh", flipperPort != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RelayRuntime()
        val remote = RelayRemote()
        val closedPort = java.net.ServerSocket(0).use { it.localPort }  // nothing listens there any more
        val base = "https://127.0.0.1:$closedPort".toHttpUrl()
        val config = RelayConfig(base, "t".repeat(43), FlipperChoice("00:00:00:00:00:00", "Sim"))
        var dials = 0
        val backends = object : RelayBackends {
            override fun checker(config: RelayConfig) =
                OkHttpServerChecker(base.newBuilder().encodedPath("/phone/check").build(), config.phoneToken, testHttp())
            override fun dialer(config: RelayConfig) = PhoneDialer { dials++; DialResult.Failed(NetError.Closed) }
            override fun connector(flipper: FlipperChoice) = FlipperConnector { _, _ -> TcpFlipperLink(flipperPort!!) }
        }
        val engine = RelayEngine({ ConfigState.Ready(config) }, backends, runtime, RelayLog(null), "0.1.0").also { it.remote = remote }
        try {
            scope.launch { engine.run() }
            withTimeout(15_000) { runtime.state.first { it.problem == Problem.SERVER_UNREACHABLE } }
            remote.open()
            withTimeout(15_000) { runtime.state.first { it.stage == Stage.REMOTE } }
            withTimeout(15_000) { remote.frames.first { it != null } }
            withTimeout(10_000) { remote.keys.first { it == RemoteKeys.Ready } }
            val before = remote.frames.value!!.seq
            assertTrue(remote.press(FlipperKey.DOWN, long = false))
            assertTrue(remote.press(FlipperKey.BACK, long = true))
            delay(2_500)
            assertEquals(RemoteKeys.Ready, remote.keys.value)  // each RELEASE answered within a second
            assertEquals(null, remote.refused.value)
            assertTrue("the stream the relay started goes on", remote.frames.value!!.seq > before)
            assertEquals(0, dials)
            remote.close()
            withTimeout(10_000) { runtime.state.first { it.stage != Stage.REMOTE } }
            assertEquals(null, remote.frames.value)
            assertEquals(RemoteKeys.Off(KeysOff.NO_SESSION), remote.keys.value)
        } finally {
            scope.cancel()
        }
        delay(200)
    }

    @Test fun anotherKeyIsAHardStopAndNothingIsSent() = runBlocking {
        assumeTrue("set by android/scripts/e2e.sh", url != null && key != null && phoneToken != null && flipperPort != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RelayRuntime()
        try {
            scope.launch { engine(phoneToken!!, runtime, TestTls.other).run() }
            withTimeout(10_000) { runtime.state.first { it.stage == Stage.ERROR } }
            assertEquals(Problem.KEY_CHANGED, runtime.state.value.problem)
            assertEquals(ServerSide.KEY_CHANGED, runtime.state.value.serverSide)
            assertEquals(FlipperSide.NOT_TRIED, runtime.state.value.flipperSide)
            delay(1_500)
            assertEquals("no retries", 0, runtime.state.value.attempt)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aWrongTokenIsAnErrorWithoutRetries() = runBlocking {
        assumeTrue("set by android/scripts/e2e.sh", url != null && key != null && flipperPort != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RelayRuntime()
        try {
            scope.launch { engine("x".repeat(43), runtime).run() }
            withTimeout(10_000) { runtime.state.first { it.stage == Stage.ERROR } }
            assertEquals(Problem.TOKEN_REJECTED, runtime.state.value.problem)
            assertEquals(FlipperSide.NOT_TRIED, runtime.state.value.flipperSide)
        } finally {
            scope.cancel()
        }
    }
}
