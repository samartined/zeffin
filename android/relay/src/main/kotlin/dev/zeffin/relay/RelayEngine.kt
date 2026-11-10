// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.FlipperChoice
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import kotlin.random.Random

data class RelayConfig(val base: HttpUrl, val phoneToken: String, val flipper: FlipperChoice) {
    override fun toString() = "RelayConfig($base, ${flipper.address}, token hidden)"
}

sealed interface ConfigState {
    data class Ready(val config: RelayConfig) : ConfigState
    /**
     * NOT_CONFIGURED (server or token), NEEDS_PAIRING or NOT_CHOSEN (Flipper). [flipper]: the chosen Flipper, if any,
     * which the remote can still use without a server.
     */
    data class Missing(val problem: Problem, val flipper: FlipperChoice? = null) : ConfigState
}

/** Builds the pieces that talk to the outside for one configuration. */
interface RelayBackends {
    fun checker(config: RelayConfig): ServerChecker
    fun dialer(config: RelayConfig): PhoneDialer
    fun connector(flipper: FlipperChoice): FlipperConnector
}

/** Waits of 1, 2, 4, 8, 16 and 30 s, each with ±20 % at random. */
class Backoff(private val random: Random = Random.Default) {
    private val steps = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    var failures = 0
        private set

    fun next(): Long {
        val base = steps[minOf(failures, steps.size - 1)]
        failures++
        return (base * (0.8 + 0.4 * random.nextDouble())).toLong()
    }

    fun reset() {
        failures = 0
    }
}

/**
 * The relay's cycle: check the server (GET /phone/check), connect the Flipper, open /phone,
 * run the session, close everything, start over. One Bluetooth link serves one server
 * session, so every session starts on a fresh Flipper RPC session; the exception is a
 * Flipper that locks and unlocks, which opens a fresh RPC session on the same link.
 *
 * While the remote is open and there is no server session, the relay holds the Flipper for it alone and does not dial
 * `/phone`: a cycle that starts, or a link that is ready, with the remote open goes to
 * [runRemote]. A server session already running goes on: the remote pauses the agent through the gate.
 *
 * Runs until cancelled (the owner stops the relay).
 */
