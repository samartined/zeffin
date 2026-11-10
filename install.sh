#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
#
# Install the Zeffin server from this clone, as your own user: check what it needs, install it in .venv, create the
# configuration, install the service, set up the agents found, then show the pairing QR code.
# It can run again without harm; it never uses sudo, never moves folders and never prints a token.
# docs/guide/install-the-server.md explains each step.

set -eu

usage() {
    cat <<'EOF'
Usage: ./install.sh [--no-pair] [--minimal] [--yes]

Installs the Zeffin server from this clone, as your own user (no root):
  1. checks Python 3.11+ with venv, git, Tailscale (connected) and systemd's user manager
  2. installs the server in .venv, inside this clone
  3. creates the configuration and the server's key (zf init) if there is none, and
     installs the service (zf install-service), which also links ~/.local/bin/zf
  4. checks that the service keeps running without a login session (linger)
  5. sets up the agents it finds: Claude Code (claude) and OpenCode (opencode)
  6. shows the pairing QR code for the app (zf pair)

Run it again at any time: what is already done is kept and said so.

Options:
  --no-pair   end without showing the pairing QR code (pair later with: zf pair)
  --minimal   install the service without sandboxing directives (if your systemd rejects them)
  --yes       also register the Flipper's MCP server for interactive Claude Code sessions
              (zf mcp-config --yes); without it, that step is only offered
  --help      show this help
EOF
}

PAIR=1
MINIMAL=
YES=
for arg in "$@"; do
    case "$arg" in
        --help|-h) usage; exit 0 ;;
        --no-pair) PAIR= ;;
        --minimal) MINIMAL=1 ;;
        --yes) YES=1 ;;
        *) echo "Unknown option: $arg (see ./install.sh --help)" >&2; exit 2 ;;
    esac
done

ROOT=$(cd "$(dirname "$0")" && pwd -P)
BASE=$(dirname "$ROOT")
VENV="$ROOT/.venv"
ZEFFIN="$VENV/bin/zeffin"
DATA="$BASE/zeffin-data"
CONFIG="$DATA/config.json"
UNITS="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
ME=${USER:-$(id -un)}

step() { printf '\n== %s\n' "$*"; }
say() { printf '  %s\n' "$*"; }
run_zf() { "$ZEFFIN" --config "$CONFIG" "$@"; }

if [ "$(id -u)" = 0 ]; then
    echo "Run this as the user the server will run as, not as root and not with sudo." >&2
    exit 1
fi
if [ ! -d "$ROOT/server/bridge" ] && [ ! -f "$ROOT/server/pyproject.toml" ]; then
    echo "$ROOT is not a Zeffin clone (no server/ folder): run install.sh from the clone." >&2
    exit 1
fi

# ------------------------------------------------------------------ 1. what is needed
step "1/6 Checking what the server needs"
missing=0
need() { say "missing: $*"; missing=1; }

PYTHON=
for candidate in python3 python3.13 python3.12 python3.11; do
    command -v "$candidate" >/dev/null 2>&1 || continue
    version=$("$candidate" -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null) || continue
    major=${version%%.*}
    minor=${version#*.}
    if [ "$major" -gt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -ge 11 ]; }; then
        PYTHON=$(command -v "$candidate")
        break
    fi
done
if [ -z "$PYTHON" ]; then
    need "Python 3.11 or later: install it with your distribution's packages (for example python3 and python3-venv)."
elif [ -x "$VENV/bin/python" ]; then
    say "ok: Python $version ($PYTHON); the virtual environment .venv exists."
elif ! "$PYTHON" -c 'import venv, ensurepip' >/dev/null 2>&1; then
    need "Python's venv module for $PYTHON (Python $version): install your distribution's python3-venv package (or python$version-venv)."
else
    say "ok: Python $version ($PYTHON) with venv."
fi

if command -v git >/dev/null 2>&1; then
    say "ok: git."
else
    need "git: install it with your distribution's packages (zf update pulls new versions with it)."
fi

TS_IP=
if ! command -v tailscale >/dev/null 2>&1; then
    need "Tailscale: install it as tailscale.com/download says, then connect it (sudo tailscale up)."
else
    TS_IP=$(tailscale ip -4 2>/dev/null | head -n 1) || TS_IP=
    if [ -n "$TS_IP" ]; then
        say "ok: Tailscale is connected; this server's Tailscale address is $TS_IP."
    else
        need "Tailscale is installed but not connected (tailscale ip -4 gives no address): connect it with sudo tailscale up."
    fi
