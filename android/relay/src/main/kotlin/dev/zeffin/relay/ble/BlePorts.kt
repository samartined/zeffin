// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.ble

import dev.zeffin.relay.GattOp
import dev.zeffin.relay.TIMEOUT_STATUS
import dev.zeffin.relay.RadioState
import kotlinx.coroutines.Deferred
import java.util.UUID

/** The Flipper's serial service, as the firmware defines it. */
object FlipperGatt {
    val SERVICE: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    /** Flipper -> phone, indications. */
    val RX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
    /** Phone -> Flipper, write with response. */
    val TX: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
    /** Flow control: the free space of the Flipper's RPC buffer, big-endian uint32. */
    val FLOW: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000")
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MTU_REQUEST = 512

    /** Bonded devices that look like a Flipper: the Flipper address prefix or the default name. */
    fun looksLikeFlipper(address: String, name: String?): Boolean =
        address.uppercase().startsWith("80:E1:26:") || name?.startsWith("Flipper", ignoreCase = true) == true
}

/** A GATT status from Android and the operation it belongs to. */
class GattError(val status: Int, val op: GattOp) : Exception("$op failed: GATT status $status")

const val TIMEOUT = TIMEOUT_STATUS

/** Status codes that mean the Flipper no longer accepts this phone's bond. */
fun isAuthFailure(status: Int) = status == 5 || status == 15 || status == 137

enum class Bond { NONE, BONDING, BONDED }

/** One GATT connection. Operations run one at a time, each with its own time limit. */
interface GattPort {
    /** Completes with the GATT status when the connection drops. */
    val disconnected: Deferred<Int>
    suspend fun requestMtu(mtu: Int): Int
    /**
     * Asks Android for a short connection interval (CONNECTION_PRIORITY_HIGH, ~11–15 ms instead of the tens or
     * hundreds the peers may settle on). Every reply from the Flipper is one indication that waits for the phone's
     * confirmation, so the interval sets the cost of each RPC reply. True when Android accepted the request.
     */
    fun requestFastConnection(): Boolean
    /** True if the serial service and its three characteristics are there. */
    suspend fun discoverSerial(): Boolean
    suspend fun enable(characteristic: UUID, indication: Boolean)
    suspend fun read(characteristic: UUID): ByteArray
    suspend fun write(characteristic: UUID, value: ByteArray)
    /** Called on a binder thread for each notification or indication. */
    fun onChanged(handler: (UUID, ByteArray) -> Unit)
    /** Called on a binder thread when the MTU changes outside [requestMtu]: a late grant, or one the Flipper started. */
    fun onMtu(handler: (Int) -> Unit)
    /** Disconnects, waits (bounded) for Android to confirm, and releases the connection. */
    suspend fun close()
}

/** The phone's Bluetooth, as the connector needs it. */
interface BleRadio {
    fun state(): RadioState
    fun bond(address: String): Bond
    /**
     * A Bluetooth link with the device is up. Android reports any open link, ours, the one left by
     * pairing or another app's, so this cannot tell who keeps it.
     */
    fun linkConnected(address: String): Boolean
    /** Connects; with [autoConnect] Android waits for the device without a time limit. Throws GattError. */
    suspend fun connect(address: String, autoConnect: Boolean, timeoutMs: Long?): GattPort
}
