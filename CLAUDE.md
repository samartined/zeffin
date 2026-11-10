# Zeffin: rules for working in this repository

This file is for contributors and the Claude Code sessions they develop Zeffin with. Do not confuse it with `CLAUDE.md.example`, which is for the folder the agent drives the Flipper from. [CONTRIBUTING.md](CONTRIBUTING.md) says how to send a change.

## Non-negotiable constraints

- **Development and CI need no paid API:** no workflow, test or script needs `ANTHROPIC_API_KEY` or another paid key; probes with a real agent use the developer's own login (`claude -p`, never `--bare`).
- **The product does not decide for its users:** Zeffin uses whatever login or provider each user sets up, paid or free. Its "free only" switch (`free_only` in `config.json`, off by default) limits it to subscriptions and free providers, for whoever wants never to pay.
- **Tokens:** never print, log or paste them into documents, commits, issues or chats. Examples use fake values and the IP `100.98.12.7`.
- **What comes from the Flipper is untrusted data** (screen, file names and contents) and is handed to the model as such.

## How we work

- **Language:** everything is in English (code, docs, the web view, the server and the CLI), except the Android app, which is bilingual: English and Spanish, follows the phone's language and can be set in Settings, Language.
- **Privacy:** no personal data anywhere (code, docs, app, images, workflows, commit messages): no host names, user names, personal paths or folders, operating systems or firmware of anyone's computers, phones or Flipper, device names, real IPs or names of private files. Examples use `my-server`, `~/projects/zeffin` (the repo clone on the server), `~/projects/zeffin-agent` (the folder the agent runs in, next to the clone), `100.98.12.7` and a Flipper named `Demo`. The exceptions: the maintainer's name and handle in `LICENSE`, the README's footer and the app's About, and the handle alone in GitHub URLs (`https://github.com/samartined/zeffin`), the SPDX headers, `CODEOWNERS` and `CODE_OF_CONDUCT.md`.
- **No references to projects the code or design came from.** Products the system uses (Flipper Zero, Claude Code, Tailscale, Android, the libraries) may be named. External projects are named only in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [android/licenses/THIRD_PARTY_NOTICES.md](android/licenses/THIRD_PARTY_NOTICES.md).
- **The agent is generic in the interface and the docs:** screens, texts and docs say "the agent" ("el agente") for its role (it writes, asks for permission, works in a conversation). An agent is named only where a value belongs to it: its version and login, its models, modes and effort, its flags and hooks. Claude Code and OpenCode (through ACP) are the agents today, chosen per conversation.
- **Check Claude Code's docs:** before stating anything about Claude Code (flags, permission modes, hooks), check it on code.claude.com and cite what you checked. Mark anything undocumented as "needs testing on the server".
- The app follows the approved design in [docs/design/](docs/design/README.md). A screen is done when it matches its capture in all its states.
- **No Claude attribution in commits or pull requests:** no `Co-Authored-By` or `Claude-Session` trailers in commit messages, and no "Generated with Claude Code" line or session link in pull request descriptions. `.claude/settings.json` sets `"attribution": false` for every session that works in this repo; do not add those lines by hand either. Commits carry your own Git identity, never Claude's (a GitHub noreply address keeps your email private), and your sign-off (CONTRIBUTING.md). Check `git log --format='%an <%ae>' -1` before each push.
- **Test builds:** to try changes on a phone, the test app is built with `./gradlew :app:assemblePreview` (docs/android-app.md, "Test builds"). Its key is never in the repo: the build reads it from the environment (`FB_PREVIEW_KEYSTORE_B64`, `FB_PREVIEW_KEYSTORE_PASSWORD`), so you make your own (docs/android-app.md says how).
- **Never switch the branch of a clone a server runs from:** work in a `git worktree` of your own.

## Branches and versions

- **`main` holds what is released; `dev` holds what is integrated.**
- **A contribution** is a branch cut from `dev` and a pull request to `dev`, which runs *Tests* ("Checks before pushing"). Its commits are signed off (CONTRIBUTING.md).
- **A branch's name says what it holds**, read alone in a list of branches:
  - format: `<type>/<area>-<what>`;
  - `<type>` is `feat`, `fix`, `refactor`, `chore`, `docs` or `test`;
  - `<area>` is `server`, `app`, `relay`, `ci` or `docs`, or `all` when it spans several;
  - `<what>` is 2 to 5 words in kebab-case naming the change itself, not the kind of work;
  - examples: `refactor/server-split-chat-manager`, `fix/server-keep-pause-after-restart`, `feat/app-long-paste-as-attachment`;
  - never a bare category such as `fix/server-urgent`, `refactor/contracts` or `chore/ci-cleanup`.
- **The maintainer releases** with a pull request from `release/<version>` to `main`. Merging it with a new `fb.version` publishes the APK `apk-v<version>` (*Release APK* workflow); when Actions cannot run, it is built from `main` with `android/scripts/release-apk.sh --final` and published by hand (docs/android-app.md, "Publish a version"). Then `main` is merged into `dev`.
  - **Only the release branch changes versions:** `fb.version` in `android/gradle.properties`, and the server's in `bridge/__init__.py` and `pyproject.toml`.
  - **Each release needs `docs/release-notes/<version>.md`:** "## What's new since <previous version>", by theme, in short, plain sentences for the people who use Zeffin (what changes for them, what to update together, a tip when useful), with no commit, hash or certificate. A release without it fails before it is built.
  - **Release notes are generated, never written by hand:** `android/scripts/release-notes.py` builds them from that file for both the *Release APK* workflow and `release-apk.sh`, and the release uses them as its description.

