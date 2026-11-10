# Install the server

Everything on this page runs **on the server**, as your own user (not root), except the one `sudo` line.

## Before you start

- Python 3.11 or later with `venv`, `git` and systemd.
- Tailscale installed and connected on the server (`tailscale ip -4` prints its address, for example `100.98.12.7`).
- For chats from the app, one agent installed for your user: Claude Code signed in (with your Claude subscription, or another login), or OpenCode, which needs Node.js with `npm` ([Use the agent](use-the-agent.md#set-up-opencode)). You can add it later and run the script again.

The script checks the first two before it changes anything, and says, one line each, what is missing and how to get it; it looks for the agents later, and says how to get one when it finds none.

## Install

```bash
git clone https://github.com/samartined/zeffin.git ~/projects/zeffin
~/projects/zeffin/install.sh
```

It installs the server, starts its service, sets up the agents it finds and ends showing the pairing QR code: have the phone ready ([Pair the app](pair-the-app.md)), or add `--no-pair` and pair later with `zf pair`.

- **Run it again at any time.** Each step checks what is already there and says "already done": the configuration, the key and the tokens are never replaced. After installing an agent, or fixing what a line asked for, run it again.
- **It needs no root.** The one command that does is printed for you to run, once: `sudo loginctl enable-linger $USER`, so the service keeps running when you log out.
- **It prints no token,** and nothing that would put one in your shell's history.
- **Options:** `--no-pair` ends without the QR code; `--minimal` installs the service without sandboxing directives, if your systemd rejects them (the script says so when the service does not stay running); `--yes` also registers the Flipper's MCP server for interactive Claude Code sessions (`zf mcp-config --yes`, see [below](#use-claude-code-on-the-server-directly-optional)), which is otherwise only offered; `--help` lists them.

`zf` and `zeffin` are the same program; the guide uses `zf`. Every command is in [The `zf` command](zf-command.md).

Check that it runs:

```bash
zf status
```

It shows the service's state, the running version, the server key's fingerprint, the relay, the agent's gate, the last frame and the last ping.

## What the script does, step by step

The same steps by hand, from the clone, if you prefer them or the script stops at one:

```bash
cd ~/projects/zeffin
python3 -m venv .venv
.venv/bin/pip install -r server/requirements.txt -e ./server
.venv/bin/zeffin init              # finds your Tailscale IP, creates the credentials and the server's TLS key
.venv/bin/zeffin install-service   # installs and starts the service, and links the short command zf
sudo loginctl enable-linger $USER  # so the service runs without a login session
.venv/bin/zeffin agent setup                   # if Claude Code is installed
.venv/bin/zeffin agent setup --agent opencode  # if OpenCode is installed
.venv/bin/zeffin pair              # the pairing QR code
```

1. **Checks:** Python 3.11 or later with `venv`, `git`, Tailscale connected (`tailscale ip -4`), and systemd's user manager (`systemctl --user`). It never installs system packages.
2. **The server** in its own virtual environment, `.venv` inside the clone.
3. **`init`**, only when there is no configuration yet, binds to the address `tailscale ip -4` gives. To choose another Tailscale address: `zeffin init --host 100.98.12.7`. The ports are 9434 (public, on the Tailscale address) and 9435 (MCP, loopback only); `--phone-port` and `--mcp-port` change them. Then **`install-service`** installs and starts a systemd user service named `zeffin`. If your systemd rejects the sandboxing directives, it retries by itself without them; `--minimal` asks for that from the start.
4. **Linger:** `loginctl show-user $USER -p Linger`; when it is off, the `sudo` line above, for you to run.
5. **The agents:** `zf agent setup` for each agent found on your `PATH` (or where its installer puts it). It checks the agent's sign-in and never asks for or shows a key ([Claude Code](use-the-agent.md#set-up-claude-code), [OpenCode](use-the-agent.md#set-up-opencode)).
6. **Pairing:** what to do on the phone, then `zf pair`.

**The `zf` command:** `install-service` links `~/.local/bin/zf` to the virtual environment's `zf`. If `~/.local/bin` was not on your `PATH`, log out and in again. From then on `zf` works from any folder; until then, run `.venv/bin/zeffin …` from the clone. To create only the link, without the service: `.venv/bin/zeffin install-cli`.

## Where things live

Everything Zeffin keeps lives next to the clone, outside its git tree:

```
~/projects/
  zeffin/         the repo clone
  zeffin-data/    config.json (tokens, mode 600), the server's TLS key, and state/ (logs, metrics, conversations)
  zeffin-agent/   the folder the agents run in for chats (zf agent setup): CLAUDE.md, AGENTS.md, opencode.json
```

- **Not inside the clone,** so a `git clean` or a new clone never deletes tokens or conversations, and the agent in its folder does not load the repository's own instructions.
- **In your home there is only what Linux or Claude Code require:** the service unit in `~/.config/systemd/user/`, the `~/.local/bin/zf` link, and the agent folder's trust entry in Claude Code's `~/.claude.json`.
- **`config.json` must be readable only by you** (mode 600): the server refuses to start otherwise.
- **The logs:** `state/audit.jsonl` (every tool call) and `state/metrics.jsonl` (connections, frames, pings) keep their newest 8 MiB each; the server trims them by itself. `state/latest.png` is the last screen a tool gave the agent. What else is kept, and for how long, is in [security.md](../security.md#what-the-server-keeps-on-disk).

## Set up the agent

The script sets up the agents it finds. To set one up by hand, or after installing it: [Claude Code](use-the-agent.md#set-up-claude-code), [OpenCode](use-the-agent.md#set-up-opencode), or run the script again.

## Let only your devices in (Tailscale policy)

Only your devices should reach port 9434 on the server: the phone, for the relay and the app, and your browsers, for the web view. An example with *grants* in your tailnet policy, adjusting the tag to the one you give the server:

```json
{
  "grants": [
    { "src": ["autogroup:member"], "dst": ["tag:zeffin-server"], "ip": ["tcp:9434"] }
  ]
}
```

Port 9435 (MCP) needs no rule: it only listens on `127.0.0.1`.

Zeffin also refuses by itself, before reading anything, any peer that is neither on the tailnet nor loopback ([Security](security.md)).

## Open the web view

From a browser on a device in your tailnet, open `https://<the server's Tailscale IP>:9434/view` (for example `https://100.98.12.7:9434/view`) and sign in with the view token, which `zf token view` prints.

**The browser warns once about the certificate,** which the server made itself. You can compare it with `zf status`: in Chrome, the certificate viewer's SHA-256 "Public key" starts with the same 20 hex characters as the fingerprint, grouped differently (needs testing in each browser).

## Use Claude Code on the server directly (optional)

The app's chats need nothing more. To drive the Flipper also from an interactive `claude` session on the server:

```bash
zf mcp-config
```

- It asks, then writes the `flipper` MCP server (http, `http://127.0.0.1:9435/mcp`, with the MCP token in its header) into your user's `~/.claude.json`, readable only by you.
- It does not run `claude mcp add`, which would put the token on a command line other users can read.
- Check it with `claude mcp list`, or `/mcp` inside Claude Code.
- An alternative that keeps the token out of `~/.claude.json` is in [`.mcp.json.example`](../../.mcp.json.example): it reads the token from the environment variable `ZEFFIN_MCP_TOKEN`.
- Run `claude` from the agent folder (`~/projects/zeffin-agent`), whose `CLAUDE.md` tells the agent how to drive the Flipper. Elsewhere, copy [`CLAUDE.md.example`](../../CLAUDE.md.example) as `CLAUDE.md` into the folder you run it from.

One long-running server lets several sessions share the same Flipper without fighting over it: calls to the Flipper run one at a time.

## Try it without a Flipper

The simulator connects like the app's relay and imitates a Flipper with a navigable menu, a screen stream and an in-memory SD card. From the clone, with the service running and the phone's relay off:

```bash
.venv/bin/python -m bridge.sim
```

The agent can then use `flipper_screen` and `flipper_press` against the simulator, and the web view shows its screen. Ctrl+C stops it.

## Next

[Pair the app](pair-the-app.md).
