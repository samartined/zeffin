// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.rpc

/**
 * The few pieces of the Flipper's RPC protocol the relay speaks itself (server/bridge/pb/proto/flipper.proto and gui.proto),
 * by hand: no protobuf library. The contract both sides test is docs/protocol/rpc/ (README.md and vectors.json).
 *
 * Each message on the link is a `PB.Main` preceded by its length as a varint. What the relay sends: three requests
 * (`gui_send_input_event_request`, `gui_start_screen_stream_request`, `gui_stop_screen_stream_request`), always with an id
 * from its own range. What it reads: `command_id`, `command_status`, `has_next`, and a `gui_screen_frame`'s `data` and
 * `orientation`. Everything from the Flipper is untrusted data: it is never turned into text.
 */
internal object RpcIds {
    /** `1 … 2^28 − 1`: the server's requests. */
    const val SERVER_MAX = (1L shl 28) - 1
    /** `2^28 … 2^29 − 1`: the server's settle stop, the one the relay syncs on. */
    const val SETTLE_MIN = 1L shl 28
    const val SETTLE_MAX = (1L shl 29) - 1
    /** `2^29 … 2^30 − 1`: the relay's own messages. `2^30 …` is reserved; `0` is the Flipper's unsolicited messages. */
    const val RELAY_MIN = 1L shl 29
    const val RELAY_MAX = (1L shl 30) - 1

    fun isSettle(id: Long) = id in SETTLE_MIN..SETTLE_MAX
    fun isRelay(id: Long) = id in RELAY_MIN..RELAY_MAX
}

/** Field numbers and enum values, as the .proto files define them. */
internal object Pb {
    // PB.Main
    const val COMMAND_ID = 1
    const val COMMAND_STATUS = 2
    const val HAS_NEXT = 3
    const val GUI_START_SCREEN_STREAM_REQUEST = 20
    const val GUI_STOP_SCREEN_STREAM_REQUEST = 21
    const val GUI_SCREEN_FRAME = 22
    const val GUI_SEND_INPUT_EVENT_REQUEST = 23
    /** `content` is a oneof of fields 4 and up; the highest today is 75. A higher number is an unknown field, skipped. */
    const val MAX_CONTENT = 127

    // PB_Gui.ScreenFrame
    const val FRAME_DATA = 1
    const val FRAME_ORIENTATION = 2

    // PB_Gui.SendInputEventRequest
    const val INPUT_KEY = 1
    const val INPUT_TYPE = 2

    // PB_Gui.InputType
    const val PRESS = 0
    const val RELEASE = 1
    const val SHORT = 2
    const val LONG = 3

    const val OK = 0

    /** The length of one message: the same bound as server/bridge/framing.py. */
    const val MAX_MESSAGE = 65536
    /** A length prefix longer than this is never valid (framing.py reads at most five bytes). */
    const val MAX_PREFIX = 5

    /** PB.CommandStatus names, from flipper.proto: what the remote shows for a refused key, never a text from the device. */
    private val STATUS = mapOf(
        0 to "OK", 1 to "ERROR", 2 to "ERROR_DECODE", 3 to "ERROR_NOT_IMPLEMENTED", 4 to "ERROR_BUSY",
        5 to "ERROR_STORAGE_NOT_READY", 6 to "ERROR_STORAGE_EXIST", 7 to "ERROR_STORAGE_NOT_EXIST",
        8 to "ERROR_STORAGE_INVALID_PARAMETER", 9 to "ERROR_STORAGE_DENIED", 10 to "ERROR_STORAGE_INVALID_NAME",
        11 to "ERROR_STORAGE_INTERNAL", 12 to "ERROR_STORAGE_NOT_IMPLEMENTED", 13 to "ERROR_STORAGE_ALREADY_OPEN",
        14 to "ERROR_CONTINUOUS_COMMAND_INTERRUPTED", 15 to "ERROR_INVALID_PARAMETERS", 16 to "ERROR_APP_CANT_START",
        17 to "ERROR_APP_SYSTEM_LOCKED", 18 to "ERROR_STORAGE_DIR_NOT_EMPTY", 19 to "ERROR_VIRTUAL_DISPLAY_ALREADY_STARTED",
        20 to "ERROR_VIRTUAL_DISPLAY_NOT_STARTED", 21 to "ERROR_APP_NOT_RUNNING", 22 to "ERROR_APP_CMD_ERROR",
        58 to "ERROR_GPIO_MODE_INCORRECT", 59 to "ERROR_GPIO_UNKNOWN_PIN_MODE",
    )

    fun statusName(status: Int): String? = STATUS[status]
}

/** A stream or a message the relay cannot read: the reader stops for the session. */
internal class RpcError(message: String) : Exception(message)
