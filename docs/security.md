# Security

What the bridge trusts, what protects each part, and where every security finding stands. The layers are in [architecture.md](architecture.md).

- Change this document in the same pull request as the change to what it says.
- An open finding gets one line here: what will be fixed, with no attack details, until it is fixed.

## What is trusted

- **The server and the user Zeffin runs as on it.** Other local users are not trusted: they may bind the bridge's ports while it is down (see "Tokens").
- **The private network (Tailscale)** carries everything between the phone, the browser and the server.
  - The public listener binds only to a Tailscale or loopback address (`config.check_public_host`).
  - The public listener serves only private-network or loopback peers (`PrivatePeers`).
  - The app sends cleartext (`ws://`, `http://`) only through the private network's tunnel: each socket is bound to the VPN network whose route inside the private ranges covers the server, or refused before any byte; no proxy; one client (`core/ServerHttp.kt`).
  - Tailscale's own encryption protects the transport.
- **The server's own key** proves the server to the app, beyond the tunnel.
  - `zf` makes it: P-256, in the data folder (`tls-key.pem`, mode 600), never in the repository, a log, an error or a chat. `bridge/tlskey.py` refuses a key file other users can read, and never replaces one it cannot use.
  - The public listener speaks only TLS 1.3 with it. A TLS 1.2 or cleartext client gets nothing.
  - The pairing QR code carries the SHA-256 of its public key (the pin). The app checks it in the handshake, before it sends a token, a pairing code or chat content, and never falls back to cleartext.
  - `zf key new` replaces it, and by default the phone and app tokens: the app must pair again.
  - The web view now receives the pairing code over https; it used to travel in cleartext inside the tunnel.
  - **Known limits:**
    - both sides still trust the kernel's peer or route for `PrivatePeers` and the tunnel. A VPN app that claims the private ranges no longer gets anything from the app, since it cannot show the pinned key. A local proxy on the server (`tailscale serve`, Funnel, an SSH tunnel) still shows outside peers as loopback or private to `PrivatePeers`: client certificates (mTLS) would close that, a later step;
    - the TLS handshake comes before `PrivatePeers` (the ASGI scope exists only after it): a peer outside the private network can finish a handshake and see the certificate (the public key and the address it already used) before it gets 403;
    - `/view` in a browser is not pinned: the browser warns once about the self-signed certificate, and the owner compares its public key's hash with the fingerprint `zf status` prints;
    - a leaked key most likely means a leaked `config.json` too, in the same folder; that is why `zf key new` also changes the phone and app tokens, and says to rotate the others if `config.json` may have leaked.
- **Not trusted:** whatever comes from the Flipper (its screen, file names and contents) and the agent's output. It is cut and cleaned where it enters (`text.py`), handed to the model as data, and shown to the owner without control or invisible format characters.

## The two listeners

| Listener | Where | What it serves | Who may use it |
|---|---|---|---|
| Public, TLS 1.3 only with the server's key | the server's Tailscale address, port 9434 by default | `/phone` (the relay), `/app` (the app), `/view` (the web view), the attachments' upload and download, `/app/pair`, `/app/pair/wait` and `/app/pair/cancel` | the phone token, the app token, the view token; the pairing routes take a rotating code, then the request's ticket |
| MCP, cleartext | `127.0.0.1` only, port 9435 by default | `/mcp` for the agent, `GET /status` for `zf status`, `/hooks/*` for the agent's hooks, `/pair*` for `zf pair` | the MCP token; each turn's own hook token; the app token for `/pair*`, never the MCP token. `zf` sends a token to it only after `localpeer`'s uid check |

## Tokens

- **Four independent tokens** in `config.json` (phone, MCP, view, app). They must differ, and `zf rotate-tokens` replaces them.
- **Never printed, logged or put on a command line.** Docs and tests use fake values.
- **`zf` sends a token only to a listener of its own user** (`localpeer.py`; F2, F17). After `connect()` and before the first byte, it finds the server's end of that very connection in `/proc/net/tcp*` and checks its uid, once the server has accepted it: before that, Linux shows that end with uid 0 and no inode, so it reads again for up to 2 s. When it cannot tell, it refuses. Never through a proxy.
- **The probe gives its turns no MCP token** without a running bridge of ours, and stops at a listener of another user.
- **The MCP token reaches the agent without a command line** (F18): through `${ZEFFIN_MCP_TOKEN}` in an MCP config, or in the user-scope entry `zf mcp-config` writes into `~/.claude.json` (mode 0600).
  - **Known limit:** with the user-scope entry, every interactive `claude` the owner starts sends the token to that port, also while the bridge is down. That is how Claude Code connects to MCP servers; `zf` cannot check it. Documented, not fixed.
