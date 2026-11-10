// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Addresses
import dev.zeffin.core.AppSettings
import dev.zeffin.core.FlipperChoice
import dev.zeffin.core.PairingRecord
import dev.zeffin.core.ServerAuth
import okhttp3.HttpUrl.Companion.toHttpUrl
import dev.zeffin.link.AppLink
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.AuditItem
import dev.zeffin.link.Conn
import dev.zeffin.link.Frame
import dev.zeffin.link.PhoneDetail
import dev.zeffin.link.PhoneEvent
import dev.zeffin.link.RelayInfo
import dev.zeffin.link.ServerStatus
import dev.zeffin.core.Lang
import dev.zeffin.core.NoTunnel
import dev.zeffin.relay.Detail
import dev.zeffin.relay.FlipperFailure
import dev.zeffin.relay.FlipperSide
import dev.zeffin.relay.LogEntry
import dev.zeffin.relay.LogLevel
import dev.zeffin.relay.NetError
import dev.zeffin.relay.Problem
import dev.zeffin.relay.RelayStrings
import dev.zeffin.relay.RelayState
import dev.zeffin.relay.ServerInfo
import dev.zeffin.relay.ServerSide
import dev.zeffin.relay.Stage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import dev.zeffin.ui.present.Fmt

/** The situation of the approved mockups: Flipper "Demo", server "my-server", frame 418, 14:05:31. Fake tokens. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("UTC")
    init { Fmt.zone = zone }

    val now: Long = ZonedDateTime.of(2026, 10, 22, 14, 5, 31, 0, zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 10, 22, h, m, s, 0, zone).toInstant().toEpochMilli()
    private fun sec(ms: Long) = ms / 1000.0

    val frameData: ByteArray = javaClass.classLoader!!.getResourceAsStream("screen/frame.bin")!!.readBytes()
    val frame = Frame(418, 0, false, frameData)

    val settings = AppSettings(
        flipper = FlipperChoice("80:E1:26:00:00:01", "Demo"),
        autoStart = true,
    )
    /** The pin of docs/protocol/pairing/key-vector.json: fingerprint 27BA 42BE 3FD7 D25B 86F5. */
    val key = ServerAuth.PinnedKey.parse("J7pCvj_X0luG9SgZ7rrB0TGpD72G8BVi5G3NaLsCPS8")!!
    val pairing = PairingRecord(Addresses.parseBase("https://100.98.12.7:9434"),
        appToken = "FAKE-app-token-for-tests-only-00000000000002b7e", phoneToken = "FAKE-phone-token-for-tests-only-0000000002b7e", key = key)
    /** A pairing from before the server's key was pinned: no key, cleartext then. */
    val pairingWithoutKey = pairing.copy(base = "http://100.98.12.7:9434".toHttpUrl(), key = null)

    val status = ServerStatus(
        connected = true, phoneAttached = true, transport = "BLE", relay = RelayInfo("0.1.0", "Demo", 414),
        gate = "active", gateChangedAt = sec(at(13, 52)), gateBy = "startup", screenSeq = 418, screenAgeS = 0.4,
        sessionUptimeS = 720.0, lastPingMs = 38.0, sessionFrames = 418, sessionBytesIn = 441_344, sessionBytesOut = 2_150,
    )

    val audit = listOf(
        AuditItem(sec(at(14, 2, 10)), "flipper_screen", "scale=4", "ok", 120, 415, null, null),
        AuditItem(sec(at(14, 2, 40)), "flipper_press", "keys=DOWN, DOWN, DOWN, OK", "ok", 1_400, 418, null, null),
        AuditItem(sec(at(14, 3, 2)), "flipper_storage_list", "path=/ext/infrared", "ok", 310, 418, null, null),
    )

    val diag = Json.parseToJsonElement("""{"type":"diag","hours":24,"session":{"frames":418,"bytes_in":441344,"bytes_out":2150,"uptime_s":720},
        "connections":3,"reached_ready":2,"reconnect_median_s":4.1,"rejected":{"bad_token":1},
        "last_rejected":{"reason":"bad_token","ts":${sec(at(13, 51))}},"pings":24,"pings_ok":24,"rtt_median_ms":38,
        "stream_failures":0,"agent_actions":14}""").jsonObject

    val phone = listOf(
        PhoneEvent(sec(at(13, 51)), "ws_rejected", "bad_token", null),
        PhoneEvent(sec(at(13, 53, 36)), "ws_open", null, null),
        PhoneEvent(sec(at(13, 53, 40)), "ready", null, PhoneDetail("BLE · Flipper Demo · MTU 414", null, null, null, null)),
        PhoneEvent(sec(at(13, 53, 41)), "first_frame", null, PhoneDetail(null, 0.6, null, null, null)),
    )

    val link = AppLinkState(Conn.CONNECTED, "my-server", "0.1.0", status, audit, phone, diag, 38)
    /** /app is down because the private network's tunnel does not reach the server. */
    val linkNoTunnel = link.copy(conn = Conn.OFFLINE, status = null, error = NetError.NoTunnel(NoTunnel.Why.NO_VPN))
    /** The server showed another key than the pairing's: /app waits for a new pairing. */
    val linkKeyChanged = link.copy(conn = Conn.KEY_CHANGED, status = null, error = NetError.KeyChanged)
    /** The pairing has no key: /app sent nothing. */
    val linkPairAgain = link.copy(conn = Conn.NEEDS_PAIRING, status = null, error = NetError.NoKey)

    val relayRunning = RelayState(
        stage = Stage.RUNNING, server = ServerInfo("my-server", "0.1.0"), serverSide = ServerSide.IN_SESSION,
        flipperSide = FlipperSide.CONNECTED, deviceName = "Demo", mtu = 414, startedAt = at(13, 52),
        sessionStartedAt = at(13, 53, 40), reconnects = 0, wakeLock = true,
    )
    val relayConnecting = RelayState(
        stage = Stage.CONNECTING_FLIPPER, server = ServerInfo("my-server", "0.1.0"), serverSide = ServerSide.READY,
        flipperSide = FlipperSide.MTU, deviceName = "Demo", startedAt = at(14, 5), waitingSince = now - 4_000, attempt = 1,
    )
    val relayError = RelayState(
        stage = Stage.ERROR, problem = Problem.TOKEN_REJECTED, detail = Detail.Http(401), server = ServerInfo("my-server", "0.1.0"),
        serverSide = ServerSide.REJECTED, flipperSide = FlipperSide.NOT_TRIED, startedAt = at(14, 5),
    )
    /** The private network's tunnel is down: the relay waits for it, nothing was sent. */
    val relayNoTunnel = RelayState(
        stage = Stage.WAITING_SERVER, problem = Problem.NO_TUNNEL, detail = Detail.Net(NetError.NoTunnel(NoTunnel.Why.NO_VPN)),
        serverSide = ServerSide.NO_TUNNEL, flipperSide = FlipperSide.NOT_TRIED, startedAt = at(14, 5), waitingSince = at(14, 5, 31),
    )
    val relayKeyChanged = RelayState(
        stage = Stage.ERROR, problem = Problem.KEY_CHANGED, detail = Detail.Net(NetError.KeyChanged), serverSide = ServerSide.KEY_CHANGED,
        flipperSide = FlipperSide.NOT_TRIED, deviceName = "Demo", startedAt = at(14, 5),
    )
    val relayPairAgain = RelayState(stage = Stage.ERROR, problem = Problem.NEEDS_PAIRING, deviceName = "Demo", startedAt = at(14, 5))
    val relayStopped = RelayState(stage = Stage.OFF, deviceName = "Demo", lastSessionMs = 12 * 60_000, stoppedAt = at(14, 17))
    val relayLocked = relayConnecting.copy(stage = Stage.FLIPPER_LOCKED, flipperSide = FlipperSide.LOCKED, mtu = 414)
    val relayBondLost = relayError.copy(problem = Problem.BOND_LOST, detail = Detail.Flipper(FlipperFailure.BondLost(5)),
        serverSide = ServerSide.READY, flipperSide = FlipperSide.FAILED)

    /** The relay log as the relay writes it, in [lang]. */
    fun log(lang: Lang): List<LogEntry> {
        val r = RelayStrings.of(lang)
        return listOf(
            LogEntry(at(13, 52, 9), LogLevel.INFO, "Android 14 · app 0.1.0"),
            LogEntry(at(13, 53, 30), LogLevel.INFO, r.logStartedByUser),
            LogEntry(at(13, 53, 31), LogLevel.ERROR, r.logServer(NetError.Timeout)),
            LogEntry(at(13, 53, 35), LogLevel.WARN, r.logRetry(2, "4 s")),
            LogEntry(at(13, 53, 36), LogLevel.INFO, r.logFlipperConnecting),
            LogEntry(at(13, 53, 39), LogLevel.INFO, r.logMtu(414)),
            LogEntry(at(13, 53, 40), LogLevel.OK, r.logNotifications),
            LogEntry(at(13, 53, 40), LogLevel.OK, r.logSocketOpen),
            LogEntry(at(13, 53, 40), LogLevel.OK, r.logRunning),
            LogEntry(at(14, 4, 40), LogLevel.INFO, r.logTraffic(r.bytes(65_740), r.bytes(1_126))),
            LogEntry(at(14, 5, 29), LogLevel.INFO, r.logTraffic(r.bytes(63_283), r.bytes(921))),
        )
    }

    @Suppress("unused") private val keepLinkClass = AppLink::class
}
