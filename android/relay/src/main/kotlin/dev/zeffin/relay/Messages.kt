// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

/**
 * What went wrong, as data. The screens and the notification put it into words in the user's
 * language ([RelayStrings]); close reasons sent to the server always use English.
 */

/** A network failure, without the address or any header. */
sealed interface NetError {
    data object Closed : NetError
    data object Refused : NetError
    data object Timeout : NetError
    data object UnknownHost : NetError
    data object NoRoute : NetError
    /** Refused before sending anything: the private network's tunnel does not reach the server (and why, for the details). */
    data class NoTunnel(val why: dev.zeffin.core.NoTunnel.Why) : NetError
    data object Tls : NetError
    /** The server showed another key than the pairing's: nothing was sent. */
    data object KeyChanged : NetError
    /** No pinned key for this server (a pairing from before the server's key was pinned): nothing was sent. */
    data object NoKey : NetError
    data object Eof : NetError
    data class Http(val code: Int) : NetError
    data object BadResponse : NetError
    /** Anything else: the exception's own (technical, untranslated) message. */
    data class Other(val raw: String) : NetError
}

/** The GATT operation that failed, for the log and the banners. */
enum class GattOp { CONNECT, CHARACTERISTIC, MTU, SERVICES, SUBSCRIBE, SUBSCRIBE_RX, SUBSCRIBE_FLOW, READ, READ_CREDIT, WRITE, OPERATION }

/** Why the link with the Flipper could not be opened or ended. GATT statuses are Android's numbers. */
sealed interface FlipperFailure {
    data object NoBluetooth : FlipperFailure
    data object BluetoothOff : FlipperFailure
    data object NoPermission : FlipperFailure
    data object NotBonded : FlipperFailure
    data object BondIncomplete : FlipperFailure
    data class BondLost(val status: Int?) : FlipperFailure
    data object NotResponding : FlipperFailure
    data class ConnectFailed(val status: Int) : FlipperFailure
    data object MtuDropped : FlipperFailure
    data object ServicesTimeout : FlipperFailure
    data object NotAFlipper : FlipperFailure
    data object BadFlowValue : FlipperFailure
    data object Overflow : FlipperFailure
    data object Stalled : FlipperFailure
    data object RpcClosed : FlipperFailure
    data class Disconnected(val status: Int) : FlipperFailure
    data class OpDropped(val op: GattOp, val status: Int) : FlipperFailure
    data class OpFailed(val op: GattOp, val status: Int) : FlipperFailure
    data class WriteFailed(val status: Int) : FlipperFailure
    data object ClosedByRelay : FlipperFailure
}

/** Extra detail next to a [Problem]: never a token. */
sealed interface Detail {
    data class Http(val code: Int) : Detail
    /** The version of another relay app attached to the server. */
    data class OtherApp(val version: String) : Detail
    data class Net(val error: NetError) : Detail
    data class Flipper(val failure: FlipperFailure) : Detail
    data class Session(val end: SessionEnd) : Detail
}