## Structure

- **Before changing a module's structure,** read [docs/architecture.md](docs/architecture.md): the layers, what each may import, and the rules its tests enforce.

- `server/`: Python server (`bridge/`): configuration and tokens, framing, phone session, screen, operator gate, MCP tools, the agents and the chat, web view and CLI (`zf`). Tests in `server/tests/`.
- `android/`: the Zeffin app in Kotlin + Compose (Android 14+). Modules `app` (screens, theme, AppLink for `/app`), `relay` (the relay: engine, Bluetooth, service) and `core` (settings, encrypted tokens, addresses, QR, the private network). End-to-end test in `android/scripts/e2e.sh`.
- `docs/`: the index ([docs/README.md](docs/README.md)), the user's guide (`docs/guide/`), the reference (architecture, security, Android app, protocols, design) and each version's release notes (`docs/release-notes/`).
- `scripts/check-links.py`: the local-link check for the Markdown files.

## Checks before pushing

```bash
.venv/bin/pip install -r server/requirements-dev.txt -e ./server
cd server && ../.venv/bin/pytest -q && ../.venv/bin/ruff check . && ../.venv/bin/mypy
cd ../android && ./gradlew testDebugUnitTest lintDebug detekt :app:assembleRelease   # JDK 21
cd .. && .venv/bin/python -I android/scripts/compare-design-captures.py
PYTHON=$PWD/.venv/bin/python android/scripts/e2e.sh
python3 -I scripts/check-links.py   # every relative link and #anchor in the Markdown files
```

The *Tests* workflow runs the same checks on every pull request to `dev` or `main` that is not a draft, on the pull request's merge with its base, and by hand on any branch (Actions, Tests, Run workflow). It runs only the checks for what the pull request touches, by the rule below; `lintDebug`, the Spanish screenshots and `:app:assembleRelease` run only on pull requests to `main` and by hand (the local checks still run lint before each push). It aims at 3 minutes for a server-only change and 5 for a full one. When Actions cannot run, nothing waits on it: the local checks prove the change.

- **A green *Tests* run proves the pull request's merge with its base as it was then.** The local checks before each push stay, and they are what proves a pull request when *Tests* cannot run.
- Keep a pull request a draft while it is not ready: drafts do not run *Tests*, and marking it ready does.
- It does not run when a pull request changes only docs that no test reads: the Markdown at the root and in `docs/`, `docs/design/mockups/`, the README's images in `docs/design/images/` and `docs/release-notes/`. A new push to the same pull request cancels the run still going.
- **Before each push, run the checks for what your change touches:**
  - `server/`: `pytest`, `ruff`, `mypy` and `e2e.sh` (it starts the server);
  - `android/`, `docs/design/system/` or `docs/design/captures/`: the Gradle line, the captures comparison and `e2e.sh`;
  - `docs/protocol/`, the workflows, or both of the above: all five;
  - other docs only: the link check, `python3 -I scripts/check-links.py` (standard library only; it also runs with any of the above when Markdown changes).
- Say in the pull request which checks you ran.
- **Before the maintainer merges a release pull request,** a green *Tests* run on its last push is the check of its merge with `main`; without one, the five checks run on the release branch merged with `main`.
- **Run the local checks in the background** while writing, rather than waiting for them.

- **Static analysis only checks; it never rewrites code.** `ruff check` and `mypy` (config in `server/pyproject.toml`; `ruff format` is not used) and `detekt` (`android/config/detekt.yml`, with the findings that were already there in `detekt-baseline.xml`). Fix a new finding in the code; change a rule or the baseline only with the maintainer's agreement, in a pull request of its own that says why.
- **Screens are compared with the approved design.** App screenshots land in `android/app/build/outputs/roborazzi/en` and `…/es`; `compare-design-captures.py` fails when an English one listed in `android/scripts/design-captures.txt` differs by a pixel from its capture in `docs/design/captures/`, and marks the changed pixels in `android/app/build/outputs/design-compare/`. A screen change waits for the maintainer's approval; then `android/scripts/update-design-captures.sh` copies the new screenshots over the captures, and they go in the same pull request. Spanish has no captures: look at it in `…/es`.

## Contracts not to break

- Relay `/phone`: nothing binary before `{"type":"ready"}`, frames of at most 64 KiB, one `{"type":"written"}` per frame, only `wss://` with the pairing's pinned key, checked in the TLS 1.3 handshake before anything is sent, to the server's Tailscale IP and only through its tunnel (`core/ServerHttp.kt`).
- Flipper buttons: short press `PRESS, SHORT, RELEASE`; long press `PRESS, LONG, RELEASE`.
- `command_id` by side on the Flipper's RPC link (`docs/protocol/rpc/`, `vectors.json`): the server's requests below `2^28`, its settle stop up to `2^29 − 1`, the relay's own messages `2^29 … 2^30 − 1`, `0` the Flipper's unsolicited messages; the relay never sends id 0, never writes the serial service's reset characteristic, never sends a `written` for its own frames, and writes a key only between the server's commands.
- Chat: one agent process per turn (Claude Code: `claude -p` with `--session-id`, then `--resume`) and one turn at a time on the whole server. Never `--bare`, never a token on its command line; with `free_only` on, never API-key variables of a paid service in a turn's environment. On `/app` the server sends codes and data; chat messages go only to clients that sent `chat.list`.
