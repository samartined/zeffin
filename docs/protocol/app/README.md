# The `/app` protocol: schemas and examples

The contract between the server and the Zeffin app on `/app`: one [JSON Schema](https://json-schema.org/draft/2020-12/schema) (draft 2020-12) per message and per chat event kind, and examples of each. This folder says what each message carries; the server's names for them are in `server/bridge/wire.py`.

| Folder | What | Names in the code |
|---|---|---|
| `server/<type>.json` | Server → app messages | `wire.Out` (server), `AppProtocol.Out` (app) |
| `app/<type>.json` | App → server messages | `wire.In`, `AppProtocol.In` |
| `events/<kind>.json` | The `chat.event` kinds, as kept in the history (`chat.history`) | `wire.Kind`, `AppProtocol.Kind` |
| `defs.json` | Pieces several messages share: modes, efforts, a conversation, an attachment, the screen, and `event` (a history event by its kind) | |
| `examples/<folder>/<name>.json` | One or more examples per type and kind, named `<type or kind>[.<variant>].json` | |

The server's names are in `server/bridge/wire.py` (and the metrics log's `ev` names in `server/bridge/events.py`); the app's are in `android/app/src/main/kotlin/dev/zeffin/link/AppProtocol.kt`.

## Rules

- Every message is a JSON object with a `type`. Chat events carry a `kind`.
- The server sends codes and data, never sentences: the app words them in its language.
- What the agent writes and what comes from the Flipper (texts, tool input and results, file names) is untrusted data. The schemas say what shape it has, not that it is safe.
- The schemas are strict (`additionalProperties: false`): **a new or renamed field goes into its schema first**, with an example, or the server's tests fail. The app's reading stays lenient, so an older app ignores a field it does not know.
- The audit and phone log items (`audit`, `phone` and their histories) are open records: only `ts` and `tool` or `ev` are fixed.

## How the two sides are checked

- Server (`server/tests/test_protocol.py`):
  - every schema is valid;
  - every example matches its schema;
  - every name in `wire.py` has a schema and an example, and the reverse;
  - the code lists in the schemas (errors, notices, problems, turn errors and so on) are `wire.py`'s;
  - `AppProtocol.kt` has the same names as `wire.py`.
- Server tests (`server/tests/conftest.py`): every message the server sends on `/app` in any test, and every one `zf chat` sends, must match its schema. Messages a test writes by hand to the server are not checked, because some are wrong on purpose.
- App (`ProtocolExamplesTest`):
  - the app reads every server example: none is dropped, and no chat event becomes `Unknown`;
  - `ChatOut` builds each app example exactly;
  - every error, problem and notice code has its own words.
  - The examples reach the app's tests as test resources (`android/app/build.gradle.kts`).

## Adding a message, a field or a code

1. Add the name to `wire.py` and `AppProtocol.kt` (codes too).
2. Write or change its schema here, and add an example to `examples/`.
3. Use it in the server and in the app.
4. Words for a new code go in the app's presenters (`ui/PresentChat.kt`, `ui/PresentRemote.kt`), in both languages, and in `zf chat` (`server/bridge/chat/client.py`).

## Renaming a code

A code is renamed in two steps, so the app always knows what the server sends.

1. The new name goes into the schema's enum next to the old one, with a description saying which server sends which. The app learns both names.
2. Once that app is merged, the server sends the new name (in `wire.py`), and the old one leaves the schema. Events the server kept on disk with the old name are sent with the new one. The app keeps reading the old name, for older servers.

