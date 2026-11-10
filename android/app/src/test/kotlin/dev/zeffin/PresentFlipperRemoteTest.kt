// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.Fixtures as F
import dev.zeffin.core.Lang
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.Conn
import dev.zeffin.relay.FlipperKey
import dev.zeffin.relay.KeyRefusal
import dev.zeffin.relay.KeysOff
import dev.zeffin.relay.Problem
import dev.zeffin.relay.RelayState
import dev.zeffin.relay.RemoteKeys
import dev.zeffin.relay.ServerSide
import dev.zeffin.relay.Stage
import dev.zeffin.ui.present.Face
import dev.zeffin.ui.present.PresentFlipperRemote
import dev.zeffin.ui.present.RemoteAction
import dev.zeffin.ui.present.RemoteAgent
import dev.zeffin.ui.present.RemoteGate
import dev.zeffin.ui.present.RemoteInputs
import dev.zeffin.ui.present.ScreenMode
import dev.zeffin.ui.present.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote's gate flow and what it says in both languages. */
class PresentFlipperRemoteTest {
    private val words = PresentFlipperRemote(Lang.EN)
    private val t0 = 1_000_000L

    private fun link(gate: String, by: String? = null, busy: Boolean = false, conn: Conn = Conn.CONNECTED): AppLinkState =
        F.link.copy(conn = conn, status = F.status.copy(gate = gate, gateBy = by, busy = busy))

    private fun inputs(link: AppLinkState, gate: RemoteGate) =
        RemoteInputs(F.relayRunning, link, gate, RemoteKeys.Ready, null, showRefusal = false, hasFrame = true, nowMs = t0 + 1_000)

    private fun ui(link: AppLinkState, gate: RemoteGate, keys: RemoteKeys = RemoteKeys.Ready, refused: KeyRefusal? = null,
                   now: Long = t0 + 1_000) =
        words.ui(inputs(link, gate).copy(keys = keys, refused = refused, showRefusal = refused != null, nowMs = now))

    @Test fun anActiveAgentIsPausedOnceByTheRemoteAndTheKeysWaitForPaused() {
        val (g1, send1) = RemoteGate().next(link("active"), t0)
        assertTrue(send1)
        val (g2, send2) = g1.next(link("active"), t0 + 500)  // the status has not changed yet: nothing more is sent
        assertFalse(send2)
        assertEquals(RemoteAgent.PAUSING, ui(link("active"), g2).agent)
        assertFalse(ui(link("active"), g2).keysOn)
        val (g3, _) = g2.next(link("paused", "remote"), t0 + 800)
        val paused = ui(link("paused", "remote"), g3)
        assertEquals(RemoteAgent.PAUSED_BY_REMOTE, paused.agent)
        assertTrue(paused.keysOn)
        assertEquals("Paused by the remote. The agent cannot touch the Flipper.", paused.lines.single().text)
        assertTrue(g3.offersResume(link("paused", "remote")))
    }

    @Test fun anAgentAlreadyPausedIsLeftAsItIsAndLeavingOffersNothing() {
        val (g, send) = RemoteGate().next(link("paused", "app"), t0)
        assertFalse(send)
        val u = ui(link("paused", "app"), g)
        assertEquals(RemoteAgent.PAUSED, u.agent)
        assertTrue(u.keysOn)
        assertFalse(g.offersResume(link("paused", "app")))
    }

    @Test fun withoutAppTheKeysStayOffWithRetry() {
        val (g, send) = RemoteGate().next(link("active", conn = Conn.OFFLINE), t0)
        assertFalse(send)  // nothing can be sent
        val u = ui(link("active", conn = Conn.OFFLINE), g)
        assertEquals(RemoteAgent.CANNOT_PAUSE, u.agent)
        assertFalse(u.keysOn)
        assertEquals(RemoteAction.RETRY, u.action)
        assertEquals(Tone.DANGER, u.lines.single().tone)
    }