- **Each chat turn and each Remote Control session has its own hook token.** A hook with another token is refused.

## Pairing

The contract is [protocol/pairing/](protocol/pairing/README.md).
- **The QR code holds the address, the pin of the server's key and a code,** never a token. The same JSON is the pairing text. The code changes every 30 s (the previous one works 5 s more), and the pairing ends after 3 minutes.
- **A right code gets nothing by itself.** It makes one request, with a ticket only that phone has and six random digits that the phone and the server both show.
  - The pairing freezes and its code is spent: any other scan gets "code already used".
  - The tokens leave only when the owner confirms that request on the server (the web view, or `zf pair` with the app token), naming its `ref`, within 120 s. The phone collects them once.
  - **The web view needs the pairing's own secret**: `POST /view/pair`, with the view token typed again, returns a random secret that the page keeps in memory only and sends as a header. Without it, the cookie (a fixed HMAC of the view token) sees only the pairing's state: no code, no `ref`, no number, and confirm, refuse and cancel are refused. A pairing `zf pair` opened shows in the web view as its state only. If the web view's pairing ends paired without this screen's confirmation, it tells the owner to run `zf rotate-tokens`.
  - A poll never confirms anything. Someone who scanned the owner's QR code gets their own number over their own connection, and cannot read the owner's.
- **The fingerprint proves the server; the number proves the request.**
- **Codes, tickets, refs and numbers live in memory** (a restart ends them) and reach no log line and no metrics line. A request that got nothing is a `pair_refused` line, shown in the web view's phone connections.
- **A wrong code does not cancel the current one** (F16). There is no lockout: a 128-bit code that lives about 35 s cannot be guessed, and a refused try waits half a second.
- **The app keeps the pairing as one record:** the server's address and both tokens, encrypted whole with an Android Keystore key, written in one step (F8, F11).
- **The owner confirms the server** before a working pairing is replaced (F14).

## The devices that use the server

Every paired device holds the same tokens, so the server tells devices apart by their address on the private network, which the tunnel ties to each device (`peers.py`).
- **Only a peer that passed a credential enters the list:** a token, the view cookie or a pairing whose tokens it collected. A refused peer (outside the private network, a wrong token, cookie or code) never enters it and is never looked up; it stays in the metrics as before.
- **Read only on the loopback listener,** in `/status` with the MCP token, for `zf status`. Nothing on the public listener shows it, and it never holds a token. The agent, which holds the MCP token, sees nothing there that `tailscale status` does not already show any local process.
- **Bounded and in memory:** at most 32 devices since the server started, fixed-size records; nothing is written to a file. The server's log gets one line the first time each device connects after a start, with its name: the record across restarts.
- **The name** comes from `tailscale whois --json` (`privnet.lookup`): `Node.ComputedName` and `Node.Hostinfo.OS` only, never the tailnet's DNS name, the host name or the user's login. It runs in a worker thread after a sighting, with the kernel's peer address as its one argument and no shell, 2 s and 256 KiB at most, two at once, cached 10 minutes (a failure 1 minute); `/status` never waits for it. A device chooses its own name: it is cleaned when kept and printed terminal-safe.
- **Known limit:** a local proxy on the server (see "What is trusted") shows outside peers as "this server".

## The agent

- **Paid usage is the user's choice:** each agent runs with the login or providers the user set up, and `/app` marks each model that may bill as `paid`. **The "free only" switch** (`free_only` in `config.json`, `zf set-free-only`, off by default) turns the "never pay" guards on:
  - **Claude Code, subscription only:** never API-key variables in a turn's environment, a subscription login (`apiKeySource` none), and only the models it covers; **the guard** (`chat/claude_code.py`) kills a turn at once when its start reports an API key, a 1M-context model, or a model that bills usage credits, or when going on would need usage credits.
  - **OpenCode, free providers only:** `opencode.json` allows only OpenCode Zen and NVIDIA NIM, the merged configuration (`opencode debug config`) may name no other provider or model and no agent or mode with a model of its own, and only free models are offered (Zen's priced at 0, NIM's from `free_models.json`). The version and the merged configuration are checked again when each turn starts, besides the check every 10 minutes.