The codes once named after Claude Code were renamed in server 0.14.0: `claude_missing` became `agent_missing` (`chat.status.problem` and `not_ready`'s `problem`), and `claude_failed` became `agent_failed` (`turn.end.error`, with the same data). `not_logged_in` and `not_subscription` keep their names: any agent has a login, and they mean `login.method` is null and `login.subscription` is false.

## The agents

`hello.agents` and `chat.status.agents` list every agent the server knows, in its order, Claude Code first: `claude-code` and `opencode`. `hello` has them as they are when the connection opens; `chat.status` brings each change (a check finished, a login, a new catalog). Each entry (`defs.json`, `agent`) is neutral data, with no field named after one agent:
- who it is (`id`, `name`, `version`), whether it is set up on this server (`configured`), and whether it can take a turn (`ready`, or its `problem`);
- its `login` (`method` in its own words, `subscription` decided by the server: `false` for an agent on providers, such as OpenCode);
- its `models`, `mode_options`, its remote access (`remote`, `null` when it has none: `chat.remote` then gets `remote_unsupported`) and whether messages to it may carry attachments (`attachments`).

Each conversation has its agent (`agent`), chosen with `chat.new` (without it, `settings.default_agent`) and kept for good. The models, modes and efforts a conversation offers are its agent's: `chat.set` and `chat.send` refuse any other with `bad_model`, `bad_mode`, `bad_effort` or `model_unavailable`, as before. `chat.new` with an id the server does not know gets `bad_agent`, with the ids it knows. The defaults of new conversations are per agent (`settings.agents`); `chat.settings` sets those of `agent` (without it, of the default agent).

`hello.agent`, `hello.remote` and `chat.status`'s `ready`, `problem`, `login`, `models` and `mode_options` were dropped when the list replaced them: the app and the server ship together at 1.0.0.

## The models

Each agent's `models` lists every model it lists, in its order. Each entry has `images` (it takes images: an image attachment, and the Flipper's screen in a tool result), `paid`, `available` and, when the model is not available, a `reason`: `usage_credits` (the agent says it would bill usage credits), `unchecked` (its list cannot prove the owner's subscription covers it) or `not_signed_in` (on a provider the agent is not signed in to). A request with a model that is not available gets `model_unavailable`, and no turn starts. Fable is one entry of Claude Code's `models`, like any other model: server 0.14.0 dropped `chat.status.fable`.

`paid` says the model may bill per use: the agent says it bills usage credits or cannot prove the subscription covers it, the login is not a subscription (an API key), or its provider prices it above 0. The app says "Paid" on its row.

## Free only

`chat.status.free_only` (and `hello.free_only`, with the chat) is the "free only" switch (`zf set-free-only`, off by default; paid usage is the user's choice unless it is on):
- **on:** Claude Code runs only with a subscription login and the models it covers (`usage_credits` and `unchecked` are not available), OpenCode only on its free providers and free models: no available model is `paid`;
- **off:** every model on a login or provider the agent has is available, and `paid` marks those that bill. No provider is favoured: OpenCode lists the models of the providers signed in first, in its own order, then OpenCode Zen's free models (no account); a provider nobody signed in has no rows, so no model is unavailable. A new conversation's default is the first available model.

An agent on providers names them in `login.method`, those signed in first, each by a readable name (OpenCode Zen, NVIDIA NIM, OpenRouter, and so on) or, for one the server does not know, its id.

The app words its promises by it: it says "free" of an agent or a list of models only while it is on. It reads a missing field (a server before 1.0.0) as off, and a missing `paid` as false.

The protocol version is `hello.protocol` (`wire.PROTOCOL`, today 1). The app keeps the server's in its state. Adding optional fields or new codes does not change it; a change an older app would misread does.

## The server's home folder

`hello.server.home` (1.0.0, optional) is the server user's home folder. The app writes it as `~` where the permission cards and the permission notification show a path (`file_path`, `path`, `notebook_path`, and OpenCode's `filepath`) and in the "Always allows" rules: only for that folder itself or a path inside it, never for one that merely starts with the same letters (`/home/demo2`). Display only: the answer sends exactly the rules the server offered.

## Too many images for the model

`turn.end`'s `image_limit` (1.0.0) ends a turn whose conversation passed the images its model takes in one request: NVIDIA's hosted API refuses 14 or more, and the agent sends the whole conversation each time. The server then has the agent summarize the conversation, which drops its images, and says in `data` how many it held (`images`), the model's `limit`, and whether the summary worked (`compacted`): true, the next message goes on; false, only a new conversation does. The server also summarizes before a turn once the conversation holds half the limit, with a `notice` `compacted`. An app that does not know the code shows its message for an unknown error.

## A request too long to show whole

A permission request's `input` is whole up to 64 KiB of JSON, the bound of the agent's own text events (`stream.PERMISSION_INPUT_LIMIT`). Past it, the server sends it cut, as a tool event's (each string at 2,000 characters, then `...(+N)`), with `whole: false`, `always: false` and no rules: the cards say it is too long to show and offer only Deny, the notification never offers Allow once, and the server answers any other answer to it with `bad_request`.