fi

if ! command -v systemctl >/dev/null 2>&1; then
    need "systemd (systemctl): the server runs as a systemd user service; this system has no systemctl."
elif systemctl --user show-environment >/dev/null 2>&1; then
    say "ok: systemd's user manager answers (systemctl --user)."
else
    need "systemd's user manager does not answer (systemctl --user): log in as $ME directly (SSH or a console, not su or sudo), then run this again."
fi

if [ "$missing" = 1 ]; then
    printf '\nNothing was installed. Install what is missing above, then run ./install.sh again.\n'
    exit 1
fi

# An install under the old names, next to this clone: never moved by this script.
OLD_DATA="$BASE/flipper-bridge-data"
OLD_AGENT="$BASE/flipper-agent"
OLD_UNIT="$UNITS/flipper-bridge.service"
if [ -e "$OLD_DATA" ] || [ -e "$OLD_AGENT" ] || [ -e "$OLD_UNIT" ]; then
    printf '\nFound an install under the old names (flipper-bridge), which this script never moves:\n'
    for item in "$OLD_DATA" "$OLD_AGENT" "$OLD_UNIT"; do
        if [ -e "$item" ]; then say "$item"; fi
    done
    cat <<EOF

Move it by hand, then run ./install.sh again. On this server:
  systemctl --user disable --now flipper-bridge.service
  rm -f "$OLD_UNIT" && systemctl --user daemon-reload
EOF
    if [ -e "$OLD_DATA" ]; then echo "  mv \"$OLD_DATA\" \"$DATA\""; fi
    if [ -e "$OLD_AGENT" ]; then echo "  mv \"$OLD_AGENT\" \"$BASE/zeffin-agent\""; fi
    echo "  rm -f \"$HOME/.local/bin/fb\""
    cat <<'EOF'
The configuration, the key, the tokens and the conversations move with the data folder; the server
moves the paths config.json names to the new folders when it reads it. Zeffin is a new app: pair it
once with zf pair. After ./install.sh, run zf agent setup (and zf agent setup --agent opencode if you
use OpenCode) so the agents trust their folder under its new name.
Nothing was installed or changed.
EOF
    exit 1
fi

# ------------------------------------------------------------------ 2. the server in .venv
step "2/6 Installing the server in $VENV"
if [ -x "$ZEFFIN" ] && "$VENV/bin/python" -I -c 'import bridge' >/dev/null 2>&1; then
    say "already done: the server is installed in .venv (zf update installs new versions)."
else
    if [ ! -x "$VENV/bin/python" ]; then
        "$PYTHON" -m venv "$VENV"
        say "created the virtual environment $VENV."
    fi
    say "installing the server and its libraries (pip, about a minute)..."
    if ! "$VENV/bin/pip" install -q --disable-pip-version-check -r "$ROOT/server/requirements.txt" -e "$ROOT/server"; then
        say "pip could not install the server (its error is above). Fix it, then run ./install.sh again."
        exit 1
    fi
    say "installed the server in .venv."
fi

# ------------------------------------------------------------------ 3. configuration and service
step "3/6 Configuring the server and installing its service"
if [ -e "$CONFIG" ]; then
    say "already done: the configuration $CONFIG exists (kept as is: its tokens and key never change here)."
else
    # zf init prints the tokens: only its errors are shown here.
    if ! run_zf init --host "$TS_IP" >/dev/null; then
        say "zf init could not create the configuration (its error is above). Fix it, then run ./install.sh again."
        exit 1
    fi
    say "created $CONFIG (mode 600) with new tokens, and the server's key, bound to $TS_IP."
fi

service_ok() { [ -f "$UNITS/zeffin.service" ] && systemctl --user is-active --quiet zeffin.service; }
if service_ok; then
    say "already done: the service zeffin is installed and running (zf status shows it)."
else
    run_zf install-service ${MINIMAL:+--minimal} | sed 's/^/    /'
    if service_ok; then
        say "installed and started the service zeffin."
    else
        say "the service zeffin does not stay running. See why with: $ZEFFIN logs -n 50"
        if [ -z "$MINIMAL" ]; then
            say "if the log shows your systemd rejecting the sandboxing directives, run: ./install.sh --minimal"
        fi
        exit 1
    fi
