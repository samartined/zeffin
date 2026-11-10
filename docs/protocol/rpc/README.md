# The Flipper's RPC link: who sends what, and the `command_id` ranges

The contract between the server and the relay for sharing the Flipper's one RPC session. The server drives the Flipper through `/phone`; the relay also sends a few messages of its own on the same link (the phone's remote for the Flipper: keys and, without a server, the screen stream). Both read the same stream, so each reply must go to the side that asked.

`vectors.json` holds the exact bytes of every message the relay builds and of the replies it reads. The server's `server/tests/test_rpc_vectors.py` builds them with `pb` and compares; the app's `RpcCodecTest` encodes and decodes them. A change on either side fails the other, as [`../pairing/key-vector.json`](../pairing/README.md) does for the key.

## Framing

- The link carries `PB.Main` messages (the Flipper's protobuf bundle), each preceded by its length as a varint: the same on both directions and on BLE and USB.
- The bytes the relay writes and the server's frames share the link's write side. A message is written whole, never interleaved with another.
- **Each server frame on `/phone` holds whole messages, never part of one** (`phone.py`, `_send_messages`). The relay relies on it to tell where a server command ends.
- The Flipper reads requests in order and answers each in order. A reply carries the request's `command_id`, its `command_status` and `has_next` when more parts follow.

## The ranges

`command_id` is a uint32. Each side takes ids only from its own range:

| Range | Who | What |
|---|---|---|
| `0` | the Flipper | Unsolicited messages: screen frames (`gui_screen_frame`). Nobody sends a request with id 0. |
| `1 … 2^28 − 1` (`1 … 268435455`) | the server | Its requests. `_allocate_id` counts from 1 and wraps back to 1 after `2^28 − 1`. |
| `2^28 … 2^29 − 1` (`268435456 … 536870911`) | the server | Its settle stop, at random in the range (below). |
| `2^29 … 2^30 − 1` (`536870912 … 1073741823`) | the relay | Its own messages. It counts from `2^29` and wraps inside its range. |
| `2^30 … 2^32 − 1` | nobody | Reserved. |

The constants live in `server/bridge/phone.py` (`SERVER_ID_MAX`, `SETTLE_ID_MIN`, `SETTLE_ID_MAX`, `RELAY_ID_MIN`, `RELAY_ID_MAX`) and in the relay's codec; `vectors.json`'s `ranges` holds them as numbers, and both sides' tests compare.

**Ids from `2^28` up take a 5-byte varint,** so a settle stop's or a relay message's id is always 5 bytes on the wire.

## What the server does with the relay's replies

- The Flipper's replies to the relay reach the server too: the relay forwards every byte from the Flipper, unchanged and in order.
- `PhoneSession._dispatch` drops a message whose id is in the relay's range, after handing on screen frames and before matching it to a pending request. It is counted (`relay_replies` in the `close` metric line) and never answers a request of the server's or reaches `flipper_events`.
- So a key the owner presses never shows in `flipper_events`.

## The settle stop

- When a session starts, the link may be mid-stream: Android can keep the Bluetooth link of an earlier session, and its Flipper RPC session and stream live on. The server sends `gui_stop_screen_stream_request` with an id from the settle range and drops every byte until that stop's reply (`phone.py`, `settle`).
- **A match is the whole reply:** a one-byte length, `08` and the id's varint, and the bytes after it parse as a complete `PB.Main` with that id and no `has_next`. The marker alone is not enough: a screen frame's data may hold it (`screen_frame.settle_lookalike` in `vectors.json`).
- **The relay syncs its own reader the same way, only on an outgoing stop whose id is in the settle range.** `hub.restart_stream`'s stop carries an ordinary server id, and nobody resyncs on it.

## What the relay sends

Only these three messages, each with an id from its range:

| Message | When |
|---|---|
| `gui_send_input_event_request` | A key: `PRESS`, then `SHORT` or `LONG`, then `RELEASE`, three messages in one write, as the server's `press_keys` sends them. Never `REPEAT`. |
| `gui_start_screen_stream_request` | Without a server session, to start the stream while the remote is open. |
| `gui_stop_screen_stream_request` | Without a server session, to stop the stream it started. |

- **Never id 0,** and never an id outside its range.
- **Never the serial service's reset characteristic:** writing it restarts the Flipper's RPC session, which would end the server's too.

## What the relay reads

- Every message's `command_id`, `command_status` and `has_next`; and a `gui_screen_frame`'s `data` (1024 bytes) and `orientation`.
- Every other field is skipped by its wire type (0 varint, 1 fixed64, 2 length-delimited, 5 fixed32). Wire types 3, 4, 6 and 7 are an error.
- Replies with ids in its range are its own; the rest it leaves to the server.
- **What comes from the Flipper is untrusted data:** a status is shown as a number and a fixed name, never as text from the device.

## `vectors.json`

Plain JSON with `snake_case` keys; every id is a JSON number (it fits a signed 64-bit integer); every enum is given by its proto name and its number.

```json
{
  "format": 1,
  "about": "…",
  "ranges": {"unsolicited": [0, 0], "server": [1, 268435455], "settle": [268435456, 536870911],
             "relay": [536870912, 1073741823], "reserved": [1073741824, 4294967295]},
  "requests": [
    {"name": "input.up.press", "sender": "relay", "message": "gui_send_input_event_request",
     "command_id": 536870912, "fields": {"key": "UP", "key_value": 0, "type": "PRESS", "type_value": 0},
     "hex": "09088080808002ba0100"}
  ],
  "replies": [
    {"name": "ok", "command_id": 536870912, "command_status": "OK", "command_status_value": 0,
     "has_next": false, "content": "empty", "fields": {}, "hex": "…"}
  ]
}
```

- **`hex`** is the whole message as on the wire: the length varint, then the `PB.Main`.
- **`requests`** (`sender: "relay"`): `input.<key>.<type>` for the six keys (`up`, `down`, `right`, `left`, `ok`, `back`) and `press`, `short`, `long`, `release`; `screen_stream.start` and `screen_stream.stop`. Their ids run from `2^29`, and `input.back.release` takes `2^30 − 1`, the top of the range. `settle.stop` (`sender: "server"`) is the server's settle stop, with an id from the settle range.
- **`replies`:**
  - `ok`, `error` (`ERROR_INVALID_PARAMETERS`) and `multipart` (`has_next: true`) to relay ids, with `content: "empty"` as the Flipper sends them;
  - `unknown_fields`: an `OK` reply followed by unknown fields 1000 to 1003 of wire types 0, 1, 2 and 5, which a reader skips;
  - `settle.stop.ok`: the reply to `settle.stop`;
  - `screen_frame`: id 0, `data` the 1024 bytes `(i * 7 + 3) % 256` for `i` from 0 (`data_hex` holds them), orientation `VERTICAL` (2);
  - `screen_frame.settle_lookalike`: the same frame with `settle.stop`'s marker inside its data at two places (`lookalike_for` names the id): once followed by `has_next`, once by bytes that do not parse. It must never be taken for `settle.stop.ok`.
- **Proto3 leaves out zero values:** `UP`, `PRESS`, `OK`, `has_next: false` and id 0 are absent from the bytes, so `input.up.press` is an empty `gui_send_input_event_request` (`ba 01 00`). An encoder must match the bytes exactly.
- A new message the relay sends, or a new reply it reads, goes into `vectors.json` first, with both sides' tests.