    @Test fun aPauseNotSeenInFiveSecondsSaysSoWithRetry() {
        val (g, _) = RemoteGate().next(link("active"), t0)
        assertEquals(RemoteAgent.PAUSING, ui(link("active"), g, now = t0 + 4_999).agent)
        val late = ui(link("active"), g, now = t0 + 5_000)
        assertEquals(RemoteAgent.NOT_PAUSED, late.agent)
        assertEquals(RemoteAction.RETRY, late.action)
        assertTrue(g.pauseAgain(t0 + 6_000).let { ui(link("active"), it, now = t0 + 7_000).agent == RemoteAgent.PAUSING })
    }

    @Test fun resumedElsewhereTheKeysGoOffAndPauseIsOffered() {
        var g = RemoteGate().next(link("active"), t0).first
        g = g.next(link("paused", "remote"), t0 + 500).first
        g = g.next(link("active", "view"), t0 + 9_000).first
        val u = ui(link("active", "view"), g)
        assertEquals(RemoteAgent.RESUMED, u.agent)
        assertFalse(u.keysOn)
        assertEquals(RemoteAction.PAUSE, u.action)
        assertFalse(g.offersResume(link("active", "view")))
    }

    @Test fun aStaleStoppedWithTheRelayRunningKeepsTheKeysOffAndPausesOnceAppIsBack() {
        // The agent was stopped, /app went offline, the agent was resumed elsewhere and the relay reached RUNNING.
        val offline = link("stopped", conn = Conn.OFFLINE)
        val (g1, send1) = RemoteGate().next(offline, t0)
        assertFalse(send1)
        val u = ui(offline, g1)
        assertEquals(RemoteAgent.CANNOT_PAUSE, u.agent)  // as without /app: the kept "stopped" is not read
        assertFalse(u.keysOn)
        assertEquals(RemoteAction.RETRY, u.action)
        // /app is back; its first status may still be the kept one, then the server's own
        val (g2, send2) = g1.next(link("stopped"), t0 + 100)
        assertFalse(send2)
        val (g3, send3) = g2.next(link("active"), t0 + 200)
        assertTrue(send3)  // one pause, now that it can be sent
        assertEquals(RemoteAgent.PAUSING, ui(link("active"), g3, now = t0 + 300).agent)
        assertFalse(ui(link("active"), g3, now = t0 + 300).keysOn)
        assertFalse(g3.next(link("active"), t0 + 400).second)  // only one
        val (g4, _) = g3.next(link("paused", "remote"), t0 + 600)
        assertEquals(RemoteAgent.PAUSED_BY_REMOTE, ui(link("paused", "remote"), g4).agent)
        assertTrue(ui(link("paused", "remote"), g4).keysOn)
    }

    @Test fun aStaleStoppedWithoutAServerSessionLeavesTheKeysOn() {
        val u = local(link = link("stopped", conn = Conn.OFFLINE))
        assertEquals(RemoteAgent.NO_SESSION, u.agent)
        assertTrue(u.keysOn)
    }

    @Test fun aStoppedAgentResumedWhileTheRemoteIsOpenIsPausedNeverPausingForever() {
        val (g1, send1) = RemoteGate().next(link("stopped"), t0)
        assertFalse(send1)  // nothing to pause
        val (g2, send2) = g1.next(link("active"), t0 + 1_000)
        assertTrue(send2)
        val late = ui(link("active"), g2, now = t0 + 1_000 + RemoteGate.PAUSE_WAIT_MS)
        assertEquals(RemoteAgent.NOT_PAUSED, late.agent)  // not "Pausing…" for good
        assertEquals(RemoteAction.RETRY, late.action)
    }

    // ---- without a server session (A2): the relay holds the Flipper for the remote, and the keys work at once

    private val relayRemote = F.relayRunning.copy(stage = Stage.REMOTE, serverSide = ServerSide.UNREACHABLE, sessionStartedAt = null)

