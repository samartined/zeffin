# Architecture

This is how the code is organised and what may depend on what.
- Tests enforce the rules (see "Enforcement").
- A change to these rules comes with its reason, in the same pull request as the code and tests that hold it.

The system has three parts, joined by network contracts:
- the **server** (Python, `server/bridge/`);
- the **Android app** (Kotlin, `android/`);
- **the agents** the server runs: Claude Code, and OpenCode over ACP, chosen per conversation.

`/app`'s contracts are JSON Schemas in [protocol/app/](protocol/app/), and the Flipper's RPC link's are in [protocol/rpc/](protocol/rpc/README.md). The others live next to their code:
- `/phone`: the docstring of `phone.py` and `RelaySession.kt`;
- the `claude -p` command line: `chat/command.py`;
- ACP, as the bridge speaks it with OpenCode: `chat/acp.py` and `chat/opencode.py`;
- Claude Code's hooks: `chat/permissions.py` and `chat/remote.py`;
- the metrics: `events.py`;
- what is kept on disk: `config.py` and `chat/index.py`.

## Server (`server/bridge/`)

Six layers. A layer imports only from the layers in its last column. The full module list is `LAYERS` in [`server/tests/test_layers.py`](../server/tests/test_layers.py), and a new module goes there in the pull request that creates it.

