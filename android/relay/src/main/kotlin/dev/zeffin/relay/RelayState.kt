// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the relay is in its cycle. The screens turn it into words. [REMOTE]: the remote is open without a server session,
 * and the relay holds the Flipper for it alone; `serverSide` still says why there is no
 * server session.
 */
enum class Stage {
    OFF, CHECKING_SERVER, WAITING_SERVER, CONNECTING_FLIPPER, WAITING_FLIPPER, FLIPPER_LOCKED,
    OPENING_SESSION, RUNNING, REMOTE, RETRYING, ERROR,
}

/** Why the relay waits or stopped. */
enum class Problem {
    NOT_CONFIGURED,
    NOT_CHOSEN,
    TOKEN_REJECTED,
    SERVER_OUTDATED,
    SERVER_UNREACHABLE,
    /** The private network's tunnel does not reach the server: nothing was sent. */
    NO_TUNNEL,
    /** The server showed another key than the pairing's: nothing was sent, and only a new pairing goes on. */
    KEY_CHANGED,
    /** The pairing is from before the server's key: nothing is sent until the owner pairs again. */
    NEEDS_PAIRING,
    OPERATOR_STOPPED,
    OTHER_RELAY,
    OWN_STALE,
    BLUETOOTH_OFF,
    NO_PERMISSION,
    NOT_BONDED,
    BOND_LOST,
    FLIPPER_UNREACHABLE,
    NOT_A_FLIPPER,
    FLIPPER_DROPPED,
    SESSION_ENDED,
}

enum class ServerSide { NOT_TRIED, CHECKING, READY, IN_SESSION, REJECTED, OUTDATED, UNREACHABLE, NO_TUNNEL, KEY_CHANGED, STOPPED, BUSY }

enum class FlipperSide { NOT_TRIED, CONNECTING, SECURING, MTU, SERVICES, SUBSCRIBING, WAITING, LOCKED, CONNECTED, FAILED }

data class ServerInfo(val name: String?, val version: String?)

data class RelayState(
    val stage: Stage = Stage.OFF,
    val problem: Problem? = null,
    /** Extra detail for the owner: HTTP code, network or Flipper failure, the other relay's version. Never a token. */
    val detail: Detail? = null,
    val server: ServerInfo? = null,
    val serverSide: ServerSide = ServerSide.NOT_TRIED,
    val flipperSide: FlipperSide = FlipperSide.NOT_TRIED,
    val deviceName: String? = null,
    val mtu: Int? = null,
    /** Wall clock, ms. */
    val startedAt: Long? = null,
    val sessionStartedAt: Long? = null,
    val waitingSince: Long? = null,
    val nextRetryAt: Long? = null,
    val stoppedAt: Long? = null,
    val lastSessionMs: Long? = null,
    val reconnects: Int = 0,
    val attempt: Int = 0,
    val bytesFromFlipper: Long = 0,
    val bytesToFlipper: Long = 0,
    val wakeLock: Boolean = false,
) {
    val running get() = stage != Stage.OFF
    val inSession get() = stage == Stage.RUNNING
}

/** The relay's single state for the process: the service writes it, the screens read it. */
class RelayRuntime {
    private val _state = MutableStateFlow(RelayState())
    val state: StateFlow<RelayState> = _state.asStateFlow()

    fun update(change: (RelayState) -> RelayState) = _state.update(change)
}

/** Wall clock and a monotonic clock, both in milliseconds; tests use virtual time. */
interface Clock {
    fun now(): Long
    fun elapsed(): Long

    object System : Clock {
        override fun now() = java.lang.System.currentTimeMillis()
        override fun elapsed() = java.lang.System.nanoTime() / 1_000_000
    }
}

/** Whether the phone's Bluetooth can be used: on, off, without the nearby devices permission, or absent. */
enum class RadioState { ON, OFF, NO_PERMISSION, MISSING }

/** A device paired with the phone, as Settings, Choose the Flipper lists it. */
data class BondedDevice(val name: String, val address: String, val looksLikeFlipper: Boolean)