    private fun local(relay: RelayState = relayRemote, link: AppLinkState = F.link.copy(conn = Conn.OFFLINE, status = null),
                      keys: RemoteKeys = RemoteKeys.Ready, paired: Boolean = true, hasFrame: Boolean = true) =
        words.ui(RemoteInputs(relay, link, RemoteGate(), keys, null, showRefusal = false, hasFrame = hasFrame, nowMs = t0,
            paired = paired))

    @Test fun withoutAServerSessionTheKeysWorkAndTheRemoteSaysWhyTheAgentCannotAct() {
        val u = local()
        assertEquals(RemoteAgent.NO_SESSION, u.agent)
        assertTrue(u.keysOn)
        assertEquals(ScreenMode.LIVE, u.screenMode)
        assertNull(u.action)
        assertEquals("The agent cannot act now: no connection with the server.", u.lines.single().text)
        assertEquals(Tone.NEUTRAL, u.lines.single().tone)
        assertEquals("Demo · connected over BLE · no server session", u.subtitle)
    }

    @Test fun theReasonFollowsWhatKeptTheServerAway() {
        fun why(relay: RelayState = relayRemote, link: AppLinkState = F.link.copy(conn = Conn.OFFLINE, status = null), paired: Boolean = true) =
            local(relay, link, paired = paired).lines.first().text.removePrefix("The agent cannot act now: ")
        assertEquals("it is stopped.", why(relayRemote.copy(serverSide = ServerSide.STOPPED)))
        assertEquals("it is stopped.", why(link = link("stopped")))
        assertEquals("the app is not paired with a server.", why(paired = false))
        assertEquals("pair again with the server.", why(relayRemote.copy(serverSide = ServerSide.KEY_CHANGED)))
        assertEquals("another relay serves the server.", why(relayRemote.copy(serverSide = ServerSide.BUSY)))
        assertEquals("the server needs an update (zf update).", why(relayRemote.copy(serverSide = ServerSide.OUTDATED)))
        assertTrue(why(relayRemote.copy(serverSide = ServerSide.NO_TUNNEL)).endsWith("is not connected on this phone."))
        assertEquals("the relay is not connected to the server.", why(link = link("active")))
    }

    @Test fun whileTheRelayGetsTheFlipperTheKeysWait() {
        val connecting = local(F.relayConnecting.copy(serverSide = ServerSide.UNREACHABLE), keys = RemoteKeys.Off(KeysOff.NO_SESSION),
            hasFrame = false)
        assertFalse(connecting.keysOn)
        assertEquals(ScreenMode.OFF, connecting.screenMode)
        assertEquals("Connecting to the Flipper…", connecting.lines[1].text)
        val syncing = local(keys = RemoteKeys.Off(KeysOff.SYNCING), hasFrame = false)
        assertEquals("Waiting for the Flipper's screen.", syncing.lines[1].text)
        val locked = local(F.relayLocked, keys = RemoteKeys.Off(KeysOff.NO_SESSION))
        assertEquals("The Flipper is locked: unlock it to use the remote.", locked.lines[1].text)
        val unreachable = local(F.relayError.copy(problem = Problem.FLIPPER_UNREACHABLE), keys = RemoteKeys.Off(KeysOff.NO_SESSION))
        assertEquals(Tone.DANGER, unreachable.lines[1].tone)
        val stopped = local(keys = RemoteKeys.Off(KeysOff.READER_STOPPED))
        assertEquals("The relay could not read the Flipper's stream: leave the remote and open it again.", stopped.lines[1].text)
    }

    @Test fun withTheRelayOffTheRemoteOffersToStartIt() {
        val u = local(F.relayStopped, keys = RemoteKeys.Off(KeysOff.NO_SESSION), hasFrame = false)
        assertEquals(RemoteAgent.RELAY_OFF, u.agent)
        assertFalse(u.keysOn)
        assertEquals(RemoteAction.START_RELAY, u.action)
        assertEquals("Start the relay", u.actionLabel)
        assertEquals("The relay is off: start it to use the remote.", u.lines.single().text)
        assertEquals("Demo · relay off", u.subtitle)
    }