| Layer | What it holds | Main modules | May import |
|---|---|---|---|
| Contracts | `/app` names and codes, `ProtocolError` (a malformed `/app` message; `ChatError` stays for a well-formed request the chat cannot serve now), `Mode` and `Effort`, the gate's states (`ACTIVE`, `PAUSED`, `STOPPED`) and `GATE_ACTIONS`, the `SEQ` frame key; the metrics `ev` names and `PHONE_EVENTS`; cutting and cleaning untrusted text; a one-colour PNG; the fan-out of one update to many bounded queues; the `Agent` port (below); `zf`'s check that a loopback listener is its own user's before a token goes to it; the private network (`privnet.py`: its ranges and `is_private`, which peers the public listener serves, how the server finds its own address, a device's name on it, and the texts naming the network) | `wire.py`, `events.py`, `text.py`, `png.py`, `fanout.py`, `agent.py`, `localpeer.py`, `privnet.py` | nothing from `bridge` |
| Persistence | config and tokens; the server's TLS key and certificate, its pin and fingerprint; the operator gate, the JSONL logs (each kept to its newest 8 MiB) and atomic writes; the figures read back from the logs; the pairing session (the rotating code, the one pending request, the owner's confirmation) and what its QR code holds; the devices that used the public listener since the server started, in memory only; conversations, their history and attachments; the device cache, the agent's notes per app, the firmware profiles and the app catalogs (and the apps' READMEs from a local source tree) | `config.py`, `tlskey.py`, `gate.py`, `stats.py`, `paircodes.py`, `peers.py`, `devicecache.py`, `notes.py`, `profiles.py`, `catalog.py`, `chat/index.py`, `chat/history.py`, `chat/attachments.py` | contracts |
| Device | the relay session (`PhoneSession`, the `/phone` contract, building RPC messages, the `command_id` ranges and the drop of the relay's replies); `Hub`, the device's shared state and its admission policy (`ops_lock`: one call runs, one waits); frames and the screen; the reaction loop of `flipper_watch`; the firmware detection | `framing.py`, `phone.py`, `hub.py`, `screen.py`, `watch.py`, `firmware.py`, `pb/` | contracts, persistence |
| Agent integration | everything specific to an agent, behind the `Agent` port. Claude Code: `claude_code.py` (the port's implementation), the command line and its environment, the `stream-json` parser, the Remote Control process and its terminal, the model catalog, the hook decision, the probe and the redaction of its fixtures. OpenCode: the generic ACP client, `opencode.py` (the port's implementation, its guards and its free models, with the curated list `free_models.json`) and its probe | in `chat/`: `claude_code.py`, `command.py`, `stream.py`, `remote.py`, `terminal.py`, `catalog.py`, `permissions.py`, `probe.py`, `probe_remote.py`, `redact.py`; `acp.py`, `opencode.py`, `probe_opencode.py`; `guarded.py` (the agents' own configuration and instructions, which both guard) | contracts |
| Chat services | conversations, turns and permissions, and one turn at a time on the whole server | in `chat/`: `manager.py` (the facade), `service.py`, `turn.py`, `remote_runner.py`, `broker.py`, `slot.py`, `bus.py`, `ports.py`, `tasks.py`, `errors.py` (below) | contracts, persistence, agent integration |
| Adapters | the edges: HTTP, WebSocket, MCP and the CLI. `app.py` and `cli.py` are the composition roots | `app.py`, `appws.py` (`/app`), `phonews.py` (`/phone`), `view.py` (web view), `tools.py` (MCP over the device), `pairing.py` (the pairing routes), `web.py` (Starlette helpers), `sim.py` (the Flipper simulator), `cli.py` (`zf`), `report.py` (what `zf report` and `zf status` print), `systemd.py` (the service's unit and its `systemctl` calls), `chat/client.py` (`zf chat`, and its guided test) | everything above |

**Rules that are easy to break,** each with what holds it:
- **Chat services never import** `phone`, `hub`, an adapter, Starlette, MCP or uvicorn (test: `test_layers.py`).
  - From the device they receive two ports: **kept frames** (to put a screen in a tool result) and **write a metric**.
  - `ChatManager` builds them once from the `Hub` it is given (`DevicePorts` in `chat/ports.py`); the pieces behind it only see the ports.
- **Agent integration imports only contracts** (test: `test_layers.py`). It is what the `Agent` port wraps.
- **Only adapters know Starlette, MCP or uvicorn** (test: `test_layers.py`, for every layer below the adapters).
  - The `/phone` handler is the adapter `phonews.py`, like `appws.py` for `/app`; `phone.py` keeps the session.
- **Adapters import other adapters only from the two composition roots, `app.py` and `cli.py`** (test: `test_layers.py`).
  - **The one exception is `web.py`:** the Starlette helpers every adapter may import (today `deny`, which refuses a WebSocket with a real HTTP status for `/phone` and `/app`).
  - The pairing code store and its QR payload are `paircodes.py`, below the adapters, so the web view and the pairing routes share it without importing each other.
- **Protocol names come from `wire.py` and `events.py`** (test: `test_protocol.py`).
  - The chat's modules, `appws.py`, `view.py` and `stats.py` write none as a literal.
  - Anywhere else, a literal event kind, error code or metrics `ev` must be a name those files know. `attachments.py` and `tools.py` are exempt, because "kind" and "code" mean other things there.
- **The public listener serves only private-network or loopback peers** (tests: `test_privnet.py`).
  - `PrivatePeers` in `app.py` is its outermost layer, around `BodyLimit`: any other peer, or one the server cannot tell, gets 403 (a WebSocket, a denial response through `web.deny`) before the body limit, routing or any token is read. Each refusal is a `peer_refused` metrics line, rated (`RefusalLog`: one per peer a minute, 20 a minute in all, the next line counts the rest).
  - The peer is the kernel's address: `listener_config` builds both listeners with `proxy_headers=False`, so no `X-Forwarded-For` header can rewrite it, and the bind check (`config.check_public_host`) stays.
  - What is private, how the server finds its own address and every text naming the network live in `privnet.py` only: its ranges, its command and its name (comments and docstrings included) appear nowhere else in `bridge/`. Another private network is another implementation of `privnet.py`'s functions.
- **Untrusted text** (from the Flipper or the agent) is cut and cleaned with `text.py` where it enters (by review).

### The chat, split

`ChatManager` (`chat/manager.py`) stays as a facade with today's methods, so `appws.py`, `app.py` and `zf chat` don't change. It wires the pieces and routes each request. Behind it:

| Piece | Owns |
|---|---|
| `ChatBus` | the listeners, emitting to them, the durable and droppable queues, overflow |
| `TurnSlot` | **one turn at a time on the whole server**, decided in one place. The two runners register as its owners when they are built, and each keeps its own turn; the slot asks them, so it never drifts from them. `claim(owner)` → none, or `ChatError` busy (any turn runs) or stopping; `other(owner)` (a turn another owner runs); `release(owner)`; `current()` (owner, conversation); `info()` for `chat.status` |
| `PermissionBroker` | the permissions held for `-p` turns, the hook handlers, answers and expiry |
| `TurnRunner` | one agent turn: start, stream, events, end; and the compaction of a model that takes few images |
| `RemoteRunner` | the conversation in Remote Control: the interactive process, its hooks, our typed messages, its watchdog |
| `ChatService` | conversations (new, open, configure, update, delete), settings, the catalog and checking requests; it creates `TurnSlot` and hands it to both runners |

`TurnSlot` is its own piece because the one-turn rule is read from both runners and from `ChatService`; inside `ChatService` it would make a cycle.

Who does what across the pieces:
- **Saving and emitting:** the runners and the broker change the `Conversation` they were given and report through callbacks the facade wires. Only `ChatService` saves the index (`changed(c, status=True)`), and only `ChatBus` emits.
- **Readiness:**
  - `ChatService` owns whether the agent is available (`problem()` and the `check_claude` loop) and the recovery when the server starts;
  - `stop()` stays in the facade.
- **The kept frames port** goes to both runners: the `-p` turn and the Remote Control hooks both attach a screen.

### The `Agent` port

`agent.py` is the port, in the contracts layer. Two implementations, in the agent integration layer: `chat/claude_code.py` for Claude Code and `chat/opencode.py` for OpenCode. The composition root (`app.py`) builds both from the `Config` and hands them to `ChatManager`, so the chat services never import agent integration and a new agent is one more implementation.
- **Two ways of working:**
  - **a turn per process:** `TurnRunner` asks the port to start a turn and reads its events (`Started`, `Delta`, `Output`, `PermissionAsk`, `TurnEnd` and the rest of `TurnEvent`);
  - **an interactive session followed by hooks:** `RemoteRunner` asks the port to start, type into and stop it, and reads its hooks as `RemoteHook`.
- **The events are already the bridge's vocabulary** (`wire.py`'s chat events): the wire is the bridge's, not the agent's.
- **What each agent offers is data:** `hello.agents` and `chat.status.agents` list every agent the port has an implementation of, each with its `id`, name and version, readiness, login, models, modes, remote access (whether attachments work with it) and attachments; the app reads them instead of knowing an agent. The composition root hands `ChatManager` every agent; each conversation keeps its agent's `id` (`chat/index.py`), and what it offers is its agent's.
- **`TurnSlot` and `PermissionBroker` stay on the server's side of the port:** they are the bridge's rules, not the agent's.
- **The "free only" switch** (`Config.free_only`, off by default: paid usage is the user's choice) is read live by each integration; its "on" state is the "never pay" guards. Each catalog entry says whether the model is `paid`.
- **Claude Code asks for the same files:** each turn's and Remote Control session's inline settings carry ask rules (`command.ask_rules`) for `CLAUDE.md`, `AGENTS.md` and OpenCode's files, which Claude Code's own protected paths leave out; its hook's request for a guarded file offers no "always" (`permissions.guarded_request`).
- **The subscription guard is Claude Code's,** with the switch on: `claude_code.py` kills a turn whose start reports an API key, a 1M-context model or a model that bills usage credits, and `command.py` keeps billing variables out of its environment. Off, the user's login runs; never `--bare`.
- **The second implementation, OpenCode over ACP:**
  - `chat/acp.py` is a generic client of the Agent Client Protocol: JSON-RPC 2.0 over the agent process's stdio (`Connection`), and `Updates`, which turns `session/update` into the port's events (`Delta`, `Output` with `flipper` and `seq` for the bridge's own tools). What the agent and its tools write is cut where it enters, as `stream.py` does for Claude Code.
  - `chat/opencode.py` runs one `opencode acp` process per turn: `initialize`, `session/new` or `session/load` (to go on with a session; its replayed history is not shown again), the model, mode and effort as session config options, then `session/prompt`; `session/cancel` interrupts it. The bridge's MCP server and its token go in `mcpServers`, through the pipe, never in the environment or on a command line. A conversation keeps OpenCode's own session id (`agent_session` in `conversations.json`); deleting it runs `opencode session delete`.
  - **Its guards** are OpenCode's integration's, each held by a test. Always: the process's environment is an allow-list; the agent folder's `opencode.json` (written by `zf agent setup --agent opencode`, and again by `zf set-free-only`) turns sharing and auto-update off and lets the `flipper_*` tools run, and OpenCode's merged configuration (`opencode debug config`) is checked on every agent check (every 10 minutes) so a global file cannot undo it; a session on another model than the conversation chose is killed at once, before its prompt or on a `config_option_update`; the version is pinned to the range the trial checked; a tool call is the bridge's own only when its title is exactly one of the bridge's MCP tools (`MCP_TOOLS`); the merged configuration's permissions (top level, and each agent's and mode's) may not allow what the bridge's `opencode.json` asks for (bash, edit, webfetch, external_directory), checked again when each turn starts, whatever the switch; an edit of a path `guarded.py` lists (`opencode.json`, `.opencode/`, `AGENTS.md`, `CLAUDE.md`, `.claude/`, `.mcp.json`, OpenCode's global folder), or one whose paths the request does not state clearly, always goes to the card, without "always": neither Accept edits nor a kept rule answers it.
  - **With the switch on, the "never pay" guards:** `opencode.json` allows only OpenCode Zen and NVIDIA NIM, the merged configuration may name no other provider or model and no agent or mode with a model of its own, both version and configuration are checked again when each turn starts, and only free models are offered (Zen's priced at 0, NIM's from `free_models.json`). Off, every provider OpenCode lists is offered, with each model's price as `paid`.
- **A model that takes few images:** `Agent.image_limit(model)` says how many images the model takes in one request when its provider caps them (OpenCode: `free_models.json`'s `max_images`, 13 for NVIDIA's free Nemotron; Claude Code: none), and `Agent.compact(request)` starts a turn of its own that replaces the session's history with a summary (OpenCode: the prompt `/compact`, with `compaction.tail_turns` 0 in `opencode.json`, so no image stays). `TurnRunner` counts the images the agent's session holds (`images` in `conversations.json`: the message's, then each tool result's), compacts before a turn that starts with half the limit or more (the `compacted` notice), and after a turn that failed past the limit, which then ends with `image_limit` (`images`, `limit`, `compacted`). The summary's own text is not shown.
- **The permission path, for an agent without hooks:** `TurnRequest.ask` hands a permission request from inside the agent's protocol (`session/request_permission`) to `PermissionBroker.ask`, which holds it with the same card, expiry and refusals as a hook's, and returns a `PermissionAnswer` (once, always with the granted rules, deny, or the bridge's own endings). The integration maps it to the agent's options (`allow_once` or `reject_once`, or cancelled): "always" is answered `allow_once`, and the integration keeps the granted rule and answers it itself for the rest of the turn (later turns get it in `TurnRequest.rules`), since OpenCode's own `allow_always` patterns are broader than the card's rule. OpenCode's `doom_loop` request for a Flipper tool repeated with the same input is answered allow once by the integration itself, with no card and no rule (`opencode.flipper_doom_loop`, which accepts only 1.19.5's exact shape, the inner tool read as `tool_name` reads a title); for any other tool it is a card. The broker and `TurnSlot` stay in the chat services; Claude Code's hook path goes through the same `PermissionBroker.ask`.

**Adapters do not reach agent integration directly:** `appws.py` takes the agent's capabilities and `app.py` the hook routes from the `ChatManager` facade (`capabilities()`, `hook_routes()`).

## Android app (`android/`)

| Module or package | What it holds | May import |
|---|---|---|
| `core` | settings, encrypted tokens, addresses, pairing, `Lang`, text cleaning; the private network's boundary (`PrivateNetwork`, with `TailscaleNetwork` as its implementation) and `ServerHttp`, the only holder of an HTTP client to the server and of its TLS trust, the pairing's pinned key (below) | — |
| `relay` | the relay: the engine (pure, with ports), Bluetooth (`relay.ble`), the server socket, the service. **Its public face is `RelayController` and its data types** (`RelayState`, `Stage`, `Problem`, `Detail`, `FlipperFailure`, `NetError` with `describeNetworkError`, `LogEntry`, `LogLevel`, `RelayStrings`, `CheckResult`, `RadioState`, `BondedDevice`) | `core` |
| `app/link` | `AppLink` (`/app`), `ChatStore`, `AppProtocol` and the protocol model, the pairing exchange, `FlipperFrame` (the framebuffer's decoding and colors) | the relay's data types, `core` |
| `app/attach` | the tray, uploader, scaler and thumbnails; `AttachFlow` (pure: what a conversation does with its attachments) | `link`, the relay's data types, `core` |
| `app/notify` | `Notifier` (pure rules), the notification service and sink, and the notifications' words (`PresentNotify`) | `link`, `ui/present`, the relay's public face, `core` |
| `app/ui/present` | presenters and words: state → `*Ui`, in English and Spanish, with no Compose; the `*Ui` models, `Tone` and `Fmt` | `link`, `attach`'s data types (`Upload`, `AttachError`), the relay's data types, `core` |
| `app/ui/routes` | one file per route: what the route collects from `AppGraph`, its own launchers, and its state in `rememberSaveable` (never a token or a pairing code) | everything above, and `ui` |
| `app/ui` (`AppNav`, screens, components, theme) | Compose. `AppNav` holds only the nav host and the route table | everything above |
| `AppGraph`, `MainActivity` | the composition root: no DI framework, no ViewModels | everything |

**Rules:**
- **No Compose screens outside `ui`.** `link`, `notify`, `attach` and `ui/present` never import `ui.screens`, `ui.components` or `ui.theme`, and `ui/present` imports no Compose at all.
- **No relay internals outside `relay`.**
  - Nothing outside it imports `relay.ble.*`, `OkHttp*`, `RelaySession`, `RelayEngine` or `RelayService`.
  - `RelayController` offers what the rest needs: `checkServer(base, token)`, `radioState()`, `bondedDevices()`, and `EXTRA_OPEN` with `OPEN_RELAY` for the screen to open.
- **No cycles between the packages:** `ui/present` never imports `notify`, and `attach` never imports `ui/present`.
- **Only the classes Android creates use `graph`:** `MainActivity`, `NotifyService` and `NotifyReceiver`. Nothing else does; routes get `AppGraph` from `AppNav`.
- **The chat lives on the main thread.** `AppLink` hands on what is not state as `events`, a `SharedFlow` that keeps nothing for a late subscriber. `AppGraph` collects it on the main thread before the link can connect, so `ChatStore` and `Notifier` are touched only there, and `ChatStore` has no locks. Disk reads stay on IO.
- **Logic that decides is pure and tested on the JVM:** what to notify, how to attach, what a screen says. Screens only draw.
- **One client to the server:** no `OkHttpClient` outside `core/ServerHttp.kt` (test: `ServerBoundaryTest`). Every request that carries a token, a pairing code or chat content goes through it, so the private network's rule cannot be gone around.
- **The private network is named only in `TailscaleNetwork`:** its ranges and its name, in Kotlin, resources and manifests; the words take the name from the boundary, and `CoreGraph` only chooses the class (test: `ServerBoundaryTest`).

`AppLayersTest` and detekt hold these rules (below).

## The private network's boundary

- **Where it lives:**
  - app, in `core`: `PrivateAddresses` (pure: `name`, `ranges`, `isPrivate(host)`), `PrivateNetwork` (`route(host)` → through a network or down, `changes`, `ownAddress()`), `TailscaleNetwork`, and `ServerHttp`, the only holder of `OkHttpClient`;
  - server: `privnet.py` in contracts (what is private, how the server finds its own address, the texts naming the network), and `PrivatePeers` in `app.py`, outermost on the public listener only.
- **What may not go around it:**
  - no `OkHttpClient` outside `ServerHttp`; no literal or word of the network outside `TailscaleNetwork` and `privnet.py`;
  - the app's server clients never use a proxy (`Proxy.NO_PROXY`);
  - only a VPN route inside the private ranges counts (`chooseRoute`): a default route or a wider prefix (a full-tunnel VPN) never does;
  - the network is chosen when the socket connects, by its destination: each socket binds to the covering VPN network or is refused. Every client is TLS with the pinned key (below), still bound the same way;
  - the decision comes before any byte, header, token or pairing code; production has no switch, flag or debug build that turns it off, and the fake boundary lives in test sources only;
  - on the server, the peer is the kernel's address only (`listener_config`, `proxy_headers=False`), and the bind check stays.
- **Later:** another private network is another implementation of the same boundary, chosen in `CoreGraph` and behind `privnet.py`'s functions. The pinned server key is below.
- **Known limits:** both sides trust the kernel's peer or route (security.md).

## The server's key

- **Where it lives:**
  - **app, in `core`:**
    - `ServerAuth.PinnedKey` in the pairing record (`PairingStore` version 2, one encrypted record);
    - `ServerKeys`, wired in `CoreGraph`, gives the key only for the pairing's own server (`sameServer`, scheme included);
    - the TLS trust lives only in `ServerHttp`: a trust manager and an `SSLContext` per pin, TLS 1.3 only, no CA, no host name check, `Proxy.NO_PROXY`, and every socket still bound to the tunnel;
  - **server:**
    - `tlskey.py` in persistence: the key and certificate files, `0600` and written atomically, the pin and the fingerprint;
    - TLS 1.3 in `listener_config`, on the public listener only;
    - the pairing session in `paircodes.py`, and its routes in `pairing.py`, `view.py` and `cli.py`.
- **What it exposes:**
  - the public listener speaks only TLS 1.3 with that key;
  - the QR code `{"fb": 3, "url", "code", "key"}` holds no secret;
  - `zf status` prints the fingerprint, and `zf key` the fingerprint and the pin;
  - `/status` gives the running pin.
- **What may not go around it:**
  - no TLS trust API outside `ServerHttp`, and no cleartext anywhere in the app;
  - no QR code or pasted pairing text below version 3, and only `PairingQr` reads the payload;
  - the code is sent only after the handshake has proved the pinned key;
  - the tokens leave only after the owner confirms a request by its `ref`: once, over the same pinned channel, with one pending request at a time;
  - the QR code's key is the running listener's, held in memory from the start;
  - the key is never replaced silently, and a new key means pairing again;
  - the loopback listener stays cleartext and local, behind the uid check.
- **Later:**
  - mTLS is a client key in the same record and a new peer predicate on the server;
  - a rotation signed by the old key is a `fb: 4` field and a second `ServerAuth` value.
- **Known limits** (security.md):
  - the TLS handshake comes before `PrivatePeers`;
  - `/view` in a browser is not pinned, and the owner compares the fingerprint;
  - the QR code is as trustworthy as the screen that shows it.

## The relay's own use of the link

- **One RPC stream, two clients:** the server and the relay share the Flipper's one RPC session, told apart by `command_id` range (`docs/protocol/rpc/README.md`; CLAUDE.md, "Contracts not to break"). The server drops the replies in the relay's range before any request or `flipper_events` sees them.
- **The relay never touches the server's bytes:** every chunk from the Flipper goes to the server first, unchanged and in order; `relay/rpc/`'s reader works on a copy and its errors stop only itself. It syncs on the server's settle stop, matched whole (`SettleMatch`).
- **One writer:** the server's frames go first, each with its `written`; a key of the remote goes only when no server frame waits and no server command is open, never with a `written`, one at a time, and only after it reads back as its three requests.
- **Without a server session** the engine holds the Flipper for the remote and never dials `/phone` while it is open (`Stage.REMOTE`, `RemoteLinkSession`).
- **Where it lives:** `relay.rpc` and the sessions are internal to the `relay` module (detekt `ForbiddenImport`, `AppLayersTest`); the app sees `RelayController.remote` and its data types (`RelayFrame`, `RemoteKeys`, `KeyRefusal`, `FlipperKey`).
- **Held by tests:** `vectors.json`, read by `test_rpc_vectors.py` and `RpcCodecTest`, ties both sides' bytes; `e2e.sh` checks that the remote's keys never reach `flipper_events`.

## The firmware knowledge

- **Detection** (device: `firmware.py`): eight small `property_get_request` commands after the stream starts, each once `ops_lock` is free and without holding it; a run belongs to its session.
- **What is kept** (persistence: `devicecache.py`, `state/device.json`): the firmware's fields and the apps learned per build, one level of subfolders deep.
- **The notes per app** (persistence: `notes.py`, `state/notes/<origin>/<appid>.json`): the agent's own notes on driving one app, one file per note, keyed by the firmware's origin (reduced to `[a-z0-9-]`) and the `appid`, with the build recorded; cleaned and capped where they enter and again when read. `Hub.notes` holds the store; `zf notes` reads and deletes the files directly. `Config.notes` (`zf notes off|on`) turns them off.
- **Repository data** (persistence: `profiles.py`, `catalog.py`, `profile_data/`): a base profile and one per firmware, and one generated app catalog per firmware (`server/scripts/build_app_catalog.py`), matched to the installed apps by `appid`.
- **The tools** (adapters: `tools.py`): `flipper_firmware` and `flipper_app`; device text and third-party text stay inside their labelled blocks (`untrusted`, `no_repo_tags`). `flipper_note` writes a note (gated, never waiting for `ops_lock`); `flipper_firmware` lists the installed apps with one, and `flipper_app` and `flipper_app_start` (with a `.fap` path) hand it back as untrusted data, never in the agents' instruction files.

## Enforcement

- **Server:**
  - [`server/tests/test_layers.py`](../server/tests/test_layers.py) holds the module → layer table and checks every import (inside functions too) against the rules above, with one test per rule.
  - A rule the code does not hold yet is listed in its `PENDING` with the step that fixes it. A test fails once an entry is no longer needed, so the list only shrinks.
  - Today it is empty.
  - `tests/test_protocol.py` checks the protocol literals (above), and that the schemas, `wire.py` and `AppProtocol.kt` agree.
  - `tests/test_mcp_snapshot.py` compares the MCP tools the agent sees with `tests/fixtures/mcp_tools.json`; after a deliberate change, `UPDATE_MCP_SNAPSHOT=1 pytest tests/test_mcp_snapshot.py` rewrites it.
  - `tests/test_properties.py` and `tests/test_malformed_messages.py` hold the `hypothesis` properties (framing, `StreamParser`, `text.py`, malformed `/app` messages); `tests/hypo.py` fixes their examples and count.
- **App:**
  - [`AppLayersTest`](../android/app/src/test/kotlin/dev/zeffin/AppLayersTest.kt) holds the package rules and the `graph` rule, with a package → forbidden table like `test_layers.py`'s; a new package goes in it.
  - detekt `ForbiddenImport` in `android/config/detekt.yml` holds the relay rule: one scope (everything outside the `relay` module), which fits detekt's one instance per rule.
  - `ProtocolExamplesTest` parses every `/app` example.
  - `ServerBoundaryTest` holds the one-client rule and the private network's literals in the main sources of the three modules. It also holds three more: the TLS trust APIs only in `ServerHttp`, no cleartext in the manifest, and the pairing payload read only by `PairingQr`.
- **Checks:** the local checks run all of it before each push (`CLAUDE.md`, "Checks before pushing"). The *Tests* workflow repeats them on every pull request to `dev` and `main` that is not a draft, for what it touches, and by hand.
- **Every release's final review checks:**
  - `test_layers.py`, `AppLayersTest` and detekt green;
  - `PENDING` empty or explained;
  - no new protocol literal outside `wire.py` and `events.py`;
  - the private network's boundary: one client (`ServerBoundaryTest`), and no literal of the network outside `privnet.py` and `TailscaleNetwork` (`test_privnet.py`, `ServerBoundaryTest`);
  - the server's key: TLS trust only in `ServerHttp` and no cleartext in the app (`ServerBoundaryTest`), TLS 1.3 only on the public listener and the key files' mode (`test_tls.py`, `test_tlskey.py`).
  - `/security-review` over `dev` against `main`, in a read-only Claude Code session of its own that wrote none of the code.
