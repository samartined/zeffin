// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * One connection to the Flipper, which is one Flipper RPC session: the firmware opens the
 * session when Bluetooth connects and closes it when it disconnects (or when it locks).
 */
interface FlipperLink {
    /** The Flipper's name, already cleaned (it is untrusted). */
    val name: String
    val mtu: Int
    /** Bytes from the Flipper, in order, buffered until the session reads them. */
    val received: ReceiveChannel<ByteArray>
    /** False while the Flipper has no RPC session (announced credit 0: usually locked with a PIN). */
    val rpcOpen: StateFlow<Boolean>
    /** Grows each time the Flipper opens a new RPC session on this same link (after an unlock). */
    val rpcGeneration: Int
    /** Completes with a reason when the link is gone. */
    val ended: Deferred<FlipperException>
    val alive: Boolean get() = !ended.isCompleted

    /** Writes the whole frame, respecting the Flipper's flow control. Throws FlipperException. */
    suspend fun write(data: ByteArray)

    /** Disconnects and waits (bounded) until Android confirms it. */
    suspend fun close()
}

enum class FlipperStep { CONNECTING, WAITING, SECURING, MTU, SERVICES, SUBSCRIBING }

fun interface FlipperConnector {
    /**
     * Connects to the chosen Flipper and returns once it is subscribed and its credit is known.
     * passive = wait for the Flipper to appear, without a time limit.
     */
    suspend fun connect(passive: Boolean, step: (FlipperStep) -> Unit): FlipperLink
}

enum class FlipperProblem(val problem: Problem) {
    NOT_CHOSEN(Problem.NOT_CHOSEN),
    BLUETOOTH_OFF(Problem.BLUETOOTH_OFF),
    NO_PERMISSION(Problem.NO_PERMISSION),
    NOT_BONDED(Problem.NOT_BONDED),
    BOND_LOST(Problem.BOND_LOST),
    UNREACHABLE(Problem.FLIPPER_UNREACHABLE),
    NOT_A_FLIPPER(Problem.NOT_A_FLIPPER),
    DROPPED(Problem.FLIPPER_DROPPED),
    STALLED(Problem.FLIPPER_DROPPED),
    CLOSED(Problem.FLIPPER_DROPPED),
}

/** [failure] says what happened; the message is the English wording, for Android's log and tests. */
class FlipperException(val kind: FlipperProblem, val failure: FlipperFailure) : Exception(RelayStrings.En.flipper(failure))