    @Test fun withoutAServerSessionSpanishToo() {
        val es = PresentFlipperRemote(Lang.ES).ui(RemoteInputs(relayRemote, F.link.copy(conn = Conn.OFFLINE, status = null), RemoteGate(),
            RemoteKeys.Ready, null, showRefusal = false, hasFrame = true, nowMs = t0))
        assertEquals("El agente no puede actuar ahora: sin conexión con el servidor.", es.lines.single().text)
        assertEquals("Demo · conectado por BLE · sin servidor", es.subtitle)
    }

    @Test fun theHoldStatesHaveWords() {
        val g = RemoteGate(started = true, sentAt = t0, pausedByRemote = true)
        val transfer = ui(link("paused", "remote"), g, keys = RemoteKeys.Off(KeysOff.SERVER_COMMAND_OPEN))
        assertFalse(transfer.keysOn)
        assertEquals("Pausing: finishing the agent's transfer. The keys come back when it ends.", transfer.lines.single().text)
        val busy = ui(link("paused", "remote", busy = true), g, keys = RemoteKeys.Off(KeysOff.WAITING_FLIPPER))
        assertFalse(busy.keysOn)
        assertEquals("Waiting for the Flipper: the agent's last call is still running.", busy.lines[1].text)
        val quiet = ui(link("paused", "remote"), g, keys = RemoteKeys.Off(KeysOff.WAITING_FLIPPER))
        assertEquals("Waiting for the Flipper to answer.", quiet.lines[1].text)
    }

    @Test fun aRefusedKeyShowsItsStatusNameAsDataNeverRetried() {
        val g = RemoteGate(started = true, sentAt = t0, pausedByRemote = true)
        val u = ui(link("paused", "remote"), g, refused = KeyRefusal(FlipperKey.OK, false, 17, "ERROR_APP_SYSTEM_LOCKED", 1))
        assertTrue(u.keysOn)
        assertEquals("OK refused by the Flipper:", u.lines[1].text)
        assertEquals("ERROR_APP_SYSTEM_LOCKED", u.lines[1].code)
        val unknown = ui(link("paused", "remote"), g, refused = KeyRefusal(FlipperKey.BACK, true, 99, null, 2))
        assertEquals("Long Back refused by the Flipper:", unknown.lines[1].text)
        assertEquals("99", unknown.lines[1].code)  // a status without a name: its number, never device text
    }

    @Test fun spanishToo() {
        val es = PresentFlipperRemote(Lang.ES)
        val g = RemoteGate(started = true, sentAt = t0, pausedByRemote = true)
        val u = es.ui(inputs(link("paused", "remote"), g))
        assertEquals("Mando", u.title)
        assertEquals("Pausado por el mando. El agente no puede tocar el Flipper.", u.lines.single().text)
        assertEquals("Demo · conectado por BLE · agente pausado", u.subtitle)
        assertEquals("¿Reanudar el agente?", es.leaveTitle)
    }

    /** The character in the state space: asleep while paused, looking for a signal while it waits. */
    @Test fun theCharactersFace() {
        val (g1, _) = RemoteGate().next(link("active"), t0)
        assertEquals("pausing", Face.CONNECTING, ui(link("active"), g1).face)
        val (g2, _) = g1.next(link("paused", "remote"), t0 + 800)
        assertEquals(Face.PAUSED, ui(link("paused", "remote"), g2).face)
        assertEquals("the keys wait for the Flipper", Face.CONNECTING,
            ui(link("paused", "remote"), g2, keys = RemoteKeys.Off(KeysOff.WAITING_FLIPPER)).face)
        assertEquals("a refused key", Face.ERROR,
            ui(link("paused", "remote"), g2, refused = KeyRefusal(FlipperKey.OK, false, 17, "ERROR_APP_SYSTEM_LOCKED", 1)).face)
        assertEquals("no /app", Face.OFFLINE, ui(link("active", conn = Conn.OFFLINE), RemoteGate()).face)
        assertEquals("the relay off", Face.OFFLINE,
            words.ui(inputs(link("paused", "remote"), g2).copy(relay = F.relayStopped)).face)
    }
}
