// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.Fixtures as F
import dev.zeffin.core.Lang
import dev.zeffin.link.Conn
import dev.zeffin.relay.RelayFrame
import dev.zeffin.relay.ServerSide
import dev.zeffin.relay.Stage
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.ScreenMode
import dev.zeffin.ui.present.ScreenSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home and the remote draw the relay's frames whenever the relay holds the link, with or without a server, and `/app`'s
 * only when it does not. The stale indicator and the age follow the source drawn.
 */
class ScreenSourceTest {
    private val present = Present(Lang.EN)
    private val relayFrame = RelayFrame(F.frameData, 2, seq = 37, receivedAt = F.now - 3_000)
    private val appFrame = F.frame.copy(receivedAt = F.now - 4_000)
    private val remoteHold = F.relayRunning.copy(stage = Stage.REMOTE, serverSide = ServerSide.UNREACHABLE, sessionStartedAt = null)

    @Test fun inASessionTheRelaysFramesAreDrawnNotTheServers() {
        val shown = ScreenSource.pick(F.relayRunning, relayFrame, appFrame)
        assertTrue(shown.fromRelay)
        assertSame(F.frameData, shown.frame!!.data)
        assertEquals(37L, shown.frame!!.seq)
        assertEquals(2, shown.frame!!.orientation)
        assertEquals(F.now - 3_000, shown.frame!!.receivedAt)
        assertFalse(shown.frame!!.stale)
    }

    @Test fun withoutAServerSessionTheRelaysFramesOrNothing() {
        assertTrue(ScreenSource.pick(remoteHold, relayFrame, appFrame).fromRelay)
        val syncing = ScreenSource.pick(remoteHold, null, appFrame)  // the server's frames are not this link's
        assertTrue(syncing.fromRelay)
        assertNull(syncing.frame)
    }

    @Test fun whenTheRelayDoesNotHoldTheLinkTheServersFrames() {
        for (relay in listOf(F.relayStopped, F.relayConnecting, F.relayError)) {
            val shown = ScreenSource.pick(relay, null, appFrame)
            assertFalse(shown.fromRelay)
            assertSame(appFrame, shown.frame)
        }
        // in a session whose reader has no frame yet (it syncs on the server's settle), the server's
        val early = ScreenSource.pick(F.relayRunning, null, appFrame)
        assertFalse(early.fromRelay)
        assertSame(appFrame, early.frame)
    }

    @Test fun homesAgeAndNumberFollowTheRelaysFrame() {
        val shown = ScreenSource.pick(F.relayRunning, relayFrame, appFrame)
        val ui = present.homeUi(F.relayRunning, F.link, shown.frame, F.settings, F.pairing, F.now)
        assertTrue(ui.subtitle, ui.subtitle.endsWith(" · #37 · 3 s ago"))
        assertEquals(ScreenMode.LIVE, ui.screenMode)
    }

    @Test fun theServersStaleFlagDoesNotDimTheRelaysLiveFrame() {
        val staleServer = F.link.copy(status = F.status.copy(screenStale = true, screenAgeS = 30.0))
        val shown = ScreenSource.pick(F.relayRunning, relayFrame, appFrame)
        val ui = present.homeUi(F.relayRunning, staleServer, shown.frame, F.settings, F.pairing, F.now)
        assertEquals(ScreenMode.LIVE, ui.screenMode)
        assertNull(ui.badge)
        // the same status with the server's own frames: stale, from the server's age
        val app = present.homeUi(F.relayRunning, staleServer, appFrame.copy(stale = true), F.settings, F.pairing, F.now)
        assertEquals(ScreenMode.STALE, app.screenMode)
    }

    @Test fun theRelaysFrameIsDrawnEvenWithoutApp() {
        val offline = F.link.copy(conn = Conn.OFFLINE)
        val shown = ScreenSource.pick(F.relayRunning, relayFrame, null)
        assertEquals(ScreenMode.LIVE, present.homeUi(F.relayRunning, offline, shown.frame, F.settings, F.pairing, F.now).screenMode)
        assertEquals(ScreenMode.OFF, present.homeUi(F.relayRunning, offline, appFrame, F.settings, F.pairing, F.now).screenMode)
    }

    @Test fun homeOffersTheRemoteWhenOfflineWithAFlipperChosen() {
        val offline = F.link.copy(conn = Conn.OFFLINE)
        assertTrue(present.homeUi(F.relayError, offline, null, F.settings, F.pairing, F.now).offersRemote)
        assertTrue(present.homeUi(F.relayStopped, F.link.copy(status = F.status.copy(connected = false)), null, F.settings, F.pairing, F.now).offersRemote)
        assertTrue(present.homeUi(F.relayError, F.link.copy(conn = Conn.NOT_CONFIGURED, status = null), null, F.settings, null, F.now).offersRemote)
        assertFalse(present.homeUi(F.relayError, offline, null, F.settings.copy(flipper = null), F.pairing, F.now).offersRemote)
        assertFalse(present.homeUi(F.relayRunning, F.link, F.frame, F.settings, F.pairing, F.now).offersRemote)  // the gate row has it
        assertEquals("Use the remote", present.useTheRemote)
        assertEquals("Usar el mando", Present(Lang.ES).useTheRemote)
    }
}
