#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
# End-to-end tests of the relay engine and the chat: starts the Python server and the Flipper
# simulator on 127.0.0.1 with a throwaway configuration, the chat with the fake claude from
# server/tests/fake_claude.py, then runs EndToEndTest (relay) and ChatEndToEndTest (app).
# The public listener speaks TLS 1.3 with the throwaway server's own key: FB_E2E_URL is https://, and FB_E2E_KEY is
# the pin the pairing QR code would carry (zf key --pin), which the app's tests check the server against.
# Tokens stay in variables and are never printed; the private key never leaves its file.
#
#   PYTHON=../.venv/bin/python android/scripts/e2e.sh     (from the repository root)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PY="${PYTHON:-python3}"
WORK="$(mktemp -d)"
PIDS=()
cleanup() {
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  # The server writes its last state while it stops: wait for it before removing the folder.
  for pid in "${PIDS[@]}"; do wait "$pid" 2>/dev/null || true; done
  rm -rf "$WORK"
}
trap cleanup EXIT

free_port() { "$PY" -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
PHONE_PORT="$(free_port)"; MCP_PORT="$(free_port)"; FLIPPER_PORT="$(free_port)"
CFG="$WORK/config.json"
cli() { (cd "$ROOT/server" && "$PY" -c 'import sys; from bridge.cli import main; sys.exit(main())' "$@"); }

cli --config "$CFG" init --host 127.0.0.1 --phone-port "$PHONE_PORT" --mcp-port "$MCP_PORT" --state-dir "$WORK/state" > /dev/null
FB_E2E_KEY="$(cli --config "$CFG" key --pin)"; export FB_E2E_KEY
# curl's --pinnedpubkey takes the same hash in standard base64 (it needs a curl built with OpenSSL or GnuTLS).
PIN_B64="$("$PY" -I -c 'import base64, sys; print(base64.b64encode(base64.urlsafe_b64decode(sys.argv[1] + "=")).decode())' "$FB_E2E_KEY")"

# The chat, with the fake claude (no real Claude Code, no login, nothing billed).
mkdir -p "$WORK/agent" "$WORK/fake-claude"
printf '#!/bin/sh\nexec "%s" -I "%s" "$@"\n' "$PY" "$ROOT/server/tests/fake_claude.py" > "$WORK/fake-claude/claude"
chmod +x "$WORK/fake-claude/claude"
export FAKE_CLAUDE_HOME="$WORK/fake-claude"
(cd "$ROOT/server" && "$PY" -I -c 'import sys; from dataclasses import replace; from bridge import config as cfg
c = cfg.load(cfg.Path(sys.argv[1])); cfg.save(cfg.Path(sys.argv[1]), replace(c, agent_dir=sys.argv[2], claude_bin=sys.argv[3]))' \
  "$CFG" "$WORK/agent" "$WORK/fake-claude/claude")
(cd "$ROOT/server" && exec "$PY" -m bridge.sim --flipper-tcp "$FLIPPER_PORT") > "$WORK/sim.log" 2>&1 & PIDS+=($!)
(cd "$ROOT/server" && exec "$PY" -c 'import sys; from bridge.cli import main; sys.exit(main())' --config "$CFG" run) > "$WORK/server.log" 2>&1 & PIDS+=($!)

# -k only skips the certificate's issuer and name: --pinnedpubkey still checks the server's key against the pin.
for _ in $(seq 50); do
  curl -sfk --pinnedpubkey "sha256//$PIN_B64" "https://127.0.0.1:$PHONE_PORT/" > /dev/null && break
  sleep 0.2
done

export FB_E2E_URL="https://127.0.0.1:$PHONE_PORT"
FB_E2E_PHONE_TOKEN="$(cli --config "$CFG" token phone)"; export FB_E2E_PHONE_TOKEN
FB_E2E_APP_TOKEN="$(cli --config "$CFG" token app)"; export FB_E2E_APP_TOKEN
export FB_E2E_FLIPPER_PORT="$FLIPPER_PORT"
export FB_E2E_CHAT=1
# For the remote's check after the tests: the MCP listener's token, never printed.
FB_E2E_MCP_TOKEN="$(cli --config "$CFG" token mcp)"; export FB_E2E_MCP_TOKEN
export FB_E2E_MCP_URL="http://127.0.0.1:$MCP_PORT/mcp"

status=0
# --rerun applies to the task before it: each test task gets its own, or an unchanged module's would be up to date and
# its old results would stand for a run against this server.
(cd "$ROOT/android" && ./gradlew --no-daemon -q :relay:testDebugUnitTest --tests '*EndToEndTest*' --rerun \
  :app:testDebugUnitTest --tests '*ChatEndToEndTest*' --rerun) || status=$?
if [ "$status" -ne 0 ]; then
  echo "--- failures"
  grep -h -A12 "<failure" "$ROOT"/android/relay/build/test-results/testDebugUnitTest/*EndToEnd*.xml \
    "$ROOT"/android/app/build/test-results/testDebugUnitTest/*ChatEndToEnd*.xml | head -40 || true
  echo "--- server log"; tail -50 "$WORK/server.log"
  echo "--- simulator log"; tail -20 "$WORK/sim.log"
fi

# The remote's keys (EndToEndTest): the server counted the replies to the relay's ids in the close metric, and
# none of them reached flipper_events (docs/protocol/rpc/README.md, "What the server does with the relay's replies").
if [ "$status" -eq 0 ]; then
  "$PY" -I - "$WORK/state/metrics.jsonl" <<'PYEOF' || status=$?
import asyncio, json, os, sys, time
from mcp import ClientSession
from mcp.client.streamable_http import create_mcp_http_client, streamable_http_client

RELAY_MIN, RELAY_MAX = 1 << 29, (1 << 30) - 1

def relay_replies():
    try:
        lines = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
    except FileNotFoundError:
        return 0
    return sum(l.get("relay_replies", 0) for l in lines if l.get("ev") == "close")

async def events():
    headers = {"Authorization": "Bearer " + os.environ["FB_E2E_MCP_TOKEN"]}
    async with create_mcp_http_client(headers=headers) as http, \
            streamable_http_client(os.environ["FB_E2E_MCP_URL"], http_client=http) as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()
            result = await session.call_tool("flipper_events", {})
            return "".join(getattr(c, "text", "") for c in result.content)

deadline = time.monotonic() + 10
while relay_replies() < 6 and time.monotonic() < deadline:  # two keys, three replies each
    time.sleep(0.2)
count = relay_replies()
text = asyncio.run(events())
leaked = [str(i) for i in range(RELAY_MIN, RELAY_MIN + 64) if f'"command_id": {i}' in text or f'"command_id":{i}' in text]
print(f"remote: {count} relay replies counted by the server; {len(leaked)} in flipper_events")
if count < 6 or leaked:
    try:
        for line in open(sys.argv[1], encoding="utf-8"):
            if '"ev": "close"' in line or '"ev":"close"' in line:
                print("close:", line.strip()[:300])
    except FileNotFoundError:
        print("no metrics file")
sys.exit(0 if count >= 6 and not leaked else 1)
PYEOF
fi
exit "$status"
