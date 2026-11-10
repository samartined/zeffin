// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.Lang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayNotificationTest {
    private val en = RelayStrings.of(Lang.EN)

    @Test fun theTextChangesWhenItsShownSecondOrMinuteDoes() {
        val running = RelayState(stage = Stage.RUNNING, sessionStartedAt = 0)
        assertEquals(1_000L, en.untilTextChanges(running, 0))
        assertEquals(300L, en.untilTextChanges(running, 59_700))  // 59 s -> 1 min
        assertEquals(60_000L, en.untilTextChanges(running, 60_000))
        assertEquals(1_000L, en.untilTextChanges(running, 3_659_000))  // 1 h 0 min -> 1 h 1 min
        val retrying = RelayState(stage = Stage.RETRYING, nextRetryAt = 90_000)
        assertEquals(30_001L, en.untilTextChanges(retrying, 0))  // "1 min" until 59.999 s are left
        assertEquals(501L, en.untilTextChanges(retrying, 80_500))  // 9.5 s left: "9 s" until 8.999 s
        assertNull(en.untilTextChanges(retrying, 90_000))
        assertNull(en.untilTextChanges(RelayState(stage = Stage.WAITING_FLIPPER), 0))
    }

    @Test fun theNotificationFollowsTheStateAndTheLanguageWithoutPolling() = runTest {
        val states = MutableStateFlow(RelayState(stage = Stage.WAITING_FLIPPER))
        val langs = MutableStateFlow(Lang.EN)
        val shown = mutableListOf<String>()
        val job = launch {
            followNotification(states, langs, { testScheduler.currentTime }) { s, strings, now ->
                shown += strings.notification(s, now)
            }
        }
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(1, shown.size)  // a waiting relay's text never changes by itself
        langs.value = Lang.ES
        runCurrent()
        assertEquals(2, shown.size)
        states.value = RelayState(stage = Stage.RUNNING, deviceName = "Demo", sessionStartedAt = testScheduler.currentTime)
        runCurrent()
        advanceTimeBy(2_500)
        assertEquals(listOf("En marcha · Demo · 0 s", "En marcha · Demo · 1 s", "En marcha · Demo · 2 s"), shown.drop(2))
        job.cancel()
    }
}