fi

# ------------------------------------------------------------------ 4. linger
step "4/6 Keeping the service running without a login session"
linger=$(loginctl show-user "$ME" -p Linger 2>/dev/null || true)
if [ "$linger" = "Linger=yes" ]; then
    say "already done: linger is on for $ME."
else
    say "linger is off for $ME: systemd stops the service when you log out, and starts it only when you log in."
    say "it is the one step that needs root; run it yourself once:"
    echo
    say "    sudo loginctl enable-linger $ME"
fi

# ------------------------------------------------------------------ 5. agents
step "5/6 Setting up the agents for chats from the app"
configured() {  # the config's field for this agent names a program that still exists, and the agent folder is there
    "$VENV/bin/python" -I - "$CONFIG" "$1" <<'EOF'
import json, os, sys
c = json.load(open(sys.argv[1]))
path = c.get(sys.argv[2]) or ""
folder = c.get("agent_dir") or ""
sys.exit(0 if folder and os.path.isdir(folder) and path and os.access(path, os.X_OK) else 1)
EOF
}
agents=0
find_agent() {  # on the PATH, or where its own installer puts it (as zf agent setup looks)
    command -v "$1" 2>/dev/null || { [ -x "$2" ] && echo "$2"; } || true
}
CLAUDE=$(find_agent claude "$HOME/.local/bin/claude")
OPENCODE=$(find_agent opencode "$HOME/.opencode/bin/opencode")
if [ -n "$CLAUDE" ]; then
    agents=1
    if configured claude_bin; then
        say "already done: Claude Code is set up for chats (again with: zf agent setup)."
    else
        say "found Claude Code ($CLAUDE): zf agent setup"
        if run_zf agent setup | sed 's/^/    /' && configured claude_bin; then
            say "Claude Code is set up for chats."
        else
            say "Claude Code is not set up yet: do what the lines above say, then run ./install.sh again."
        fi
    fi
fi
if [ -n "$OPENCODE" ]; then
    agents=1
    if configured opencode_bin; then
        say "already done: OpenCode is set up for chats (again with: zf agent setup --agent opencode)."
    else
        say "found OpenCode ($OPENCODE): zf agent setup --agent opencode"
        if run_zf agent setup --agent opencode | sed 's/^/    /' && configured opencode_bin; then
            say "OpenCode is set up for chats."
        else
            say "OpenCode is not set up yet: do what the lines above say, then run ./install.sh again."
        fi
    fi
fi
if [ "$agents" = 0 ]; then
    say "no agent found on your PATH (claude or opencode). Install one, then run ./install.sh again:"
    say "- Claude Code, with your Claude subscription: install it as code.claude.com says, then run claude and sign in with /login."
    oc=$("$VENV/bin/python" -I -c 'from bridge.chat.opencode import MIN_VERSION as v; print(".".join(map(str, v)))' \
        2>/dev/null || true)
    say "- OpenCode, on free providers only: npm install -g opencode-ai${oc:+@$oc}${oc:+ (the version this server supports)}."
fi
if [ -n "$CLAUDE" ]; then
    if [ -n "$YES" ]; then
        say "registering the Flipper's MCP server for interactive claude sessions (zf mcp-config --yes):"
        run_zf mcp-config --yes | sed 's/^/    /' || true
    else
        say "optional: to drive the Flipper also from an interactive claude session on this server, run: zf mcp-config"
    fi
fi

# ------------------------------------------------------------------ 6. pairing
step "6/6 Pairing the app"
if ! command -v zf >/dev/null 2>&1; then
    say "zf is linked in ~/.local/bin, which is not on this shell's PATH yet: log in again, or use $VENV/bin/zf meanwhile."
fi
if [ -z "$PAIR" ]; then
    say "skipped (--no-pair). When the phone is ready, run: zf pair"
    exit 0
fi
cat <<'EOF'
  On the phone, first:
    1. install the Zeffin app from the repository's Releases page;
    2. connect Tailscale to the same tailnet as this server;
    3. in the app: Settings, Server, Pair with QR code.
  Then scan the QR code below, and confirm here the number the phone shows.
  (Not ready yet? Ctrl+C, and later run: zf pair)

EOF
exec "$ZEFFIN" --config "$CONFIG" pair
