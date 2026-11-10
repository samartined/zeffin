# The `zf` command

`zf` and `zeffin` are the same program, run on the server. `zf` alone, in a terminal, opens a menu with the common actions; `zf --help` and `zf <command> --help` list every option. Every command takes `--config FILE` before the command, to use another `config.json` (default `zeffin-data/config.json` next to the clone).

## Everyday

| Command | What it does |
|---|---|
| `zf` | A menu with the common actions |
| `zf status` | The service's state, the running version, the server key's fingerprint, the relay, the agent's gate, the last frame and ping, and each agent: Claude Code's version, login and the chat's state; OpenCode's version, providers and default model. Last, the devices that used the server since it started: each one's name on your tailnet and its system, its address, what it used (relay, app, web view, pairing), and whether it is connected now or when it was last seen. `zf chat` and anything else on the server itself is one row, "this server" |
| `zf start`, `zf stop`, `zf restart` | Control the systemd user service |
| `zf update` | Pull the latest code, reinstall, restart and show the status ([Updating](updating.md)) |
| `zf logs [-f] [-n N]` | The service's log (journalctl); `-f` keeps following, `-n` sets how many lines (default 100) |
| `zf report [HOURS]` | A summary of the stability metrics (default the last 24 hours) |
| `zf pair` | Pair the app: a QR code and the same pairing text, whose code changes every 30 s, then you confirm the phone by its number ([Pair the app](pair-the-app.md)) |
| `zf notes [show\|delete APPID] [--firmware ORIGIN]` | The agent's notes per app ([The Flipper tools](flipper-tools.md#notes-per-app)): without an action, the list (firmware, app, length, when it was written, same or other build, installed now); `show` prints one, `delete` removes one, `delete --all [--yes]` every one. It reads the files directly: no server needed |
| `zf notes off\|on` | Turn the notes per app off (the agent neither writes nor reads them; the files stay) or back on (the default). A running service restarts to use it |
| `zf chat [--new] [--mode default\|acceptEdits\|plan\|auto] [--list] [ID]` | Chat with the agent from the terminal, through the same channel as the app ([Use the agent](use-the-agent.md#from-the-terminal-zf-chat)). `--test [--lang en\|es]` starts the chat's guided manual test |

## Setup

| Command | What it does |
|---|---|
| `zf init [--host IP] [--phone-port N] [--mcp-port N] [--state-dir DIR]` | Create `config.json` with new tokens, and the server's TLS key. The host defaults to `tailscale ip -4`; the ports to 9434 and 9435 |
| `zf install-service [--minimal]` | Install and start the systemd user service, and link `~/.local/bin/zf`. `--minimal` leaves out the sandboxing directives |
| `zf install-cli` | Only link `~/.local/bin/zf` |
| `zf run` | Run the server in the foreground (Ctrl+C stops it), instead of the service |
| `zf agent setup [--dir DIR] [--model opus\|sonnet\|haiku]` | Prepare Claude Code for chats: the agent folder (default `zeffin-agent` next to the clone, with its `CLAUDE.md`), the path of `claude`, the login check (it says when the login is not a subscription), and the folder's trust entry in `~/.claude.json`. `--model` is Claude Code's default model |
| `zf agent setup --agent opencode [--dir DIR]` | Prepare OpenCode for chats: its version (1.19.5 up to, not including, 1.20.0), `AGENTS.md` and `opencode.json` in the agent folder, the merged-configuration check, the providers signed in, the free models it offers and how many paid ones, and the default model ([Use the agent](use-the-agent.md#set-up-opencode)) |
| `zf mcp-config [--yes]` | Register the `flipper` MCP server for your own interactive Claude Code, in `~/.claude.json` (it asks first); it shows the `.mcp.json` alternative and never prints the token |
| `zf set-firmware-sources ORIGIN [PATH] [--remove]` | Name a local checkout of a firmware's app sources (`ORIGIN`: `official`, `momentum` or `unleashed`), from which `flipper_app` reads the apps' READMEs. Kept in `config.json`, read at the next restart. Without `PATH`, it shows the current one |

## Keys and tokens

| Command | What it does |
|---|---|
| `zf show` | The relay's URL and phone token, the web view's URL and view token, and the server key's fingerprint |
| `zf token phone\|mcp\|view\|app` | Print one token (for scripts) |
| `zf rotate-tokens [--only phone\|mcp\|view\|app]` | Replace the tokens (all four by default; `--only` is repeatable). A new MCP token also goes into the entry `zf mcp-config` wrote |
| `zf key [--pin]` | The server's TLS key: its fingerprint, the pin the pairing QR code carries, and its files. `--pin` prints only the pin. It never prints the key itself |
| `zf key new [--yes] [--keep-tokens]` | Replace the key and, by default, the phone and app tokens: pair the app again |
| `zf set-host IP` | Change the bound Tailscale address, keeping the tokens and the key; restart, then pair again |

## Checking the agent

| Command | What it does |
|---|---|
| `zf agent probe` | Check how the installed Claude Code behaves where its documentation is not explicit, with about 17 tiny Haiku turns; it prints a report without tokens, paths or texts |
| `zf agent probe --agent opencode [--model MODEL]` | Check the installed OpenCode with a few short turns on a free model (default the first offered), against a throwaway server and the simulator: the guards, the screen, a permission, resuming and deleting a session. `--notes` adds the notes per app on the simulator's Dial app: a conversation that learns it, then one that uses its note (3 or 4 more turns) |
| `zf agent probe --notes` | With Claude Code, only the notes per app: two tiny Haiku turns against a throwaway server and the simulator (one saves a note, a new one reads it back), and a check that Claude Code wrote nothing to its own memory |
| `zf agent probe --remote [--yes]` | Also try Remote Control: it creates a session shown in your Claude app (`--yes` does not ask first) |
| `zf agent probe --json` | Print only the report, as JSON, on standard output (the steps go to standard error); with either agent |
| `zf agent probe --fixtures DIR` | Also save each Claude Code turn's output there, redacted, as test fixtures |

## What `zf` checks before it sends a token

- `zf status`, `zf pair` and `zf chat` talk to the server's loopback listener (`127.0.0.1:9435`). Before sending a token there, `zf` checks that the listener belongs to your own user, and otherwise refuses and sends nothing.
- `zf chat` also checks the public listener's TLS key against the one in the data folder.