- **Always, whatever the switch:**
  - the `claude` CLI never with `--bare`, and no token on any agent's command line;
  - OpenCode's environment is an allow-list (no provider's key, no token), its sharing and auto-update are off (also in the merged configuration), and its version is pinned to the range checked;
  - a session on another model than the conversation chose, at its start or during the turn (a `config_option_update`), is killed at once;
  - a tool call counts as the bridge's own (no card, in any mode) only when its name is exactly one of the bridge's MCP tools: an OpenCode request's title can be any text, such as a shell command;
  - OpenCode's `doom_loop` request (one tool run 3 times in a row with the same input) is answered allow once by the bridge, with no card and no rule, only when the repeated tool is exactly one of the bridge's MCP tools, in the shape OpenCode 1.19.5 sends; any other tool, or a request of another shape, reaches the card. The Flipper tools run without asking anyway (Claude Code has no such check), and Pause still refuses those that change the Flipper;
  - "Always" on an OpenCode card is answered `allow_once`, and the bridge keeps and matches the granted rule itself: OpenCode's own `always` patterns are broader than the card's rule (security review before 1.0.0, M1 and M2);
  - the MCP token reaches OpenCode only in `session/new` and `session/load`, through the pipe; after an MCP turn with 1.19.5 it was found nowhere in OpenCode's data, state, cache or configuration folders (L2, checked on a real server);
  - **the agents cannot change their own settings or instructions without the card** (S2): an edit, delete or move of `opencode.json`, `opencode.jsonc`, `.opencode/`, `AGENTS.md`, `CLAUDE.md`, `CLAUDE.local.md`, `.claude/` or `.mcp.json` in the agent folder, of OpenCode's global folder, or of `~/.claude/` and `~/.claude.json` (`chat/guarded.py`), always reaches the card, with no "Always" offered. For OpenCode, neither Accept edits nor a kept rule answers it, and an edit whose paths the request does not state clearly reaches the card too. For Claude Code, each turn carries ask rules for the files its own protected paths leave out (`CLAUDE.md`, `AGENTS.md`, OpenCode's files); it already asks for `.claude/` and `.mcp.json` in Accept edits, and no allow rule pre-approves those (code.claude.com/docs/en/permission-modes, "Protected paths");
  - **OpenCode's permissions are the bridge's** (S2): when each turn starts, whatever the switch, a merged configuration whose `permission` (top level, per agent, per mode, or the older `tools`) allows bash, edit, webfetch or external_directory, itself or through a wildcard, is refused. The user's own plugins, MCP servers and other rules are left alone;
  - **known limit:** for a shell command, both checks read the command's text. A command that writes one of those files without naming it plainly (a glob, quotes, a variable) is still a command, so it reaches the card in every mode but Claude Code's Accept edits, whose file commands (`cp`, `mv`, `sed`, …) Claude Code approves itself on paths in the folder.