class RelayEngine(
    private val loadConfig: suspend () -> ConfigState,
    private val backends: RelayBackends,
    private val runtime: RelayRuntime,
    private val log: RelayLog,
    private val appVersion: String,
    private val clock: Clock = Clock.System,
    random: Random = Random.Default,
    /** The words for the log, in the user's language at the time. */
    private val strings: () -> RelayStrings = { RelayStrings.En },
    /** One log line per frame from the server with the BLE write time and the Flipper's first answers (the test app). */
    private val rpcTiming: Boolean = false,
) {
    /** The remote's side: each server session reads the Flipper's stream for it and writes its keys. */
    var remote: RelayRemote? = null

    companion object {
        const val POLL_MS = 15_000L
        const val OWN_STALE_POLL_MS = 3_000L
        const val OWN_STALE_WINDOW_MS = 60_000L
        const val IDLE_LINK_MS = 60_000L
        const val UNLOCK_GRACE_MS = 2_000L
        const val LONG_SESSION_MS = 60_000L
        const val QUIET_AFTER_MS = 5 * 60_000L
        const val QUIET_RETRY_MS = 5 * 60_000L
        const val OUTDATED_RETRY_MS = 60_000L
        const val SUMMARY_MS = 60_000L
        /** The remote's screen closed and opened again within this (built again on a rotation): the hold goes on. */
        const val REMOTE_CLOSE_GRACE_MS = 1_000L
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val backoff = Backoff(random)

    /** Something changed (network back, Bluetooth on, operator resumed, settings): try now. */
    fun nudge() {
        wake.trySend(Unit)
    }

    /** "Reintentar": also retries a token the server rejected before, or a server whose key did not match. */
    fun retryNow() {
        rejectedToken = null
        keyRefused = null
        backoff.reset()
        troubleSince = null
        nudge()
    }

    @Volatile private var rejectedToken: String? = null
    /** The configuration whose server showed another key: not tried again until a new pairing, or Retry. */
    @Volatile private var keyRefused: RelayConfig? = null
    private var troubleSince: Long? = null
    private var lastOwnEnd: Long? = null
    private var kept: Kept? = null
    /** The link being used or waited on. Stopping the relay closes it: a GATT left open keeps the link up. */
    private var inUse: FlipperLink? = null
    private var unreachable = 0
    private var sessions = 0
    /** When the current link connected: an idle link is kept a minute from then, not from the last retry. */
    private var linkSince = 0L

    private class Kept(val link: FlipperLink, val since: Long, val usedGeneration: Int?)

    suspend fun run() {
        runtime.update { RelayState(stage = Stage.CHECKING_SERVER, startedAt = clock.now(), wakeLock = true) }
        log.info(strings().logStarted)
        try {
            coroutineScope {
                // opening or closing the remote tries now: it takes the Flipper, or gives it back to the cycle
                val watch = launch { remote?.opened?.drop(1)?.collect { nudge() } }
                try {
                    while (true) cycle()
                } finally {
                    watch.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                inUse?.close()
                inUse = null
                kept?.link?.close()
                kept = null
                runtime.update {
                    it.copy(stage = Stage.OFF, problem = null, detail = null, stoppedAt = clock.now(), wakeLock = false,
                        serverSide = ServerSide.NOT_TRIED, flipperSide = FlipperSide.NOT_TRIED, sessionStartedAt = null,
                        nextRetryAt = null, waitingSince = null)
                }
                log.info(strings().logStopped)
            }
        }
    }

    // ---------------------------------------------------------------- one turn of the cycle

    private val remoteOpen: Boolean get() = remote?.opened?.value == true

    private suspend fun cycle() {
        val loaded = loadConfig()
        val chosen = when (loaded) {
            is ConfigState.Ready -> loaded.config.flipper
            is ConfigState.Missing -> loaded.flipper
        }
        if (remoteOpen && chosen != null) {
            if (loaded is ConfigState.Missing) runtime.update { it.copy(serverSide = ServerSide.NOT_TRIED) }
            val link = flipperLink(chosen) ?: return
            return if (remoteOpen) runRemote(link) else keep(link)
        }
        val config = when (val c = loaded) {
            is ConfigState.Missing -> {
                dropKept()
                setStage(Stage.ERROR, c.problem)
                park()
                return
            }
            is ConfigState.Ready -> c.config
        }
        if (rejectedToken != null && rejectedToken == config.phoneToken) {
            setStage(Stage.ERROR, Problem.TOKEN_REJECTED, detail = Detail.Http(401))
            park()
            return
        }
        if (keyRefused == config) {
            setStage(Stage.ERROR, Problem.KEY_CHANGED, detail = Detail.Net(NetError.KeyChanged), serverSide = ServerSide.KEY_CHANGED)
            park()
            return
        }

        // 1. The server
        setStage(Stage.CHECKING_SERVER, serverSide = ServerSide.CHECKING)
        when (val r = backends.checker(config).check()) {
            CheckResult.Rejected -> return tokenRejected(config)
            CheckResult.Outdated -> {
                dropKept()
                setStage(Stage.ERROR, Problem.SERVER_OUTDATED, serverSide = ServerSide.OUTDATED)
                log.error(strings().logServerOutdated)
                pause(OUTDATED_RETRY_MS)
                return
            }
            is CheckResult.Unreachable -> {
                dropKeptIfIdle()
                if (r.error is NetError.NoTunnel) return noTunnel(r.error)
                if (r.error == NetError.KeyChanged || r.error == NetError.NoKey) return keyRefused(config, r.error)
                log.error(strings().logServer(r.error))
                return retryLater(Problem.SERVER_UNREACHABLE, Detail.Net(r.error), ServerSide.UNREACHABLE)
            }
            is CheckResult.Ok -> {
                runtime.update { it.copy(server = r.server, serverSide = ServerSide.READY) }
                if (r.gate == "stopped") {
                    dropKeptIfIdle()
                    return waitServer(Problem.OPERATOR_STOPPED, null, ServerSide.STOPPED, POLL_MS, strings().logOperatorStopped)
                }
                val relay = r.relay
                if (relay != null) {
                    dropKeptIfIdle()
                    val own = lastOwnEnd?.let { clock.elapsed() - it < OWN_STALE_WINDOW_MS } == true &&
                        relay.app == appVersion && relay.device == config.flipper.name
                    return if (own) {
                        waitServer(Problem.OWN_STALE, null, ServerSide.BUSY, OWN_STALE_POLL_MS, null)
                    } else {
                        waitServer(Problem.OTHER_RELAY, relay.app?.let { Detail.OtherApp(it) }, ServerSide.BUSY, POLL_MS,
                            strings().logOtherRelay(relay.app))
                    }
                }
            }
        }

        // 2. The Flipper
        val link = flipperLink(config.flipper) ?: return
        if (remoteOpen) return runRemote(link)  // opened meanwhile: /phone waits until it closes

        // 3. /phone
        setStage(Stage.OPENING_SESSION, serverSide = ServerSide.CHECKING)
        when (val d = backends.dialer(config).dial()) {
            is DialResult.Refused -> {
                when (d.code) {
                    401 -> {
                        link.close()
                        return tokenRejected(config)
                    }
                    409 -> {
                        keep(link)
                        return waitServer(Problem.OTHER_RELAY, null, ServerSide.BUSY, OWN_STALE_POLL_MS, strings().logBusy409)
                    }
                    423 -> {
                        keep(link)
                        return waitServer(Problem.OPERATOR_STOPPED, null, ServerSide.STOPPED, POLL_MS, strings().logStopped423)
                    }
                    else -> {
                        keep(link)
                        log.error(strings().logRefused(d.code))
                        return retryLater(Problem.SERVER_UNREACHABLE, Detail.Http(d.code), ServerSide.UNREACHABLE)
                    }
                }
            }
            is DialResult.Failed -> {
                keep(link)
                if (d.error is NetError.NoTunnel) return noTunnel(d.error)
                if (d.error == NetError.KeyChanged || d.error == NetError.NoKey) return keyRefused(config, d.error)
                log.error(strings().logServer(d.error))
                return retryLater(Problem.SERVER_UNREACHABLE, Detail.Net(d.error), ServerSide.UNREACHABLE)
            }
            is DialResult.Open -> runSession(d.socket, link)
        }
    }

    private suspend fun runSession(socket: PhoneSocket, link: FlipperLink) {
        sessions++
        troubleSince = null
        val startedAt = clock.elapsed()
        runtime.update {
            it.copy(stage = Stage.RUNNING, problem = null, detail = null, serverSide = ServerSide.IN_SESSION,
                flipperSide = FlipperSide.CONNECTED, sessionStartedAt = clock.now(), waitingSince = null,
                nextRetryAt = null, attempt = 0, reconnects = sessions - 1, bytesFromFlipper = 0, bytesToFlipper = 0,
                wakeLock = true)
        }
        log.ok(strings().logSocketOpen)
        log.ok(strings().logRunning)
        var minuteFrom = 0L
        var minuteTo = 0L
        val end = withTicker(SUMMARY_MS, {
            if (minuteFrom > 0 || minuteTo > 0) {
                strings().let { t -> log.info(t.logTraffic(t.bytes(minuteFrom), t.bytes(minuteTo))) }
                minuteFrom = 0
                minuteTo = 0
            }
        }) {
            RelaySession(socket, link, appVersion, onTraffic = { from, to ->
                minuteFrom += from
                minuteTo += to
                runtime.update { it.copy(bytesFromFlipper = it.bytesFromFlipper + from, bytesToFlipper = it.bytesToFlipper + to) }
            }, timing = log.takeIf { rpcTiming }, clock = clock, strings = strings).also { s -> remote?.let { s.remote(it, log) } }.run()
        }
        val lasted = clock.elapsed() - startedAt
        lastOwnEnd = clock.elapsed()
        runtime.update { it.copy(sessionStartedAt = null, lastSessionMs = lasted) }
        strings().let { t -> log.warn(t.logSessionEnded(t.duration(lasted), end)) }
        if (end is SessionEnd.Locked && link.alive) {
            kept = Kept(link, clock.elapsed(), link.rpcGeneration)
        } else {
            link.close()
        }
        if (lasted >= LONG_SESSION_MS) {
            backoff.reset()
        } else {
            retryLater(Problem.SESSION_ENDED, Detail.Session(end), ServerSide.NOT_TRIED)
        }
    }

    // ---------------------------------------------------------------- the remote without a server session

    /**
     * The remote is open and there is no server session: the relay holds [link] for it alone until it closes, the Flipper
     * locks or the link is lost. `serverSide` keeps why there is no server session, for the remote's words.
     */
    private suspend fun runRemote(link: FlipperLink) {
        val r = remote ?: return keep(link)
        troubleSince = null
        setStage(Stage.REMOTE, flipperSide = FlipperSide.CONNECTED)
        log.ok(strings().logRemoteHold(link.name))
        val session = RemoteLinkSession(link, r, log, strings, clock, onTraffic = { from, to ->
            runtime.update { it.copy(bytesFromFlipper = it.bytesFromFlipper + from, bytesToFlipper = it.bytesToFlipper + to) }
        })
        when (session.run { remoteClosed(r) }) {
            RemoteEnd.CLOSED -> {
                log.info(strings().logRemoteReleased)
                if (session.readerStopped || !link.alive) {
                    link.close()  // a stream the relay could not read: the next use connects again
                } else {
                    linkSince = clock.elapsed()  // idle from now, under the usual rule
                    keep(link)
                }
            }
            RemoteEnd.LOCKED -> if (link.alive) kept = Kept(link, clock.elapsed(), link.rpcGeneration) else link.close()
            RemoteEnd.LOST -> {
                link.close()
                val e = link.ended.await()
                log.warn(strings().logFlipper(e.failure))
                retryLater(e.kind.problem, Detail.Flipper(e.failure), null)
            }
        }
    }

    /** Returns once the remote's screen has closed and stayed closed for [REMOTE_CLOSE_GRACE_MS]. */
    private suspend fun remoteClosed(r: RelayRemote) {
        while (true) {
            r.opened.first { !it }
            if (withTimeoutOrNull(REMOTE_CLOSE_GRACE_MS) { r.opened.first { it } } == null) return
        }
    }

    // ---------------------------------------------------------------- the Flipper link

    /** A link ready for a new server session (or the remote), or null after having waited or failed. */
    private suspend fun flipperLink(flipper: FlipperChoice): FlipperLink? {
        kept?.let { k ->
            kept = null
            inUse = k.link
            when {
                !k.link.alive -> Unit
                k.usedGeneration != null -> return awaitUnlock(k.link, k.usedGeneration)
                clock.elapsed() - k.since < IDLE_LINK_MS && k.link.rpcOpen.value -> {
                    linkSince = k.since
                    return k.link
                }
                else -> k.link.close()
            }
        }
        val passive = unreachable >= 2
        setStage(if (passive) Stage.WAITING_FLIPPER else Stage.CONNECTING_FLIPPER,
            if (passive) Problem.FLIPPER_UNREACHABLE else null,
            flipperSide = if (passive) FlipperSide.WAITING else FlipperSide.CONNECTING)
        log.info(if (passive) strings().logFlipperWaiting else strings().logFlipperConnecting)
        val link = try {
            backends.connector(flipper).connect(passive) { step ->
                val side = when (step) {
                    FlipperStep.CONNECTING -> FlipperSide.CONNECTING
                    FlipperStep.WAITING -> FlipperSide.WAITING
                    FlipperStep.SECURING -> FlipperSide.SECURING
                    FlipperStep.MTU -> FlipperSide.MTU
                    FlipperStep.SERVICES -> FlipperSide.SERVICES
                    FlipperStep.SUBSCRIBING -> FlipperSide.SUBSCRIBING
                }
                runtime.update { it.copy(flipperSide = side) }
            }
        } catch (e: FlipperException) {
            flipperFailed(e)
            return null
        }
        inUse = link
        unreachable = 0
        linkSince = clock.elapsed()
        runtime.update { it.copy(deviceName = link.name, mtu = link.mtu, flipperSide = FlipperSide.CONNECTED) }
        log.ok(strings().logFlipperConnected(link.name, link.mtu))
        // Right after power-on the Flipper may answer the first credit read with 0 and open its RPC session a moment
        // later: give it a short grace before calling it locked.
        if (!link.rpcOpen.value && withTimeoutOrNull(UNLOCK_GRACE_MS) { link.rpcOpen.first { it } } == null) {
            return awaitUnlock(link, null)
        }
        return link
    }

    /** The Flipper has no RPC session (locked): wait, without a wake lock, for it to announce credit. */
    private suspend fun awaitUnlock(link: FlipperLink, usedGeneration: Int?): FlipperLink? {
        setStage(Stage.FLIPPER_LOCKED, flipperSide = FlipperSide.LOCKED)
        log.warn(strings().logFlipperLocked)
        val unlocked = coroutineScope {
            val opened = async {
                link.rpcOpen.first { it && (usedGeneration == null || link.rpcGeneration > usedGeneration) }
                true
            }
            val result = select {
                opened.onAwait { it }
                link.ended.onAwait { false }
            }
            opened.cancel()
            result
        }
        if (unlocked && link.alive) {
            log.ok(strings().logFlipperUnlocked)
            return link
        }
        link.close()
        return null
    }

    private suspend fun flipperFailed(e: FlipperException) {
        val problem = e.kind.problem
        runtime.update { it.copy(flipperSide = FlipperSide.FAILED) }
        when (e.kind) {
            FlipperProblem.NOT_CHOSEN, FlipperProblem.BLUETOOTH_OFF, FlipperProblem.NO_PERMISSION,
            FlipperProblem.NOT_BONDED, FlipperProblem.BOND_LOST, FlipperProblem.NOT_A_FLIPPER -> {
                log.error(strings().logFlipper(e.failure))
                setStage(Stage.ERROR, problem, detail = Detail.Flipper(e.failure))
                park()
            }
            FlipperProblem.UNREACHABLE -> {
                unreachable++
                log.warn(strings().logFlipper(e.failure))
                retryLater(problem, Detail.Flipper(e.failure), null)
            }
            else -> {
                log.warn(strings().logFlipper(e.failure))
                retryLater(problem, Detail.Flipper(e.failure), null)
            }
        }
    }

    // ---------------------------------------------------------------- waiting

    private suspend fun tokenRejected(config: RelayConfig) {
        rejectedToken = config.phoneToken
        dropKept()
        log.error(strings().logTokenRejected)
        setStage(Stage.ERROR, Problem.TOKEN_REJECTED, detail = Detail.Http(401), serverSide = ServerSide.REJECTED,
            flipperSide = FlipperSide.NOT_TRIED)
    }

    /**
     * The server did not show the pairing's key, or there is no key: nothing was sent. A hard stop, not a backoff:
     * only a new pairing (a new engine) or Retry tries again.
     */
    private suspend fun keyRefused(config: RelayConfig, error: NetError) {
        dropKept()
        log.error(strings().logServer(error))
        if (error == NetError.KeyChanged) {
            keyRefused = config
            setStage(Stage.ERROR, Problem.KEY_CHANGED, Detail.Net(error), serverSide = ServerSide.KEY_CHANGED, flipperSide = FlipperSide.NOT_TRIED)
        } else {
            setStage(Stage.ERROR, Problem.NEEDS_PAIRING, serverSide = ServerSide.NOT_TRIED, flipperSide = FlipperSide.NOT_TRIED)
        }
        park()
    }

    private suspend fun waitServer(problem: Problem, detail: Detail?, side: ServerSide, pollMs: Long, message: String?) {
        if (troubleSince == null) troubleSince = clock.elapsed()
        if (message != null && runtime.state.value.problem != problem) log.warn(message)
        val quiet = quiet()
        setStage(Stage.WAITING_SERVER, problem, detail, serverSide = side, quiet = quiet)
        pause(if (quiet) QUIET_RETRY_MS else pollMs)
    }

    /** Nothing was sent: the private network's tunnel is down. It waits for the tunnel (the service nudges it), not a backoff. */
    private suspend fun noTunnel(error: NetError.NoTunnel) =
        waitServer(Problem.NO_TUNNEL, Detail.Net(error), ServerSide.NO_TUNNEL, POLL_MS, strings().logServer(error))

    private suspend fun retryLater(problem: Problem, detail: Detail?, side: ServerSide?) {
        if (troubleSince == null) troubleSince = clock.elapsed()
        val quiet = quiet()
        val wait = if (quiet) QUIET_RETRY_MS else backoff.next()
        runtime.update { it.copy(attempt = it.attempt + 1) }
        strings().let { t -> log.warn(t.logRetry(runtime.state.value.attempt, t.duration(wait))) }
        setStage(Stage.RETRYING, problem, detail, serverSide = side, quiet = quiet, nextRetryAt = clock.now() + wait)
        pause(wait)
    }

    private fun quiet() = troubleSince?.let { clock.elapsed() - it >= QUIET_AFTER_MS } == true

    private fun setStage(
        stage: Stage,
        problem: Problem? = null,
        detail: Detail? = null,
        serverSide: ServerSide? = null,
        flipperSide: FlipperSide? = null,
        quiet: Boolean = false,
        nextRetryAt: Long? = null,
    ) {
        val wakeLock = when (stage) {
            Stage.CHECKING_SERVER, Stage.OPENING_SESSION, Stage.RUNNING, Stage.REMOTE, Stage.CONNECTING_FLIPPER -> true
            Stage.RETRYING, Stage.WAITING_SERVER -> !quiet
            else -> false
        }
        runtime.update {
            it.copy(
                stage = stage, problem = problem, detail = detail, wakeLock = wakeLock, nextRetryAt = nextRetryAt,
                serverSide = serverSide ?: it.serverSide, flipperSide = flipperSide ?: it.flipperSide,
                waitingSince = if (stage == Stage.RUNNING) null else (it.waitingSince ?: clock.now()),
            )
        }
    }

    private fun keep(link: FlipperLink) {
        kept = Kept(link, linkSince, null)
    }

    private suspend fun dropKept() {
        kept?.link?.close()
        kept = null
    }

    /** An idle link is kept at most a minute; a locked one (waiting for unlock) stays. */
    private suspend fun dropKeptIfIdle() {
        val k = kept ?: return
        if (!k.link.alive || (k.usedGeneration == null && clock.elapsed() - k.since >= IDLE_LINK_MS)) dropKept()
    }

    /** Returns true if nudged before [ms]. */
    private suspend fun pause(ms: Long): Boolean = withTimeoutOrNull(ms) { wake.receive(); true } ?: false

    private suspend fun park() {
        wake.receive()
    }
}
