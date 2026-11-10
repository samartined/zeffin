// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.Fixtures as F
import dev.zeffin.core.Lang
import dev.zeffin.link.AppLink
import dev.zeffin.link.Frame
import dev.zeffin.link.ServerStatus
import dev.zeffin.ui.present.Present
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home times a live screen from when its frame reached the app, since the server may send its status only when
 * something changes; a stale or missing frame keeps the server's age.
 */
class FrameAgeTest {
    private val present = Present(Lang.EN)
    private val data = ByteArray(AppLink.FRAME_BYTES)
    private val status = ServerStatus(connected = true, screenAgeS = 9.0)

    @Test fun theLinkRecordsWhenAFrameArrived() {
        val link = AppLink(testHttp(), clock = { 1_000_000L })
        link.handle("""{"type":"frame","seq":418,"orientation":0,"stale":false,"data":"${java.util.Base64.getEncoder().encodeToString(data)}"}""")
        assertEquals(1_000_000L, link.frame.value!!.receivedAt)
    }

    @Test fun aLiveScreenIsTimedFromItsArrival() {
        val frame = Frame(418, 0, false, data, receivedAt = 1_000_000L)
        assertEquals(400L, present.frameAgeMs(frame, status, stale = false, nowMs = 1_000_400L))
        assertEquals(0L, present.frameAgeMs(frame, status, stale = false, nowMs = 999_000L))  // a clock that went back
    }

    @Test fun homeSaysHowOldTheLiveScreenIs() {
        // The status says 0.4 s, but it may be seconds old; the frame reached the app 3 s ago.
        val ui = present.homeUi(F.relayRunning, F.link, F.frame.copy(receivedAt = F.now - 3_000), F.settings, F.pairing, F.now)
        assertTrue(ui.subtitle, ui.subtitle.endsWith(" · #418 · 3 s ago"))
    }

    @Test fun aStaleScreenKeepsTheServersAge() {
        val frame = Frame(418, 0, true, data, receivedAt = 1_000_000L)
        assertEquals(9_000L, present.frameAgeMs(frame, status, stale = true, nowMs = 1_000_400L))
    }

    @Test fun withoutAnArrivalTimeTheServersAgeIsUsed() {
        assertEquals(9_000L, present.frameAgeMs(Frame(418, 0, false, data), status, stale = false, nowMs = 1_000_400L))
        assertEquals(9_000L, present.frameAgeMs(null, status, stale = false, nowMs = 1_000_400L))
        assertEquals(0L, present.frameAgeMs(null, null, stale = false, nowMs = 1_000_400L))
    }
}
