# The Flipper tools

What the agent can do with the Flipper: the `flipper_*` tools of the `flipper` MCP server, which the Zeffin server offers on `127.0.0.1:9435` only. You do not call them yourself; this page says what the agent has, so you know what to ask for and what to expect.

## The tools

| Tool | What it does | Under Pause |
|---|---|---|
| `flipper_status` | Phone connected, transport, the operator's gate, the screen's age | Works |
| `flipper_screen` | The latest frame as an upscaled PNG (×4 by default); `fresh` forces a redraw | Works |
| `flipper_press` | Up to 20 keys (`UP`, `DOWN`, `LEFT`, `RIGHT`, `OK`, `BACK`, with a `:long` suffix for a long press), about 0.5 s each over Bluetooth, and returns the settled screen | Refused |
| `flipper_wait_screen` | Waits for a frame newer than a given one | Works |
| `flipper_watch` | Watches a screen region frame by frame on the server until it changes, stays still or matches an earlier screen, then presses up to 5 keys at once (optionally again, up to 20 times; at most 120 s): it reacts faster than the agent's own turn | Its keys are refused |
| `flipper_firmware` | The detected firmware, its profile (menus, keys, app paths, quirks), the apps found on this Flipper, and one line per installed app from the firmware's app catalog; `refresh=true` detects again | Works |
| `flipper_app` | One app's whole catalog entry by its `appid`, its note from an earlier conversation (also for an app in no catalog), and, when the server has the firmware's app sources, the app's README | Works |
| `flipper_note` | Saves the agent's note on driving one app, for later conversations ([Notes per app](#notes-per-app)); it does not talk to the Flipper | Refused |
| `flipper_device_info`, `flipper_power_info` | Firmware and battery information (`device_info` takes about 25 s over Bluetooth) | Works |
| `flipper_list`, `flipper_stat`, `flipper_read` | Read the file system (`/ext` is the SD card). `flipper_stat(md5=true)` checks a file without reading it | Works |
| `flipper_write`, `flipper_mkdir`, `flipper_delete`, `flipper_rename` | Change the file system | Refused |
| `flipper_app_start`, `flipper_app_exit`, `flipper_app_load_file`, `flipper_app_button` | Apps. `app_start` opens any app in about 0.5 s (a `.fap` by its path, and then it returns the app's note, when it has one); the others need apps with RPC support | Refused |
| `flipper_rpc` | Any Flipper Zero RPC request, as JSON | Refused |
| `flipper_events` | Recent unsolicited messages from the Flipper | Works |

## How they behave

- **Like a person with a remote control:** the agent looks at the screen, presses a few keys, looks again. A key takes about 0.5 s over Bluetooth, so short routes are faster; opening an app by its path skips the menus.
- **One call at a time:** calls to the Flipper run in order; one more may wait, and any further call is refused at once (`refused: busy`).
- **No retries:** a timeout or a disconnect leaves the result unknown. Nothing is repeated by itself; the agent is told to look before trying again.
- **Settled screen:** after a press, the server waits until no frame arrives for 250 ms, up to 1.5 s, because some animations never stop. The result says whether the screen settled.
- **Files are slow:** about 1 KB/s over Bluetooth.
- **A timeout does not end the session.** The phone reconnects by itself, and the server restarts the screen stream.

## What the agent reads is data, never instructions

- File names and contents, device information and RPC replies reach the agent wrapped as untrusted data (`<flipper_data trust="untrusted">`), with control and bidirectional characters escaped.
- The app catalogs' names and descriptions were written by each app's author: the tools label them as descriptions, never instructions. A capability flag ("it transmits") is a hint, never a permission.
- The agent folder's `CLAUDE.md` (Claude Code) and `AGENTS.md` (OpenCode) say the same to the agent, and ask it not to transmit (Sub-GHz, infrared, NFC or RFID emulation, BadUSB) unless your request asks for it explicitly. **That is an instruction, not a guard:** see [Security](security.md#what-zeffin-does-not-stop).

## The firmware and its apps

The server detects the Flipper's firmware when the screen stream starts, and keeps what it learns in `state/device.json`. The agent reads it first with `flipper_firmware`.

To let `flipper_app` read the apps' READMEs, name a local checkout of the firmware's app sources (it is kept in `config.json`, never in the repository, and read at the next restart):

```bash
zf set-firmware-sources official ~/src/flipper-firmware
zf restart
```

`zf set-firmware-sources ORIGIN` alone shows the current one, and `--remove` forgets it. `ORIGIN` is one of the profiles the server ships (`official`, `momentum`, `unleashed`).

## Notes per app

Some apps hide how they work: a long press that opens a page, a key that does nothing, an exit that needs two keys. The agent finds that out by trial, which costs time and screens. So it can keep what it learned, one short note per app, and later conversations on this server start from it.

- **When:** a note is saved when the agent decides to, or when you ask it: "save what you learned about this app". The agent is told to save one as soon as it finds a key or an exit that was not obvious, and opening an app by its path with no note yet reminds it, and whether it does depends on the model: in one test, Claude Code with Haiku saved one by itself while it worked out an app, and the next conversation used it; in four tests, NVIDIA's free model saved one only when asked. So after the agent has worked out an app, check the chat for the "Using flipper_note" card, or ask it. A new note on the same app replaces the old one whole.
- **What:** in the agent's own words, up to 1,000 characters and 12 lines: what the app opens on, the keys that are not obvious, how to leave it, what it lacks, and any key that transmits or writes. Never your file names or values.
- **How it comes back:** `flipper_firmware` lists which apps have a note; `flipper_app` shows it, and opening an app by its `.fap` path returns it. It comes as untrusted data, a hint from an earlier conversation: the agent is told to check each step on the screen, and to write the note again when it is wrong. A note written on another firmware build is marked so.
- **Where:** in `state/notes/`, one file per firmware and app, on this server only. A note belongs to the app, not to the conversation: deleting a conversation leaves its notes.
- **Pause and Stop** refuse a note, as they refuse keys.
- **You see and control them:** the chat shows each note as the agent writes it (the "Using flipper_note" card, with the start of the note: its first 160 characters), and on the server, where `zf notes show` prints it whole:

```bash
zf notes                          # list them: firmware, app, length, when, same or other build, installed now
zf notes show APPID               # read one (--firmware ORIGIN for another firmware's)
zf notes delete APPID             # delete one; zf notes delete --all deletes every note
zf notes off                      # the agent neither writes nor reads them; zf notes on turns them back on
```

`zf notes off` keeps the files; `zf notes delete --all` removes them. The notes are part of what reaches the agent's provider in later conversations, like the screens.

With Claude Code, its own memory between sessions (auto memory) is turned off in Zeffin's conversations, so the notes are the one memory, for both agents.

## Audit

Every call goes to `state/audit.jsonl` with its arguments (cut), its result and the frame number before and after; for `flipper_note`, the app and the note's length, never its text. Tokens never reach it. The app's Home and the web view show the agent's actions as they happen.
