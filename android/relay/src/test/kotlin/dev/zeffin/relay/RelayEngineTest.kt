// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.FlipperChoice
import dev.zeffin.core.NoTunnel
import dev.zeffin.core.CoreGraph
import dev.zeffin.relay.rpc.Pb
import dev.zeffin.relay.rpc.RpcReader
import dev.zeffin.relay.rpc.Wire
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RelayEngineTest {
    private val config = RelayConfig("https://100.98.12.7:9434".toHttpUrl(), "t".repeat(43), FlipperChoice("80:E1:26:00:00:01", "Demo"))
    private val ok = CheckResult.Ok(ServerInfo("my-server", "0.1.0"), "active", null)

    inner class Backends : RelayBackends {
        val checks = ArrayDeque<CheckResult>()
        var check: CheckResult = ok
        var checkCount = 0
        val dials = ArrayDeque<() -> DialResult>()
        var dialCount = 0
        val connects = ArrayDeque<(Boolean) -> FlipperLink>()
        val passive = mutableListOf<Boolean>()
        val sockets = mutableListOf<FakeSocket>()
        val links = mutableListOf<FakeLink>()

        override fun checker(config: RelayConfig) = ServerChecker { checkCount++; checks.removeFirstOrNull() ?: check }
        override fun dialer(config: RelayConfig) = PhoneDialer {
            dialCount++
            dials.removeFirstOrNull()?.invoke() ?: FakeSocket().also { sockets += it }.let { DialResult.Open(it) }
        }
        override fun connector(flipper: FlipperChoice) = FlipperConnector { p, _ ->
            passive += p
            connects.removeFirstOrNull()?.invoke(p) ?: FakeLink().also { links += it }
        }
    }

    private fun TestScope.start(
        b: Backends,
        runtime: RelayRuntime = RelayRuntime(),
        remote: RelayRemote? = null,
        load: () -> ConfigState = { ConfigState.Ready(config) },
    ): Pair<RelayEngine, RelayRuntime> {
        val engine = RelayEngine({ load() }, b, runtime, RelayLog(null, TestClock(this)), "0.1.0",
            TestClock(this), Random(1))
        engine.remote = remote
        backgroundScope.launch { engine.run() }
        runCurrent()
        return engine to runtime
    }

    @Test fun aRejectedTokenStopsBeforeTouchingTheFlipper() = runTest {
        val b = Backends().apply { check = CheckResult.Rejected }
        val (engine, rt) = start(b)
        assertEquals(Stage.ERROR, rt.state.value.stage)
        assertEquals(Problem.TOKEN_REJECTED, rt.state.value.problem)
        advanceTimeBy(600_000)
        assertEquals(1, b.checkCount)
        assertTrue(b.passive.isEmpty())
        b.check = ok
        engine.retryNow()
        runCurrent()
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    @Test fun anotherKeyIsAHardStopThatNudgesDoNotRetry() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.KeyChanged) }
        val (engine, rt) = start(b)
        assertEquals(Stage.ERROR, rt.state.value.stage)
        assertEquals(Problem.KEY_CHANGED, rt.state.value.problem)
        assertEquals(ServerSide.KEY_CHANGED, rt.state.value.serverSide)
        assertTrue(b.passive.isEmpty())  // the Flipper is not touched
        repeat(3) { engine.nudge(); runCurrent() }  // the network comes and goes: no new try
        advanceTimeBy(600_000)
        assertEquals(1, b.checkCount)
        assertEquals(0, rt.state.value.attempt)
        b.check = ok
        engine.retryNow()  // the owner's Retry tries once more
        runCurrent()
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    @Test fun anotherKeyWhenDialingIsTheSameHardStop() = runTest {
        val b = Backends().apply { dials += { DialResult.Failed(NetError.KeyChanged) } }
        val (_, rt) = start(b)
        assertEquals(Problem.KEY_CHANGED, rt.state.value.problem)
        advanceTimeBy(600_000)
        assertEquals(1, b.dialCount)
    }

    @Test fun withoutAKeyItAsksToPairAgain() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.NoKey) }
        val (_, rt) = start(b)
        assertEquals(Stage.ERROR, rt.state.value.stage)
        assertEquals(Problem.NEEDS_PAIRING, rt.state.value.problem)
        advanceTimeBy(600_000)
        assertEquals(1, b.checkCount)
        assertTrue(b.passive.isEmpty())
    }

    @Test fun aPairingWithoutAKeyIsNotEvenTried() = runTest {
        val b = Backends()
        val rt = RelayRuntime()
        val engine = RelayEngine({ ConfigState.Missing(Problem.NEEDS_PAIRING) }, b, rt, RelayLog(null, TestClock(this)), "0.1.0",
            TestClock(this), Random(1))
        backgroundScope.launch { engine.run() }
        runCurrent()
        assertEquals(Problem.NEEDS_PAIRING, rt.state.value.problem)
        assertEquals(0, b.checkCount)
        assertEquals("Error · Pair again", RelayStrings.En.notification(rt.state.value, 0))
    }

    @Test fun withoutTheTunnelItWaitsForItAndGoesOnWhenItIsBack() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.NoTunnel(NoTunnel.Why.NO_VPN)) }
        val (engine, rt) = start(b)
        assertEquals(Stage.WAITING_SERVER, rt.state.value.stage)
        assertEquals(Problem.NO_TUNNEL, rt.state.value.problem)
        assertEquals(ServerSide.NO_TUNNEL, rt.state.value.serverSide)
        assertEquals(Detail.Net(NetError.NoTunnel(NoTunnel.Why.NO_VPN)), rt.state.value.detail)
        assertTrue(b.passive.isEmpty())  // the Flipper is not touched while the server cannot be reached
        assertEquals("Waiting for ${CoreGraph.privateAddresses.name}", RelayStrings.En.notification(rt.state.value, 0))
        b.check = ok
        engine.nudge()  // what RelayService does when the private network changes
        runCurrent()
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    @Test fun anOldServerAsksForAnUpdate() = runTest {
        val b = Backends().apply { check = CheckResult.Outdated }
        val (_, rt) = start(b)
        assertEquals(Problem.SERVER_OUTDATED, rt.state.value.problem)
        advanceTimeBy(59_000)
        assertEquals(1, b.checkCount)
        advanceTimeBy(2_000)
        assertEquals(2, b.checkCount)
    }

    @Test fun anOperatorStopIsPolledWithoutTheFlipper() = runTest {
        val b = Backends().apply { check = CheckResult.Ok(ServerInfo("my-server", "0.1.0"), "stopped", null) }
        val (engine, rt) = start(b)
        assertEquals(Stage.WAITING_SERVER, rt.state.value.stage)
        assertEquals(Problem.OPERATOR_STOPPED, rt.state.value.problem)
        advanceTimeBy(15_500)
        assertEquals(2, b.checkCount)
        assertTrue(b.passive.isEmpty())
        b.check = ok
        engine.nudge() // the app saw the operator resume
        runCurrent()
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    @Test fun ownStaleSessionIsRetriedQuicklyAndOtherRelaysSlowly() = runTest {
        val b = Backends()
        val (_, rt) = start(b)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
        advanceTimeBy(5_000)
        // the network drops; the server still shows our session for a while
        b.check = CheckResult.Ok(ServerInfo("my-server", "0.1.0"), "active", AttachedRelay("0.1.0", "Demo", true, 30.0))
        b.sockets.last().events.trySend(SocketEvent.Failure(NetError.NoRoute))
        runCurrent()
        advanceTimeBy(1_500) // backoff after a short session
        assertEquals(Problem.OWN_STALE, rt.state.value.problem)
        val before = b.checkCount
        advanceTimeBy(3_100)
        assertEquals(before + 1, b.checkCount)
        b.check = CheckResult.Ok(ServerInfo("my-server", "0.1.0"), "active", AttachedRelay(null, null, true, 3000.0))
        advanceTimeBy(3_100)
        assertEquals(Problem.OTHER_RELAY, rt.state.value.problem)
        val n = b.checkCount
        advanceTimeBy(14_000)
        assertEquals(n, b.checkCount)
        advanceTimeBy(1_100)
        assertEquals(n + 1, b.checkCount)
    }

    @Test fun shortSessionsBackOffAndALongOneResetsIt() = runTest {
        val b = Backends()
        val dialTimes = mutableListOf<Long>()
        repeat(5) {
            b.dials += {
                dialTimes += testScheduler.currentTime
                FakeSocket().also { s -> b.sockets += s; s.events.trySend(SocketEvent.Closed(1011, "Flipper reported ERROR_DECODE")) }
                    .let { s -> DialResult.Open(s) }
            }
        }
        val (_, rt) = start(b)
        advanceTimeBy(60_000)
        val gaps = dialTimes.zipWithNext { x, y -> y - x }
        // waits of about 1, 2, 4 and 8 s (±20 %) between sessions that end at once: never a tight loop
        listOf(1_000L, 2_000L, 4_000L, 8_000L).forEachIndexed { i, base ->
            assertTrue("gap $i = ${gaps[i]}", gaps[i] in (base * 8 / 10)..(base * 12 / 10))
        }
        // the sixth session stays up; one that lasts over a minute is followed by an immediate retry
        assertEquals(Stage.RUNNING, rt.state.value.stage)
        advanceTimeBy(61_000)
        val n = b.dialCount
        b.sockets.last().events.trySend(SocketEvent.Closed(1011, "Bridge stopping"))
        runCurrent()
        assertEquals(n + 1, b.dialCount)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
        assertEquals(6, rt.state.value.reconnects)
    }

    @Test fun twoUnreachableAttemptsSwitchToPassiveWaiting() = runTest {
        val b = Backends()
        repeat(2) { b.connects += { throw FlipperException(FlipperProblem.UNREACHABLE, FlipperFailure.NotResponding) } }
        val (_, rt) = start(b)
        advanceTimeBy(5_000)
        assertEquals(listOf(false, false, true), b.passive)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    @Test fun aLockedFlipperIsWaitedForWithoutOpeningPhone() = runTest {
        val b = Backends()
        val link = FakeLink(open = false)
        b.connects += { link }
        val (_, rt) = start(b)
        advanceTimeBy(RelayEngine.UNLOCK_GRACE_MS + 1)
        assertEquals(Stage.FLIPPER_LOCKED, rt.state.value.stage)
        assertFalse(rt.state.value.wakeLock)
        advanceTimeBy(600_000)
        assertEquals(0, b.dialCount)
        link.unlock()
        runCurrent()
        assertEquals(1, b.dialCount)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
        // it locks again mid-session: the session ends, the link is kept and reused after unlock
        link.rpcOpen.value = false
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(Stage.FLIPPER_LOCKED, rt.state.value.stage)
        assertFalse(link.closed)
        link.unlock()
        runCurrent()
        assertEquals(2, b.dialCount)
        assertEquals(1, b.passive.size)
    }

    @Test fun aFlipperThatOpensRpcJustAfterConnectingIsNotCalledLocked() = runTest {
        val b = Backends()
        val link = FakeLink(open = false)
        b.connects += { link }
        val (_, rt) = start(b)
        advanceTimeBy(RelayEngine.UNLOCK_GRACE_MS / 2)
        assertTrue(rt.state.value.stage != Stage.FLIPPER_LOCKED)
        link.unlock()
        runCurrent()
        assertEquals(1, b.dialCount)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    @Test fun aBusyServerKeepsTheFreshLinkForAMinuteAtMost() = runTest {
        val b = Backends()
        repeat(30) { b.dials += { DialResult.Failed(NetError.Timeout) } }
        val (_, rt) = start(b)
        assertEquals(1, b.passive.size)
        advanceTimeBy(30_000)
        assertEquals(1, b.passive.size) // reused while fresh
        advanceTimeBy(90_000)
        assertTrue(b.passive.size >= 2) // dropped after a minute idle, then connected again
        assertTrue(b.links.first().closed)
        assertEquals(Stage.RETRYING, rt.state.value.stage)
    }

    @Test fun afterFiveMinutesOfTroubleItGoesQuiet() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.Timeout) }
        val (_, rt) = start(b)
        assertTrue(rt.state.value.wakeLock)
        advanceTimeBy(6 * 60_000)
        assertFalse(rt.state.value.wakeLock)
        val n = b.checkCount
        advanceTimeBy(4 * 60_000)
        assertEquals(n, b.checkCount)
        advanceTimeBy(61_000)
        assertEquals(n + 1, b.checkCount)
    }

    @Test fun stoppingTheRelayClosesTheLinkItUses() = runTest {
        fun TestScope.launchEngine(b: Backends): Pair<Job, RelayRuntime> {
            val rt = RelayRuntime()
            val engine = RelayEngine({ ConfigState.Ready(config) }, b, rt, RelayLog(null, TestClock(this)), "0.1.0",
                TestClock(this), Random(1))
            val job = backgroundScope.launch { engine.run() }
            runCurrent()
            return job to rt
        }
        // in session
        val running = Backends()
        val (job1, rt1) = launchEngine(running)
        assertEquals(Stage.RUNNING, rt1.state.value.stage)
        job1.cancel()
        runCurrent()
        assertTrue(running.links.single().closed)
        assertEquals(Stage.OFF, rt1.state.value.stage)
        // waiting for the Flipper to be unlocked
        val locked = Backends()
        val link = FakeLink(open = false)
        locked.connects += { link }
        val (job2, rt2) = launchEngine(locked)
        advanceTimeBy(RelayEngine.UNLOCK_GRACE_MS + 1)
        assertEquals(Stage.FLIPPER_LOCKED, rt2.state.value.stage)
        job2.cancel()
        runCurrent()
        assertTrue(link.closed)
    }

    @Test fun lostBondNeedsTheOwner() = runTest {
        val b = Backends()
        b.connects += { throw FlipperException(FlipperProblem.BOND_LOST, FlipperFailure.BondLost(5)) }
        val (engine, rt) = start(b)
        assertEquals(Stage.ERROR, rt.state.value.stage)
        assertEquals(Problem.BOND_LOST, rt.state.value.problem)
        advanceTimeBy(600_000)
        assertEquals(1, b.passive.size)
        engine.nudge() // Android reported a new bond
        runCurrent()
        assertEquals(Stage.RUNNING, rt.state.value.stage)
    }

    // ---------------------------------------------------------------- the remote without a server session (A2)

    private fun messages(link: FakeLink) = link.written.flatMap { RpcReader().feed(it) }

    @Test fun theRemoteOpenWithoutAServerHoldsTheFlipperAndNeverDials() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.Timeout) }
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote)
        assertEquals(Stage.RETRYING, rt.state.value.stage)
        assertTrue(b.passive.isEmpty())
        remote.open(); runCurrent()
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(ServerSide.UNREACHABLE, rt.state.value.serverSide)  // why there is no server session
        assertTrue(rt.state.value.wakeLock)  // as for a session
        val link = b.links.single()
        assertEquals(listOf(Pb.GUI_STOP_SCREEN_STREAM_REQUEST, Pb.GUI_START_SCREEN_STREAM_REQUEST), messages(link).map { it.content })
        b.check = ok  // the server comes back while the remote is open: /phone still waits
        advanceTimeBy(600_000)
        assertEquals(0, b.dialCount)
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        link.received.trySend(Wire.reply(messages(link).first().id)); runCurrent()
        assertEquals(RemoteKeys.Ready, remote.keys.value)
        // closing: the relay stops its own stream and gives the link back to its cycle, which opens a session on it
        remote.close()
        advanceTimeBy(RelayEngine.REMOTE_CLOSE_GRACE_MS + 1)
        assertEquals(Pb.GUI_STOP_SCREEN_STREAM_REQUEST, messages(link).last().content)
        assertEquals(1, b.dialCount)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
        assertEquals(1, b.passive.size)  // the same link
        assertFalse(link.closed)
    }

    @Test fun withoutAPairingButWithAFlipperChosenTheRemoteStillWorks() = runTest {
        val b = Backends()
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote, load = { ConfigState.Missing(Problem.NOT_CONFIGURED, config.flipper) })
        assertEquals(Problem.NOT_CONFIGURED, rt.state.value.problem)
        remote.open(); runCurrent()
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(0, b.checkCount)
        remote.close()
        advanceTimeBy(RelayEngine.REMOTE_CLOSE_GRACE_MS + 1)
        assertEquals(Problem.NOT_CONFIGURED, rt.state.value.problem)
        assertTrue(b.links.single().closed)  // no server to keep it for
    }

    @Test fun withoutAFlipperChosenTheRemoteHasNothingToHold() = runTest {
        val b = Backends()
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote, load = { ConfigState.Missing(Problem.NOT_CHOSEN) })
        remote.open(); runCurrent()
        assertEquals(Problem.NOT_CHOSEN, rt.state.value.problem)
        assertTrue(b.passive.isEmpty())
    }

    @Test fun aStoppedGateOrAnotherRelayLeavesTheFlipperToTheRemote() = runTest {
        val b = Backends().apply { check = CheckResult.Ok(ServerInfo("my-server", "0.1.0"), "stopped", null) }
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote)
        assertEquals(Problem.OPERATOR_STOPPED, rt.state.value.problem)
        remote.open(); runCurrent()
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(ServerSide.STOPPED, rt.state.value.serverSide)
        assertEquals(0, b.dialCount)
    }

    @Test fun aServerSessionGoesOnWhenTheRemoteOpensAndTheRemoteTakesOverWhenItEnds() = runTest {
        val b = Backends()
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote)
        assertEquals(Stage.RUNNING, rt.state.value.stage)
        remote.open(); runCurrent()
        assertEquals(Stage.RUNNING, rt.state.value.stage)  // A1: the stream is the server's, the gate pauses the agent
        assertEquals(1, b.dialCount)
        b.check = CheckResult.Unreachable(NetError.Timeout)
        b.sockets.single().events.trySend(SocketEvent.Failure(NetError.NoRoute)); runCurrent()
        advanceTimeBy(2_000)
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(1, b.dialCount)
    }

    @Test fun aRemoteOpenedWhileTheFlipperConnectsDoesNotDial() = runTest {
        val b = Backends()
        val remote = RelayRemote()
        val link = FakeLink(open = false)
        b.connects += { link }
        val (_, rt) = start(b, remote = remote)
        remote.open(); runCurrent()  // the link is waiting for the Flipper's RPC session
        link.unlock(); runCurrent()
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(0, b.dialCount)
    }

    @Test fun aRotationDoesNotEndTheRemotesHold() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.Timeout) }
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote)
        remote.open(); runCurrent()
        val writes = b.links.single().written.size
        remote.close(); remote.open()  // the screen is built again
        advanceTimeBy(RelayEngine.REMOTE_CLOSE_GRACE_MS * 3)
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(writes, b.links.single().written.size)
    }

    @Test fun aLinkWhoseStreamCouldNotBeReadIsNotKept() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.Timeout) }
        val remote = RelayRemote()
        val (_, _) = start(b, remote = remote)
        remote.open(); runCurrent()
        val link = b.links.single()
        link.received.trySend(Wire.reply(messages(link).first().id) + byteArrayOf(0, 0, 0)); runCurrent()
        assertEquals(RemoteKeys.Off(KeysOff.READER_STOPPED), remote.keys.value)
        remote.close()
        advanceTimeBy(RelayEngine.REMOTE_CLOSE_GRACE_MS + 1)
        assertTrue(link.closed)
    }

    @Test fun aLockWhileTheRemoteIsOpenWaitsAndStartsOverOnTheNewRpcSession() = runTest {
        val b = Backends().apply { check = CheckResult.Unreachable(NetError.Timeout) }
        val remote = RelayRemote()
        val (_, rt) = start(b, remote = remote)
        remote.open(); runCurrent()
        val link = b.links.single()
        link.received.trySend(Wire.reply(messages(link).first().id)); runCurrent()
        assertEquals(RemoteKeys.Ready, remote.keys.value)
        link.rpcOpen.value = false; runCurrent()
        assertEquals(Stage.FLIPPER_LOCKED, rt.state.value.stage)
        assertEquals(RemoteKeys.Off(KeysOff.NO_SESSION), remote.keys.value)
        val before = link.written.size
        link.unlock(); runCurrent()
        assertEquals(Stage.REMOTE, rt.state.value.stage)
        assertEquals(RemoteKeys.Off(KeysOff.SYNCING), remote.keys.value)  // a new reader, synced on a new stop
        assertEquals(Pb.GUI_STOP_SCREEN_STREAM_REQUEST, RpcReader().feed(link.written[before]).single().content)
        assertEquals(0, b.dialCount)
    }
}