- **The owner approves what they can see whole:**
  - the permission cards show the whole command, with no silent cut (F1), and every other argument of the request, whole, each under its own label: a file's content, an edit's texts, a page's prompt, an unknown tool's arguments; the agent's description is labelled as its own (S1). A path inside the server's home folder, and in the "Always" rules shown, is written with `~` (`hello.server.home`), only for that folder or a path inside it; the answer sends the rules as the server offered them;
  - a permission request's input reaches the cards whole up to 64 KiB of JSON, the bound of the agent's own text (S1b; a tool's card in the conversation stays cut). Past it, it comes cut and marked not whole: the cards say it is too long to show and offer only Deny, the notification no Allow once, `zf chat` only deny, and the server takes no other answer. In Accept edits, edits are still the mode's to answer, whatever their size;
  - the notification offers "Allow once" only for a request it shows whole (F7);
  - "always" saves only the rules the client showed (F19). Claude Code gets the kept rules as one JSON string each in the turn's inline settings (`permissions.allow`), never on the command line, and a rule whose content could be read as several (a quote, a parenthesis that closes early) is never kept; OpenCode's rule names exactly one path, never a move (S4 and S5);
  - Markdown drops bidi and format characters, also after decoding references (F20), and draws at most 8 levels of nested quotes and lists, the deeper ones flat with their text, so no message can overflow the stack and close the app (S3);
  - `zf chat` strips terminal control characters and shows the exact command (F4).
- **Claude Code's auto memory is off** in every `claude` the server starts, turns and Remote Control sessions included (`CLAUDE_CODE_DISABLE_AUTO_MEMORY=1` in `command.base_env`; code.claude.com/docs/en/memory, "Enable or disable auto memory"). Claude Code loads that memory at the start of each session as instructions, so nothing an earlier conversation wrote there becomes one. The notes per app ("The Flipper", below) are the one memory, handed back as data, for both agents.
- **A permission nobody answers** on the other device is declined after 15 minutes, so the server is never stuck.
- **Attachments:**
  - the meta read back from disk is checked before any file is read or removed (F3);
  - a download's type is one its upload could have had.
- **Chat history** splits only on `\n`, and a bad line never closes `/app` (F13).

## The Flipper

- **The operator's gate:** Pause and Stop in the web view. It survives a restart (`state/gate.json`).
- **Writing tools check the gate again** after waiting for the device and between the frames of a long write (F12). After Pause, nothing more is written.
- **One call at a time:** `ops_lock` runs one call and lets one wait.
- **The phone's remote** drives the Flipper over the relay's own link, outside the agent's tools: with a server session it pauses the agent first (`gate` with `via: "remote"`, audited as `remote`), and its keys go only between the server's commands; without a server session the relay does not connect to the server while the remote is open, so the agent cannot start in the middle. The server drops the replies to the relay's ids, so they never reach the agent.
- **What the agent reads about the Flipper's firmware**: the device's firmware fields, its app names and any app README are untrusted and wrapped as such; the app catalogs' names and descriptions were written by the apps' authors, cleaned and capped when built, and labelled as descriptions, never instructions; a README is read only inside the configured source tree. Knowing an app transmits gives no permission.
- **Notes per app**: the agent's own notes on driving one app, written with `flipper_note` after it read untrusted screens, and read back by later conversations.
  - **In the agent's own words:** the tool's description and the server's instructions ask for keys and screens described in its words, with no instructions, no text copied beyond short menu labels and nothing of the operator's. This is guidance only: the server cannot enforce it.
  - **Cleaned and capped where it enters, and again when read** (`notes.py`): control, bidi and format characters written as `\uXXXX`, the blocks' tags escaped, refused rather than cut past 1,000 characters or 12 lines, at most 200 notes per firmware and 500 in all; a file edited by hand is cleaned again or ignored. Its key is the firmware's origin reduced to `[a-z0-9-]` and the `appid` of an app on this Flipper, so no device text becomes a path.
  - **Handed back only in tool results,** as untrusted data inside a `<flipper_data>` block, with a header saying who wrote it and from what, and flagged when written on another build: `flipper_firmware` lists which apps have one, and `flipper_app` and `flipper_app_start` (with a `.fap` path) show it. Never in the agents' instruction files, the system prompt or the MCP instructions.
  - **Gated:** Pause and Stop refuse a note, so Stop also keeps an injected screen from reaching one. Its audit line holds the `appid`, its length and whether it replaced a note, never its text.
  - **The owner's control:** the tool card in the chat shows each note as it is written; `zf notes` lists, shows and deletes them, and `zf notes off` turns them off (nothing written or handed back; the files stay until deleted).
  - **Risk left:** a wrong or misleading note in the agent's words (an injected screen made an earlier conversation write "RIGHT exits" where RIGHT transmits), bounded by the transmit rule, "trust the screen over it", the build flag and the owner's view; a note that carries instruction-like text despite the guidance, framed like device data but lasting until deleted; the user's data in a note (a file name, a value), which outlives a deleted conversation and goes to the provider in later ones, like the screens.

## What the server keeps on disk

Everything below is in the data folder's `state/`, next to the clone, unless it says otherwise.

- **The audit and metrics logs** (`audit.jsonl`, `metrics.jsonl`) keep their newest 8 MiB each (`gate.JsonlLog`): past 10 MiB, the file is replaced by its last 8 MiB from a line's start, in one rename. At today's rate that is about a month of metrics and far more of the audit log.
  - An unauthenticated flood (rejected tokens, refused peers) can push out old metrics, never the audit log, which only authenticated callers write, and it can never fill the disk.
  - A write that fails, such as on a full disk, never fails the tool call or the metrics loop: the record stays in memory and the server's log says so once.
- **`latest.png`** holds only a screen the agent was given: the frame a screen tool returned, written when it returns it. The frames that arrive while the agent is idle, or while the owner drives the Flipper with the phone's remote, stay in memory only.
- **Conversations, their history and their attachments** are kept until the owner deletes them; each history keeps its last 2,000 events. Empty conversations go after 24 hours, and uploads never sent after an hour.
- **Claude Code's transcripts** live outside the state folder, under `~/.claude/projects/`, in plaintext. Claude Code deletes them after `cleanupPeriodDays`, 30 days by default (code.claude.com/docs/en/data-usage, "Data retention"). Deleting a conversation also removes its transcript.
- **`gate.json` and `device.json`**: one small file each, replaced on each change.
- **The notes per app** (`notes/<origin>/<appid>.json`): one file per note, mode 600 in folders of mode 700, kept until the owner deletes them with `zf notes delete`. They belong to apps, not to conversations: deleting a conversation leaves them.

## Keys

- **The release key** (`flipper-bridge.jks`, alias `flipper-bridge`):
  - kept by the maintainer in a safe backup and in the repository's secrets for *Release APK*; `android/scripts/release-apk.sh` reads it from the environment of the machine that builds, only when Actions cannot run;
  - its certificate's SHA-256 is printed in every release's notes: `5b063b1639be972474c0a755d3fb183caa5a9b12de260d6e7324cfbb132be31e`;
  - the script refuses an APK signed with another key, or debuggable;
  - only the maintainer uses it.
- **The server's TLS key** (`tls-key.pem` in the data folder, next to `config.json`): made by `zf`, mode 600, never copied anywhere. Its fingerprint is public: `zf status` and `zf key` print it, and `zf key --pin` prints the pin the pairing QR code carries.
- **The test app's key** is a different one, never in the repository (F15): the build reads it from `FB_PREVIEW_KEYSTORE_B64` and `FB_PREVIEW_KEYSTORE_PASSWORD`. A key once kept in the repository's earlier, private history, and its password, are burned; neither is in this repository.

## How security is reviewed

- **Each release is reviewed with `/security-review`** over `dev` against `main`, in a read-only Claude Code session that wrote none of the code.
- **The Claude Security plugin scan** ran twice before 1.0.0 (2026-10-26 and 2026-11-03): the whole repository at medium effort and 31 exposed files at high. The second found three findings, all fixed in 1.0.0 (S1–S3, below), and met again a known limit already listed: the user-scope MCP entry, and a loopback port another local user takes while the bridge is down (see "What is trusted" and "Tokens"). What the next scan covers is kept below.

## What the next scan covers

The last scan ran on 2026-11-03, before the fixes that went into 1.0.0, over the whole repository. After each merge, the maintainer adds a line here when the pull request changes what a token, the network, the agent or a key can reach.

- **1.0.0, the scan's fixes:**
  - server: `command._allow` (the kept rules in `--settings` `permissions.allow`), `wire.valid_rule`'s one-rule check, and `opencode.rule_for` from `request_paths` (S4, S5);
  - server: `stream.permission_input` and its bound, the broker's refusal of anything but deny for a request not whole, and `zf chat`'s permission prompt;
  - server: `chat/guarded.py`, `opencode.guards`, `request_paths` and `rule_for` (OpenCode's edits of the agents' own files, and a rule for one path only), `opencode.guard_problem` (the merged configuration's permissions, at each turn start), `command.ask_rules` in each turn's and Remote Control session's settings, and `permissions.guarded_request`;
  - app: the permission cards' rows (`PresentChat.toolRows`), and `Markdown.parse`'s depth caps and its walk without recursion;
  - CI: *Release APK*'s permissions per job and its build without a cache.
  - server: `privnet.lookup` and `_read_capped` (a subprocess per device, its output capped), `peers.py` (the devices list: what enters it, its bound, the background lookups), the hooks after each credential check in `phonews.py`, `appws.py`, `view.py` and `app.py`, and `/status`'s `devices`.
- **1.0.0, the notes per app:**
  - server: `notes.py` (the cleaning, the caps, the key and the files under `state/notes/`), `flipper_note` and the three read paths in `tools.py` (`note_text`, `find_note`, `notes_lines`, and `flipper_app_start`'s `note`), `zf notes`, and `CLAUDE_CODE_DISABLE_AUTO_MEMORY` in `command.base_env`.
- **Known limits to look at again:** the user-scope MCP entry (see "Tokens"); the private network's limits (see "What is trusted"); shell commands that write the agents' own files without naming them (see "The agent").

## Findings

From the Claude Security plugin scans of 2026-10-26 (11 MEDIUM, 9 LOW, none CRITICAL or HIGH) and 2026-11-03 (2 MEDIUM, 1 LOW), and the reviews after them, all before 1.0.0. The scans' reports are not published.

| Finding | What | State |
|---|---|---|
| F1 | The permission cards show the whole command | Fixed before 1.0.0 |
| F2 | `zf` checks the loopback listener before sending a token | Fixed before 1.0.0 |
| F3 | Attachment meta from disk is validated before use | Fixed before 1.0.0 |
| F4 | `zf chat` shows the exact command, without terminal control characters | Fixed before 1.0.0 |
| F5, F6, F9, F10 | The app sends tokens only through the private network's tunnel, and the public listener serves only private peers | Fixed before 1.0.0; the server's own key came later (see "What is trusted") |
| F7 | "Allow once" from the notification only for a request it shows whole | Fixed before 1.0.0 |
| F8 | The pairing is one atomic record | Fixed before 1.0.0 |
| F11 | The pairing is one atomic record | Fixed before 1.0.0 |
| F12 | Writing tools check the gate after waiting and between frames | Fixed before 1.0.0 |
| F13 | A bad history line never closes `/app` | Fixed before 1.0.0 |
| F14 | The owner confirms the server before a pairing is replaced | Fixed before 1.0.0 |
| F15 | The test app's key leaves the repository | Fixed before 1.0.0 |
| F16 | A wrong pairing code does not cancel the current one | Fixed before 1.0.0 |
| F17 | `zf` checks the loopback listener before sending a token | Fixed before 1.0.0 |
| F18 | The MCP token is on no command line | Fixed before 1.0.0 |
| F19 | "Always" saves only the rules the client showed | Fixed before 1.0.0 |
| F20 | Markdown drops bidi and format characters after decoding | Fixed before 1.0.0 |
| Review R1 | The probe gave its turns the MCP token after refusing a foreign listener (LOW) | Fixed before 1.0.0 |
| Review R2 | `/security-review`: F5, F6, F9 and F10 closed, no findings | — |
| Review R3 | `/security-review`: the `/view` cookie alone could read the pairing code and confirm a request (LOW). The key, the listeners, the pairing, the app's trust and the models' guard are sound | Fixed: a secret per pairing, returned only with the view token typed again |
| Review R4 | `/security-review`: no finding; two LOW notes: the remote could read a stale "stopped" while `/app` reconnected, and three device-text lines skipped `no_repo_tags` | Fixed |
| S1 (second scan, MEDIUM) | The permission cards show every argument of a request, whole, not only its command or path | Fixed before 1.0.0 |
| S1b (from S1's fix) | A permission request goes to the card whole up to 64 KiB; a longer one can only be denied | Fixed before 1.0.0 |
| S4 (scan of the S1–S3 fixes, MEDIUM) | A kept "always" rule can never be read by Claude Code as several, such as a bare Bash | Fixed before 1.0.0 |
| S5 (scan of the S1–S3 fixes, LOW) | OpenCode's "always" rule covers one path, never a move | Fixed before 1.0.0 |
| S2 (second scan, MEDIUM) | The agents cannot change their own configuration or instructions without the card, and OpenCode's configuration cannot allow what the bridge asks for | Fixed before 1.0.0 |
| S3 (second scan, LOW) | Deep Markdown cannot close the app | Fixed before 1.0.0 |
