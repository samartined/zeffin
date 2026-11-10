# Troubleshooting

Start with `zf status` on the server and Settings, **Diagnostics** in the app (**Copy diagnostics** copies the versions, the states and the relay's last 200 events, never a token; it does hold the server's address and name, and the Flipper's name). The messages below are the ones the app, the web view and `zf` show.

## Installing

`./install.sh` can run again at any time: fix what a line asks for, then run it again. What is already done is kept and said so.

| What you see | What to do |
|---|---|
| "Run this as the user the server will run as, not as root and not with sudo." | Run `./install.sh` as your own user, without `sudo` |
| `missing: …` lines, then "Nothing was installed." | Install what each line names (Python 3.11 or later with `venv`, `git`, Tailscale connected, systemd), then run it again |
| "systemd's user manager does not answer (systemctl --user)" | Log in as that user directly (SSH or a console), not through `su` or `sudo`, then run it again |
| "the service zeffin does not stay running" | `zf logs -n 50` shows why. If your systemd rejects the sandboxing directives, `./install.sh --minimal` |
| "linger is off" | Run once `sudo loginctl enable-linger $USER`, as it says, so the service keeps running when you log out |
| "no agent found on your PATH (claude or opencode)" | Install Claude Code or OpenCode ([Use the agent](use-the-agent.md)), then run it again |
| "Claude Code is not set up yet" or "OpenCode is not set up yet" | Do what the lines above it say (usually signing in), then run it again |
| "zf is linked in ~/.local/bin, which is not on this shell's PATH yet" | Log out and in again, or use `.venv/bin/zf` meanwhile |

## The server

| What you see | What to do |
|---|---|
| `zf: command not found` | `~/.local/bin` is not on your `PATH` yet: log out and in again, or run `.venv/bin/zeffin …` from the clone. `zeffin install-cli` creates the link again |
| The service is not running | `zf logs -n 50` shows why. If your systemd rejects the sandboxing, `zf install-service --minimal` (or `./install.sh --minimal`) |
| The service stops when you log out | `sudo loginctl enable-linger $USER` |
| `… is readable by other users (mode …); run chmod 600` | `chmod 600 ~/projects/zeffin-data/config.json`, then `zf restart` |
| `zf update` says the clone has local changes, or its history does not match | See [Updating](updating.md#the-server) |
| The browser warns about the web view's certificate | Expected once: the server made it itself. Compare it with the fingerprint `zf status` prints ([Install the server](install-the-server.md#open-the-web-view)) |

## The phone and the relay

| What you see | What to do |
|---|---|
| "Tailscale is not connected on this phone" | Turn Tailscale on. The app connects again by itself as soon as the tunnel is back. Always-on VPN keeps it up ([Pair the app](pair-the-app.md#2-connect-tailscale-on-the-phone)) |
| "Not paired with the server" | [Pair the app](pair-the-app.md#3-pair-the-app-with-the-server) |
| "App token rejected: pair again" | The app token changed on the server (`zf rotate-tokens`, `zf key new`): pair again with `zf pair` |
| "The server's key changed" | Nothing was sent. If you ran `zf key new`, pair again. If you did not, another device may be answering at that address: pair only with a QR code from `zf pair` |
| "The server is too old for this app" | `zf update` on the server |
| "Bluetooth is off", or "The nearby devices permission is missing" | Turn Bluetooth on, or **Grant the permission** from the banner |
| No Flipper in Settings, Relay, Device | Pair the Flipper first in Android's Bluetooth settings, then **Look again** |
| The screen freezes or keys get odd replies | Another app may hold the Flipper's Bluetooth link (the official app, for example): close it. The relay cannot tell |
| The relay stops in the background | Turn off battery optimization for Zeffin and for Tailscale |
| "This code was already used by another phone" | Refuse the waiting request on the server. If you already confirmed it, `zf rotate-tokens`, then pair again where nobody can see the screen |

## The agent

| What you see | What to do |
|---|---|
| "Claude Code (claude) is not installed for this user, or not on your PATH." | Install Claude Code for the same user that runs the service, sign in, and run `zf agent setup` again |
| "Claude Code is not signed in." | Run `claude`, type `/login`, then `zf agent setup` |
| "Signed in: … It is not a subscription: every turn bills per use." | Expected with an API key or another login that bills. For a subscription, run `claude`, type `/login` and choose your Claude account |
| "Claude Code's settings on the server are not safe to run: run zf agent setup there to write them again." | The agent folder's settings (`.claude/settings*.json`) are not valid JSON, or OpenCode's `opencode.json` turns sharing or auto-update on. Fix or remove that file, then run `zf agent setup` (with `--agent opencode` for OpenCode) |
| "Stopped: it was not on the login and model allowed." | The turn's session ended up on another model than the conversation's, and was stopped. Choose the model again in Conversation settings |
| A model says **Paid** | It bills per use: usage credits on your Claude account, your API key, or a paid provider. Choose another model if you do not want that |
| A model shows as not available | The agent is not signed in to its provider. Sign it in on the server, or choose another |
| "Automatic mode is not available: this turn ran in Manual." | Auto is not available to your account or model (code.claude.com/docs/en/permission-modes). Use another mode |
| The agent's Flipper actions say "Paused by the operator. Not sent" | The agent is paused: **Resume** in Home or in the web view |
| `refused: busy` in the agent's actions | Two calls already run or wait; the agent should wait for them. Nothing to do |
| A conversation can be read but not continued | Claude Code deleted its transcript (after 30 days by default). Start a new conversation |
| No notification when the agent finishes or asks | Settings, Agent: **Allow notifications**, and check Android's channels for Zeffin |
| Another conversation is busy | One turn runs at a time on the whole server. Open it, or stop it |

## OpenCode

| What you see | What to do |
|---|---|
| "OpenCode (opencode) is not installed for this user, or not on your PATH." | `npm install -g opencode-ai@1.19.5` as the user that runs the service, then `zf agent setup --agent opencode` |
| "Refused: this server runs OpenCode from 1.19.5 up to, but not including, 1.20.0" (or, in the app, "The server does not support this version of OpenCode") | Install a version in that range, for example `npm install -g opencode-ai@1.19.5`, then `zf agent setup --agent opencode`. Do not let OpenCode update itself past it |
| "Refused: OpenCode's configuration, with your global one, …" | Your global OpenCode configuration (`~/.config/opencode`) turns sharing or auto-update on. Remove that setting and run `zf agent setup --agent opencode` again |
| "No provider is signed in, so OpenCode uses OpenCode Zen's free models" | Fine without an account. For another provider (NVIDIA NIM's free tier among them), run `opencode auth login` on the server, then `zf agent setup --agent opencode` |
| A NIM model shows "Not available: the agent is not signed in to its provider." | The same: sign in to NVIDIA with `opencode auth login` |
| "Refused: OpenCode lists none of its models here." | The server cannot reach the providers' catalogs: check its internet access, then run `zf agent setup --agent opencode` again |
| "OpenCode is not signed in on the server" | No model is offered: check the internet access, or sign in to a provider, then `zf restart` |
| A turn stops at once after a model change | The session ended up on another model than the conversation's, and was stopped. Choose the model again in Conversation settings |

`zf agent probe --agent opencode` checks the installed OpenCode against a throwaway server and prints a report to paste when you ask for help.

## Still stuck

- `zf report` summarizes the connection's stability over the last 24 hours (`zf report 72` for three days).
- `zf logs -f` follows the service's log while you try again.
- If you report a problem, include `zf status` and the app's **Copy diagnostics**, never a token, `config.json` or the key.
