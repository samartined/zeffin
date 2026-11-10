# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""zeffin command line (also installed as `zf`)."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import os
import shutil
import subprocess
import sys
import time
from dataclasses import replace
from pathlib import Path

from . import config as cfg
from . import localpeer, privnet, profiles, tlskey
from .report import describe_chat as _describe_chat
from .report import describe_status as _describe_status
from .report import report
from .stats import read_events
from .systemd import UNIT_NAME, _service, _start_and_check, _systemctl, render_unit, runs_config, unit_path
from .wire import AgentId

AGENT_IDS = (str(AgentId.CLAUDE_CODE), str(AgentId.OPENCODE))


def _prog() -> str:
    """How to call this CLI in printed instructions: `zf` if it is on the PATH, else the venv's script."""
    if shutil.which("zf"):
        return "zf"
    script = Path(sys.executable).with_name("zeffin")
    return str(script) if script.exists() else "zeffin"


def self_command(config: Path, *args: str) -> list[str]:
    """This CLI in a new process. -I keeps the current folder out of the import path: a stray
    `bridge/` folder there (an old checkout layout) would otherwise shadow the installed package."""
    return [sys.executable, "-I", "-m", "bridge.cli", "--config", str(config), *args]


def _name() -> str:
    """The program name for --help: `zf` when run as zf (argv[0] is -c or a path under python -m)."""
    return "zf" if Path(sys.argv[0]).name == "zf" else "zeffin"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog=_name(), description=__doc__)
    p.add_argument("--config", type=Path, default=cfg.DEFAULT_CONFIG, help="config file (default %(default)s)")
    sub = p.add_subparsers(dest="cmd", metavar="COMMAND")

    sub.add_parser("status", help="service state and, if running, the relay, agent and screen")
    for action in ("start", "stop", "restart"):
        sub.add_parser(action, help=f"{action} the systemd user service")
    sub.add_parser("update", help="pull the latest code, reinstall and restart")
    s = sub.add_parser("logs", help="show the service log (journalctl)")
    s.add_argument("-f", "--follow", action="store_true", help="keep printing new lines")
    s.add_argument("-n", "--lines", type=int, default=100, help="how many lines (default 100)")

    s = sub.add_parser("init", help="create config with fresh credentials")
    s.add_argument("--host", help=privnet.HOST_HELP)
    s.add_argument("--phone-port", type=int, default=9434)
    s.add_argument("--mcp-port", type=int, default=9435)
    s.add_argument("--state-dir", type=Path, help=f"state and logs (default {cfg.DEFAULT_STATE})")

    sub.add_parser("run", help="run the bridge in the foreground")
    sub.add_parser("show", help="print phone URL/token and live-view URL")
    s = sub.add_parser("token", help="print one token (for scripts)")
    s.add_argument("which", choices=["phone", "mcp", "view", "app"])
    s = sub.add_parser("mcp-config", help="register the Flipper MCP server for Claude Code (writes ~/.claude.json)")
    s.add_argument("--yes", action="store_true", help="write it without asking")
    sub.add_parser("pair", help="pair the Zeffin app: a QR code whose code changes every 30 s, then you confirm")
    s = sub.add_parser("key", help="the server's TLS key: its fingerprint, the pin in the pairing QR code, a new key")
    s.add_argument("action", nargs="?", choices=["new"], help="new: replace the key (the app must pair again)")
    s.add_argument("--pin", action="store_true", help="print only the pin (the QR code's key), for scripts")
    s.add_argument("--yes", action="store_true", help="with new: do not ask first")
    s.add_argument("--keep-tokens", action="store_true", help="with new: keep the phone and app tokens")
    s = sub.add_parser("report", help="summarize stability metrics")
    s.add_argument("since", nargs="?", type=float, metavar="HOURS", help="look back this many hours (default 24)")
    s.add_argument("--hours", type=float, default=24.0, help=argparse.SUPPRESS)
    s = sub.add_parser("set-host", help=privnet.SET_HOST_HELP)
    s.add_argument("host")
    s = sub.add_parser("set-free-only",
                       help="free only: the agents run only on a subscription or free providers and models (default "
                            "off); without on or off, prints it")
    s.add_argument("state", nargs="?", choices=["on", "off"])
    s = sub.add_parser("set-firmware-sources",
                       help="name a local checkout of a firmware's app sources, for the apps' READMEs (flipper_app)")
    s.add_argument("origin", help="the firmware's profile name: " + ", ".join(sorted(profiles.load_index().profiles)))
    s.add_argument("path", nargs="?", help="the checkout's folder (the one the catalog's source_dir is relative to)")
    s.add_argument("--remove", action="store_true", help="forget the checkout named for this firmware")
    s = sub.add_parser("notes", help="the agent's notes per app: list, show or delete them, or turn them off or on")
    s.add_argument("action", nargs="?", choices=["show", "delete", "off", "on"],
                   help="without one, lists the notes; off: the agent can neither write nor read them (default on)")
    s.add_argument("appid", nargs="?", help="with show or delete: the app (the .fap file's name without .fap)")
    s.add_argument("--firmware", metavar="ORIGIN", help="with show or delete: the firmware's origin as the list names "
                                                         "it (default: the one the Flipper ran last)")
    s.add_argument("--all", action="store_true", help="with delete: every note, of every firmware")
    s.add_argument("--yes", action="store_true", help="with delete --all: do not ask first")
    s = sub.add_parser("rotate-tokens", help="replace tokens with new random ones, keeping host and ports")
    s.add_argument("--only", action="append", choices=["phone", "mcp", "view", "app"],
                   help="rotate only this token (repeatable; default: all four)")
    s = sub.add_parser("install-service", help="install and start a systemd user service")
    s.add_argument("--minimal", action="store_true", help="omit sandboxing directives (if your systemd rejects them)")
    sub.add_parser("install-cli", help="put `zf` on your PATH (~/.local/bin/zf)")
    s = sub.add_parser("agent", help="set up and check the agents for chats from the app (Claude Code, OpenCode)")
    agent = s.add_subparsers(dest="agent_cmd", metavar="ACTION", required=True)
    a = agent.add_parser("setup", help="choose the agent folder, find the agent and check its login")
    a.add_argument("--agent", choices=AGENT_IDS, default=AGENT_IDS[0],
                   help="claude-code (default) or opencode")
    a.add_argument("--dir", type=Path, help=f"folder the agents run in (default {cfg.DEFAULT_AGENT})")
    a.add_argument("--model", choices=cfg.CHAT_MODELS, help="Claude Code's model for chat turns (default opus)")
    a = agent.add_parser("probe", help="check undocumented agent behavior: Claude Code with ~17 tiny Haiku turns, "
                                      "OpenCode with a few turns against a throwaway bridge and the simulator")
    a.add_argument("--agent", choices=AGENT_IDS, default=AGENT_IDS[0], help="claude-code (default) or opencode")
    a.add_argument("--model", help="with --agent opencode: one of its free models (default: the first offered)")
    a.add_argument("--remote", action="store_true",
                   help="also try Remote Control (creates a session shown in your Claude app)")
    a.add_argument("--yes", action="store_true", help="with --remote: do not ask before starting")
    a.add_argument("--json", action="store_true", help="print only the report, as JSON, on stdout (the rest on stderr)")
    a.add_argument("--notes", action="store_true",
                   help="the notes per app on the simulator's Dial, against a throwaway bridge: with --agent opencode "
                        "3 or 4 more turns; with Claude Code only these, two tiny Haiku turns")
    a.add_argument("--fixtures", type=Path, metavar="DIR",
                   help="also save each turn's stream-json there, redacted, as test fixtures")
    s = sub.add_parser("chat", help="chat with the agent from this terminal (same channel as the app)")
    s.add_argument("conversation", nargs="?", help="start of a conversation id (default: the latest)")
    s.add_argument("--new", action="store_true", help="start a new conversation")
    s.add_argument("--mode", choices=["default", "acceptEdits", "plan", "auto"],
                   help="permission mode of the new conversation (default is Manual)")
    s.add_argument("--list", action="store_true", help="list the conversations and exit")
    s.add_argument("--test", action="store_true", help="the chat's manual test, guided step by step by the agent")
    s.add_argument("--lang", choices=["en", "es"], help="language of the guided test (default: the terminal's)")

    args = p.parse_args(argv)
    if args.cmd is None:
        if sys.stdin.isatty() and sys.stdout.isatty():
            return menu(args.config)
        p.print_help()
        return 2
    try:
        return COMMANDS[args.cmd](args)
    except cfg.ConfigError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


def data_dir(config_path: Path) -> Path:
    """The data folder: config.json's, which also holds the server's TLS key (tlskey.py)."""
    return config_path.expanduser().resolve().parent


def cmd_init(args) -> int:
    c = cfg.create(args.config, args.host, args.phone_port, args.mcp_port, args.state_dir)
    key, created = tlskey.ensure(data_dir(args.config), c.host)
    print(f"Created {args.config} (mode 600)" + (" and the server's TLS key" if created else "") + "\n")
    _show(c, key)
    print(f"\nNext: {_prog()} install-service (or {_prog()} run), then {_prog()} mcp-config")
    return 0


def cmd_run(args) -> int:
    c = cfg.load(args.config)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    from .app import serve

    asyncio.run(serve(c, data_dir(args.config)))
    return 0


def _show(c: cfg.Config, key: tlskey.ServerKey | None) -> None:
    print("Phone relay:")
    print(f"  Server URL:  {c.phone_url()}")
    print(f"  Phone token: {c.phone_token}")
    print("Zeffin app: zeffin pair, or Pair the app in the live view  (QR code, then you confirm)")
    print(f"Live view ({privnet.VIEW_FROM}):")
    print(f"  URL:         {c.view_url()}")
    print(f"  View token:  {c.view_token}")
    print(f"Server key:    {key.fingerprint if key else 'none yet (made when the server starts)'}")
    print(f"State/logs:    {c.state_dir}")


def cmd_show(args) -> int:
    _show(cfg.load(args.config), tlskey.read(data_dir(args.config)))
    return 0


def cmd_key(args) -> int:
    """The server's TLS key: its fingerprint, its pin and its files; `new` replaces it. Never prints the key itself."""
    import secrets

    c = cfg.load(args.config)
    folder = data_dir(args.config)
    if args.action != "new":
        key = tlskey.read(folder)
        if key is None:
            print(f"No key yet: {_prog()} init, or the server's first start, makes it.", file=sys.stderr)
            return 1
        if args.pin:
            print(key.pin)
            return 0
        print(f"Fingerprint: {key.fingerprint}")
        print(f"Pin:         {key.pin}   (the pairing QR code's \"key\")")
        print(f"Key file:    {key.key_path}   (mode 600; never copy or share it)")
        print(f"Certificate: {key.cert_path}")
        return 0
    print("A new key means pairing the app again: it refuses a server whose key changed."
          + ("" if args.keep_tokens else " The phone and app tokens change too."))
    if not args.yes and not _ask("Make a new key? [y/N] "):
        print("  Nothing changed.")
        return 1
    key = tlskey.new(folder, c.host)
    which = [] if args.keep_tokens else ["phone", "app"]
    if which:
        cfg.save(args.config, replace(c, **{f"{name}_token": secrets.token_urlsafe(32) for name in which}))
    print(f"New key: {key.fingerprint}" + (". New tokens: phone, app." if which else "."))
    print(f"The server serves it once it restarts:\n\n  {_prog()} restart\n")
    print(f"Then pair the app again:  {_prog()} pair   (or Pair the app in the web view)")
    print(f"If config.json may have leaked with the old key, also run:  {_prog()} rotate-tokens --only view --only mcp")
    return 0


def cmd_token(args) -> int:
    c = cfg.load(args.config)
    print({"phone": c.phone_token, "mcp": c.mcp_token, "view": c.view_token, "app": c.app_token}[args.which])
    return 0


def _mcp_server(c: cfg.Config) -> dict:
    """The user-scope entry for the Flipper MCP server: an http server with a header (code.claude.com/docs/en/mcp)."""
    return {"type": "http", "url": c.mcp_url(), "headers": {"Authorization": f"Bearer {c.mcp_token}"}}


def cmd_mcp_config(args) -> int:
    """Register the MCP server by writing ~/.claude.json directly: `claude mcp add --header` would put the token
    on claude's command line, which other users can read in /proc/<pid>/cmdline."""
    from .chat import command

    c = cfg.load(args.config)
    path = command.claude_config_file()
    print(f"Claude Code reaches the Flipper through the MCP server `flipper` at {c.mcp_url()}.\n")
    print(f"Option A - register it once for your user, in {path} (the token is stored there, readable only by you).")
    if args.yes or _ask("Write it now? [y/N] "):
        result = command.set_user_mcp_server("flipper", _mcp_server(c), path)
        if result == "kept":
            print(f"  {path} could not be read or has an unexpected shape: left as is.")
            return 1
        print("  Already registered." if result == "same" else "  Registered `flipper` for your user.")
        print("  Check with: claude mcp list   (a running Claude Code may rewrite that file from memory:"
              f" if `flipper` is missing, quit it and run {_prog()} mcp-config again)")
    else:
        print(f"  Not written. Later: {_prog()} mcp-config --yes")
    entry = {"mcpServers": {"flipper": {"type": "http", "url": c.mcp_url(),
                                        "headers": {"Authorization": "Bearer ${ZEFFIN_MCP_TOKEN}"}}}}
    print("\nOption B - project .mcp.json, token read from the environment:\n")
    print(json.dumps(entry, indent=2))
    print(f"\n  export ZEFFIN_MCP_TOKEN=$({_prog()} token mcp)\n")
    print("Check inside Claude Code with /mcp. The MCP listener only accepts connections from this machine.")
    return 0


def _ask(question: str) -> bool:
    if not sys.stdin.isatty():
        print("  Not a terminal: add --yes to write it.")
        return False
    try:
        return input(f"  {question}").strip().lower() in ("y", "yes")
    except EOFError:
        return False


def _local(c: cfg.Config, method: str, path: str, body: dict | None = None, timeout: float = 5.0) -> dict:
    """A call to the running server's loopback listener with the app token (zf pair's routes refuse the MCP token),
    once localpeer has checked that the listener is ours. Raises NotOurs, _HTTPStatus or OSError."""
    status, data = localpeer.request("127.0.0.1", c.mcp_port, method, path, c.app_token, timeout=timeout,
                                     body=json.dumps(body).encode() if body is not None else None,
                                     content_type="application/json" if body is not None else None)
    if not 200 <= status < 300:
        raise _HTTPStatus(status)
    return json.loads(data)


class _HTTPStatus(Exception):
    def __init__(self, code: int):
        super().__init__(f"HTTP {code}")
        self.code = code


PAIR_ENDS = {
    "refused": "Refused: nothing was sent. If it was not your phone, someone else saw the QR code: "
               "pair again where nobody can see the screen.",
    "expired": "The pairing ended: nobody confirmed in time, or no phone scanned within 3 minutes. Nothing was sent.",
    "cancelled": "Cancelled: the code no longer works. Nothing was sent.",
    "phone_cancelled": "The phone cancelled: nothing was sent.",
    "lost": "The phone did not collect the tokens in time: nothing was sent. Pair again.",
}


def _answer_within(question: str, seconds: int) -> bool | None:
    """y/N typed within `seconds`; None when time ran out."""
    import select

    print(question, end="", flush=True)
    ready, _, _ = select.select([sys.stdin], [], [], max(1, seconds))
    if not ready:
        print()
        return None
    line = sys.stdin.readline()
    return line.strip().lower() in ("y", "yes")


def _pair_screen(state: dict, tty: bool) -> None:
    """One code: the QR code, the pairing text as one line, the server's key and the owner's texts."""
    import qrcode

    if tty:
        print("\033[2J\033[H", end="")  # a clean screen for each code
    payload = state["payload"]
    try:
        fingerprint = tlskey.fingerprint_of(json.loads(payload)["key"])
    except (ValueError, KeyError, TypeError):
        fingerprint = "?"
    qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(payload)
    qr.make(fit=True)
    print("Scan this with the Zeffin app (Settings > Server > Pair with QR code).")
    print("It holds a code, not the tokens: you confirm the phone here before anything is sent.\n")
    qr.print_ascii(invert=True)
    print("\nOn the phone itself, paste this pairing text in the app instead:")
    print(payload)
    print(f"\nServer key: {fingerprint}  (the app shows the same after it scans)")
    print("Keep the QR code and the text to yourself: no photo, no screenshot, no sharing.")
    print("The code changes every 30 s. Ctrl+C cancels.")


def cmd_pair(args) -> int:
    """Pairing from the running server (bridge/paircodes.py): a QR code whose code changes every 30 s, then the owner
    confirms the phone that scanned it by its number. Never the tokens."""
    c = cfg.load(args.config)
    try:
        state = _local(c, "POST", "/pair")
    except localpeer.NotOurs as e:
        print(f"Not pairing: {e}.", file=sys.stderr)
        return 1
    except _HTTPStatus as e:
        why = {401: f"it rejected the app token: tokens changed since it started ({_prog()} restart)",
               404: f"it is an older version without pairing codes ({_prog()} restart after {_prog()} update)"}
        print(f"The server is running, but {why.get(e.code, f'it answered HTTP {e.code}')}.", file=sys.stderr)
        return 1
    except (OSError, ValueError):
        print(f"The server is not running: start it first ({_prog()} start, or {_prog()} run in another terminal).",
              file=sys.stderr)
        return 1
    tty = sys.stdout.isatty()
    session, seq, said = state.get("session"), None, None
    try:
        while True:
            if state.get("session") != session:
                print(f"\nA newer pairing replaced this one (the web view, or another {_prog()} pair).")
                return 1
            now = state.get("state")
            if now == "showing":
                if state.get("seq") != seq:
                    seq = state.get("seq")
                    _pair_screen(state, tty)
                if tty:
                    left = int(state.get("ends_in", 0))
                    print(f"\rNew code in {state.get('rotates_in')} s · stops in {left // 60}:{left % 60:02d}  ",
                          end="", flush=True)
            elif now == "confirming":
                r = state["request"]
                number = f"{r['number'][:3]} {r['number'][3:]}"
                print(f"\n\nA phone ({r['peer']}) asks to pair. Its number: {number}")
                print("If your phone shows no number, refuse. The number proves the request.")
                if not sys.stdin.isatty():
                    print("No terminal to confirm in: refusing.")
                    _local(c, "POST", "/pair/refuse", {"ref": r["ref"]})
                    return 1
                answer = _answer_within(f"Does your phone show {number}? Confirm? [y/N] ", int(r["expires_in"]))
                try:
                    _local(c, "POST", "/pair/confirm" if answer else "/pair/refuse", {"ref": r["ref"]})
                except _HTTPStatus as e:
                    if e.code != 409:
                        raise
                    print("The request changed before your answer.")
            elif now == "confirmed":
                if said != now:
                    print("Confirmed: waiting for the phone to collect the address and the tokens…")
            elif now == "paired":
                at = state.get("paired_at")
                print(f"Paired at {time.strftime('%H:%M', time.localtime(at)) if at else '?'}. "
                      "Check the phone connections in the web view: an unknown pairing is an alarm.")
                return 0
            else:
                print("\n" + PAIR_ENDS.get(str(now), f"The pairing ended ({now}). Nothing was sent."))
                return 1
            said = now
            time.sleep(1)
            state = _local(c, "GET", "/pair")
    except KeyboardInterrupt:
        try:
            _local(c, "POST", "/pair/cancel")
        except (localpeer.NotOurs, _HTTPStatus, OSError, ValueError):
            pass
        print("\nCancelled: the code no longer works. Nothing was sent.")
        return 130
    except localpeer.NotOurs as e:
        print(f"Stopped: {e}.", file=sys.stderr)
        return 1
    except (_HTTPStatus, OSError, ValueError):
        print("The server stopped: the code no longer works.", file=sys.stderr)
        return 1


def cmd_set_host(args) -> int:
    c = cfg.load(args.config)
    cfg.save(args.config, replace(c, host=cfg.check_public_host(args.host)))
    print(f"Host set to {args.host}. Restart the bridge: it makes a new certificate for the address, with the same key.")
    print(f"Then pair the app again ({_prog()} pair): its pairing holds the address.")
    return 0


def cmd_set_free_only(args) -> int:
    """The "free only" switch: paid usage stays the user's choice unless they turn it on. The agent folder's opencode.json follows it, and a running service restarts to use it."""
    from .chat import opencode

    c = cfg.load(args.config)
    if args.state is None:
        print(f"Free only: {'on' if c.free_only else 'off'}.")
        return 0
    on = args.state == "on"
    cfg.save(args.config, replace(c, free_only=on))
    if on:
        print("Free only: on. Claude Code runs only with a subscription login and the models it covers; OpenCode "
              "only on its free providers and free models; no paid service's key reaches a turn.")
    else:
        print("Free only: off. The agents run with whatever login or provider you set up, paid or free; the app "
              "marks paid models.")
    folder = Path(c.agent_dir) if c.agent_dir else None
    if c.opencode_bin and folder is not None and (folder / opencode.CONFIG_FILE).exists():
        opencode.write_config(folder, on)
        print(f"Wrote {folder / opencode.CONFIG_FILE} for it.")
    if _service() is not None and runs_config(args.config) and \
            _systemctl("is-active", UNIT_NAME).stdout.strip() == "active":
        print("Restarting the service to use it...")
        return _control("restart")
    print(f"The server uses it when it starts ({_prog()} start, or {_prog()} run).")
    return 0


def cmd_notes(args) -> int:
    """The agent's notes per app, read and deleted straight from state/notes: same user, no server, no token.
    off and on set notes in config.json, and a running service restarts to use it, as set-free-only does."""
    from . import notes
    from .devicecache import DeviceCache
    from .text import terminal_safe

    c = cfg.load(args.config)
    store = notes.NoteStore(c.state / "notes")
    snapshot = DeviceCache(c.state / "device.json").snapshot
    now = notes.current(snapshot)
    if args.action in ("off", "on"):
        on = args.action == "on"
        cfg.save(args.config, replace(c, notes=on))
        print("Notes per app: on. The agent writes notes on driving the apps with flipper_note, and later "
              "conversations get them back as untrusted data." if on else
              "Notes per app: off. flipper_note refuses, and no note is handed to the agent; the notes on disk are "
              f"kept ({_prog()} notes delete --all removes them).")
        if _service() is not None and runs_config(args.config) and \
                _systemctl("is-active", UNIT_NAME).stdout.strip() == "active":
            print("Restarting the service to use it...")
            return _control("restart")
        print(f"The server uses it when it starts ({_prog()} start, or {_prog()} run).")
        return 0
    if args.action is None:
        found = store.list()
        print(f"Notes per app: {'on' if c.notes else 'off'}, {len(found)} kept in {store.root}.")
        if not found:
            return 0
        here = notes.installed(snapshot)
        print(f"  {'firmware':<16} {'appid':<32} {'chars':>5}  {'written':<16}  build        installed now")
        for n in found:
            if now is None or n.origin != now[0]:
                build, inst = "-", "-"
            else:
                build = "same" if n.build == now[1] else "other"
                inst = "yes" if n.appid in here else ("no" if snapshot.apps is not None else "?")
            written = time.strftime("%Y-%m-%d %H:%M", time.localtime(n.written_at))
            print(f"  {n.origin:<16} {n.appid:<32} {len(n.text):>5}  {written:<16}  {build:<12} {inst}")
        if now is None:
            print("  No firmware known yet, so the build and whether each app is installed are not known.")
        elif snapshot.apps is None:
            print("  The Flipper's apps are not learned yet: ? marks what is not known.")
        return 0
    if args.action == "delete" and args.all:
        if args.appid or args.firmware:
            print("delete --all takes no appid and no --firmware.", file=sys.stderr)
            return 2
        count = len(store.list())
        if not count:
            print("No notes to delete.")
            return 0
        if not (args.yes or _ask(f"Delete all {count} notes? [y/N] ")):
            print(f"  Nothing deleted. Later: {_prog()} notes delete --all --yes")
            return 1
        print(f"Deleted {store.delete_all()} notes.")
        return 0
    if not args.appid:
        print(f"Name the app: {_prog()} notes {args.action} <appid> (the list shows them).", file=sys.stderr)
        return 2
    origin = notes.origin_key(args.firmware) if args.firmware else (now[0] if now else None)
    if origin is None:
        print("No firmware known yet: name it with --firmware (the list shows each note's).", file=sys.stderr)
        return 2
    appid = args.appid
    if args.action == "show":
        note = store.get(origin, appid)
        if note is None:
            print(f"No note on {terminal_safe(appid)} for the firmware {origin}.", file=sys.stderr)
            return 1
        written = time.strftime("%Y-%m-%d %H:%M", time.localtime(note.written_at))
        print(f"Note on {note.appid} ({note.origin}), written {written} on the build "
              f"{terminal_safe(note.build or '(unknown)')}, {len(note.text)} characters. The agent wrote it, from "
              "what it saw on the Flipper:")
        print(terminal_safe(note.text))
        return 0
    if store.delete(origin, appid):
        print(f"Deleted the note on {appid} ({origin}).")
        return 0
    print(f"No note on {terminal_safe(appid)} for the firmware {origin}.", file=sys.stderr)
    return 1


def cmd_set_firmware_sources(args) -> int:
    c = cfg.load(args.config)
    names = profiles.load_index().profiles
    if args.origin not in names:
        print(f"Unknown firmware {args.origin!r}: use one of {', '.join(sorted(names))}.", file=sys.stderr)
        return 2
    sources = dict(c.firmware_sources)
    if args.remove:
        if args.path:
            print("Give a path or --remove, not both.", file=sys.stderr)
            return 2
        sources.pop(args.origin, None)
        cfg.save(args.config, replace(c, firmware_sources=sources))
        print(f"No local source tree for {args.origin} now. Restart the bridge ({_prog()} restart) to use it.")
        return 0
    if not args.path:
        now = sources.get(args.origin)
        print(f"{args.origin}: {now or 'no local source tree'} (give a path to set it, or --remove)")
        return 0
    folder = Path(args.path).expanduser().resolve()
    if not folder.is_dir():
        print(f"{folder} is not a folder.", file=sys.stderr)
        return 2
    sources[args.origin] = str(folder)
    cfg.save(args.config, replace(c, firmware_sources=sources))
    print(f"Source tree for {args.origin}: {folder}. flipper_app reads the apps' READMEs from it, "
          f"after a restart ({_prog()} restart).")
    return 0


def cmd_rotate_tokens(args) -> int:
    import secrets

    c = cfg.load(args.config)
    which = sorted(set(args.only or ["phone", "mcp", "view", "app"]))
    changes = {f"{name}_token": secrets.token_urlsafe(32) for name in which}
    cfg.save(args.config, replace(c, **changes))
    print(f"New tokens written to {args.config}: {', '.join(which)}.")
    print("The old ones stop working once the bridge restarts:\n")
    print(f"  {_prog()} restart     (or restart '{_prog()} run')\n")
    print("Then update where each one is used. Do not paste tokens into chats or issues.")
    if "phone" in which:
        print("\n- Phone token (Android app, Server section):")
        print(f"    {_prog()} token phone")
    if "mcp" in which:
        print("\n- MCP token (Claude Code): " + _update_mcp_entry(replace(c, **changes)))
        print("  If you use .mcp.json with ${ZEFFIN_MCP_TOKEN}, export the new value instead.")
    if "view" in which:
        print("\n- View token (live view): open the view again and sign in with")
        print(f"    {_prog()} token view")
    if "app" in which:
        print("\n- App token (Zeffin app): pair the app again with")
        print(f"    {_prog()} pair")
    return 0


def _update_mcp_entry(c: cfg.Config) -> str:
    """Put the new MCP token into the user-scope `flipper` entry, if there is one; never prints the token."""
    from .chat import command

    path = command.claude_config_file()
    result = command.set_user_mcp_server("flipper", _mcp_server(c), path, only_if_present=True)
    if result in ("written", "same"):
        return f"updated the `flipper` entry in {path}. Check with: claude mcp list"
    if result == "missing":
        return f"no `flipper` entry in {path}. Register it with: {_prog()} mcp-config"
    return f"{path} could not be read or has an unexpected shape: left as is. Then run: {_prog()} mcp-config"


def cmd_report(args) -> int:
    c = cfg.load(args.config)
    path = c.state / "metrics.jsonl"
    if not path.exists():
        print(f"No metrics yet at {path}")
        return 1
    hours = args.since if args.since is not None else args.hours
    print(report(read_events(path, time.time() - hours * 3600, max_bytes=1 << 62), hours))
    return 0


def cmd_install_service(args) -> int:
    return install_service(args.config, args.minimal)


def install_service(config_path: Path, minimal: bool = False) -> int:
    c = cfg.load(config_path)
    agent = bool(c.agent_dir)
    c.state.mkdir(parents=True, exist_ok=True, mode=0o700)
    dest = unit_path()
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not shutil.which("systemctl"):
        dest.write_text(render_unit(config_path, c.state, minimal, agent))
        print(f"Wrote {dest}; systemctl not found, start it yourself.")
        return 0
    ok, detail = _start_and_check(dest, render_unit(config_path, c.state, minimal, agent))
    if not ok and not minimal:
        print(f"Sandboxed unit did not stay up ({detail}); retrying without sandboxing directives.")
        ok, detail = _start_and_check(dest, render_unit(config_path, c.state, True, agent))
        if ok:
            print("Running with the minimal unit (NoNewPrivileges and UMask only).")
    print(f"Wrote {dest}; service: {detail}.")
    print(link_cli())
    if not ok:
        print(f"It is not running. Check: {_prog()} logs -n 50")
        return 1
    print("To keep it running without a login session: sudo loginctl enable-linger $USER")
    return 0


# ---------------------------------------------------------------- zf: day-to-day commands

def link_cli(home: Path | None = None) -> str:
    """Link ~/.local/bin/zf to this venv's zf script; never replaces a file that is not a link."""
    target = Path(sys.executable).with_name("zf")  # not resolved: the venv's python is a symlink
    if not target.exists():
        return f"No zf script next to {sys.executable}: reinstall with pip install -e server."
    bindir = (home or Path.home()) / ".local" / "bin"
    bindir.mkdir(parents=True, exist_ok=True)
    link = bindir / "zf"
    if link.is_symlink():
        if os.readlink(link) == str(target):
            return f"{link} is ready."
        link.unlink()
    elif link.exists():
        return f"{link} exists and is not a link; left as is. Use {target} instead."
    link.symlink_to(target)
    if str(bindir) in os.environ.get("PATH", "").split(os.pathsep):
        return f"Linked {link}: run zf from anywhere."
    return (f"Linked {link}. It is not on your PATH yet: log in again, or add\n"
            f'    export PATH="$HOME/.local/bin:$PATH"\n  to your shell profile.')


def cmd_install_cli(args) -> int:
    print(link_cli())
    return 0


def fetch_status(c: cfg.Config, timeout: float = 3.0, *, raise_not_ours: bool = False) -> tuple[dict | None, str | None]:
    """The running server's /status on the loopback MCP listener: (data, None) or (None, why not).
    The MCP token goes only to a listener localpeer has checked is ours; with raise_not_ours, NotOurs is raised."""
    try:
        status, body = localpeer.request("127.0.0.1", c.mcp_port, "GET", "/status", c.mcp_token, timeout=timeout)
    except localpeer.NotOurs as e:
        if raise_not_ours:
            raise
        return None, str(e)
    except OSError:
        return None, "not running (nothing answers on the local port)"
    if status == 401:
        return None, f"running, but it rejected the MCP token: tokens changed since it started ({_prog()} restart)"
    if status == 404:
        return None, f"running an older version without /status ({_prog()} restart)"
    if not 200 <= status < 300:
        return None, f"answered HTTP {status}"
    try:
        return json.loads(body), None
    except ValueError:
        return None, "answered something that is not JSON"


def describe_status(data: dict, installed: str, file_pin: str | None = None) -> list[str]:
    return _describe_status(data, installed, _prog(), file_pin)


def describe_chat(chat: dict) -> list[str]:
    return _describe_chat(chat, _prog())


def cmd_status(args) -> int:
    from . import __version__

    c = cfg.load(args.config)
    service = _service()
    if service is None:
        print("Service: not installed as a systemd user service" + ("" if shutil.which("systemctl") else " (no systemctl)"))
    else:
        restarts = service.get("NRestarts", "?")
        since = service.get("ActiveEnterTimestamp") or "-"
        print(f"Service: {service.get('ActiveState', '?')} ({service.get('SubState', '?')}), since {since}, restarts {restarts}")
    data, error = fetch_status(c)
    if data is None:
        print(f"Server:  {error}")
        print(f"Installed version {__version__}.")
        return 1
    try:
        on_disk = tlskey.read(data_dir(args.config))
    except tlskey.KeyFileError as e:
        print(f"Key file: {e}")
        on_disk = None
    print("\n".join(describe_status(data, __version__, on_disk.pin if on_disk else None)))
    return 0


def _control(action: str) -> int:
    if not shutil.which("systemctl"):
        print("systemctl not found: start or stop 'zeffin run' yourself.")
        return 1
    if _service() is None:
        print(f"The service is not installed. Install it with: {_prog()} install-service")
        return 1
    result = _systemctl(action, UNIT_NAME)
    if result.returncode != 0:
        print(f"systemctl {action} failed: {result.stderr.strip()}")
        return 1
    if action == "stop":
        print("Stopped.")
        return 0
    time.sleep(2)
    state = _systemctl("is-active", UNIT_NAME).stdout.strip()
    print(f"Service: {state}.")
    if state != "active":
        print(f"Check the log: {_prog()} logs -n 50")
        return 1
    return 0


def cmd_start(args) -> int:
    return _control("start")


def cmd_stop(args) -> int:
    return _control("stop")


def cmd_restart(args) -> int:
    return _control("restart")


def cmd_logs(args) -> int:
    if not shutil.which("journalctl"):
        print("journalctl not found.")
        return 1
    command = ["journalctl", "--user", "-u", UNIT_NAME, "-n", str(args.lines)]
    command += ["-f"] if args.follow else ["--no-pager"]
    os.execvp(command[0], command)  # hand over the terminal: -f and Ctrl+C behave as usual
    return 0


def _git(root: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)


def repo_root() -> Path | None:
    """The git checkout this (editable) install runs from."""
    out = subprocess.run(["git", "-C", str(Path(__file__).resolve().parent), "rev-parse", "--show-toplevel"],
                         capture_output=True, text=True)
    return Path(out.stdout.strip()) if out.returncode == 0 and out.stdout.strip() else None


def pip_install(root: Path) -> int:
    server = root / "server"
    return subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-r", str(server / "requirements.txt"),
                           "-e", str(server)]).returncode


def cmd_update(args) -> int:
    root = repo_root()
    if root is None:
        print("This install is not a git checkout (pip install -e server from a clone): update it by hand.")
        return 1
    if _git(root, "status", "--porcelain", "--untracked-files=no").stdout.strip():
        print(f"{root} has local changes; commit or discard them first (git -C {root} status).")
        return 1
    fetch = _git(root, "fetch", "--quiet")
    if fetch.returncode != 0:
        print(f"git fetch failed: {fetch.stderr.strip()}")
        return 1
    upstream = _git(root, "rev-parse", "@{u}")
    if upstream.returncode != 0:
        print("The current branch has no upstream branch to update from.")
        return 1
    head = _git(root, "rev-parse", "HEAD").stdout.strip()
    if head == upstream.stdout.strip():
        running, why = running_commit(args.config)
        if running == head:
            print("Already up to date; the service runs this code.")
            return 0
        if why is not None:
            print(f"Already up to date. Could not tell which code the service runs ({why}); "
                  f"{_prog()} restart runs the checked-out code.")
            return 0
        print(f"Already up to date, but the service runs {running[:7] if running else 'older code'}, "
              f"not the checked-out {head[:7]}. Installing dependencies...")
        return _install_and_restart(root, args)
    if _git(root, "merge-base", "--is-ancestor", "HEAD", "@{u}").returncode != 0:
        print("Your copy's history does not match the remote's (it was rewritten, or you have local commits).")
        print("If there is nothing of yours to keep in it, align it once with:")
        print(f"    git -C {root} reset --hard @{{u}}")
        print(f"and run {_prog()} update again.")
        return 1
    merged = _git(root, "merge", "--ff-only", "--quiet", "@{u}")
    if merged.returncode != 0:
        print(f"git merge failed: {merged.stderr.strip()}")
        return 1
    count = _git(root, "rev-list", "--count", f"{head}..HEAD").stdout.strip()
    print(f"Updated: {count} new commit(s). Installing dependencies...")
    return _install_and_restart(root, args)


def running_commit(config_path: Path) -> tuple[str | None, str | None]:
    """The commit the running server started from: (commit, None), or (None, why it is unknown).

    (None, None) means a server from before /status reported its commit: older code than any checkout with zf
    update's check.
    """
    if _service() is None:
        return None, "no systemd service installed"
    try:
        c = cfg.load(config_path)
    except (cfg.ConfigError, OSError, ValueError) as e:
        return None, f"cannot read {config_path} ({type(e).__name__})"
    data, error = fetch_status(c)
    if data is None:
        return None, f"server {error}"
    if "commit" not in data:
        return None, None
    if not isinstance(data["commit"], str) or not data["commit"]:
        return None, "the server does not know its git commit"
    return data["commit"], None


def _install_and_restart(root: Path, args) -> int:
    if pip_install(root) != 0:
        print("pip install failed; the service was not restarted.")
        return 1
    if _service() is None:
        print("Done. Restart the bridge yourself (no systemd service installed).")
        return 0
    if _control("restart") != 0:
        return 1
    # A new process, so the freshly installed code reports its own version.
    return subprocess.run(self_command(args.config, "status")).returncode


# ---------------------------------------------------------------- zf agent: Claude Code for the chat

def _example_claude_md() -> Path | None:
    root = repo_root()
    path = root / "CLAUDE.md.example" if root else None
    return path if path and path.exists() else None


def cmd_chat(args) -> int:
    from .chat import client

    key = tlskey.read(data_dir(args.config))
    if key is None:
        print(f"No server key yet: start the server first ({_prog()} start).")
        return 1
    return client.run(cfg.load(args.config), key.pin, new=args.new or bool(args.mode), mode=args.mode,
                      prefix=args.conversation, only_list=args.list, test=args.test, lang=args.lang)


def cmd_agent(args) -> int:
    return {"setup": cmd_agent_setup, "probe": cmd_agent_probe}[args.agent_cmd](args)


def _agent_instructions() -> str | None:
    """The agent's instructions from CLAUDE.md.example: its first lines explain where to copy it, which the agent
    does not need."""
    example = _example_claude_md()
    if example is None:
        return None
    lines = example.read_text().splitlines()
    body = "\n".join([lines[0], *lines[3:]]) if len(lines) > 3 and lines[2].startswith("Copy this file") else "\n".join(lines)
    return body.rstrip() + "\n"


def cmd_agent_setup(args) -> int:
    from .chat import command

    if getattr(args, "agent", AGENT_IDS[0]) == "opencode":
        return cmd_agent_setup_opencode(args)
    c = cfg.load(args.config)
    claude = command.find_claude()
    if claude is None:
        print("Claude Code (claude) is not installed for this user, or not on your PATH.")
        print("Install it as described on code.claude.com, sign in once with `claude` and /login, then run this again.")
        return 1
    version = command.claude_version(claude)
    print(f"Claude Code: {claude} ({version or 'version unknown'})")
    current = Path(c.agent_dir) if c.agent_dir else None
    folder = (args.dir or current or cfg.DEFAULT_AGENT).expanduser().resolve()
    if current is not None and current != folder:
        # Its files are never touched; only the trust entry zf added for it goes.
        command.untrust_folder(current)
        if current.exists():
            print(f"{current} is no longer the agent folder; it was left as is.")
    folder.mkdir(parents=True, exist_ok=True)
    claude_md = folder / "CLAUDE.md"
    body = _agent_instructions()
    if not claude_md.exists() and body is not None:
        claude_md.write_text(body)
        print(f"Wrote {claude_md} (instructions for the agent; edit it as you like).")
    else:
        print(f"Agent folder: {folder}" + (" (CLAUDE.md kept as is)" if claude_md.exists() else ""))
    problem = command.agent_settings_problem(folder, c.free_only)
    if problem:
        print(f"Refused: {problem}. Free only is on, so chats must use your subscription; remove it and run this "
              "again." if c.free_only else f"Refused: {problem}. Fix it and run this again.")
        return 1
    logged_in, method = command.auth_status(claude, free_only=c.free_only)
    if not logged_in:
        print("Claude Code is not signed in. Run `claude`, type /login, and run this again.")
        return 1
    if c.free_only and method not in command.SUBSCRIPTION_AUTH:
        print(f"Claude Code signs in with {method}, which is not a subscription login, and free only is on. Use "
              "/login with your Claude account (or claude setup-token) and run this again.")
        return 1
    print(f"Signed in: {method}." + ("" if method in command.SUBSCRIPTION_AUTH else
                                     " It is not a subscription: every turn bills per use."))
    found = command.billing_vars(os.environ)
    if found and c.free_only:
        print(f"Note: this shell has {', '.join(found)}. With free only on, the service never passes them to claude.")
    model = args.model or c.chat_model
    changed = (c.agent_dir, c.claude_bin, c.chat_model) != (str(folder), claude, model)
    c = replace(c, agent_dir=str(folder), claude_bin=claude, chat_model=model)
    cfg.save(args.config, c)
    print(f"Saved: agent folder, claude path and model {model}.")
    trust = command.trust_folder(folder)
    if trust == "trusted":
        print(f"Marked {folder} as trusted for Claude Code (its config file), so its permission rules apply: "
              "claude -p never shows the trust question.")
    elif trust == "kept":
        print("Could not update Claude Code's config to trust the folder: open claude once in it and accept the trust question.")
    if _after_setup(args.config, changed, "claude") != 0:
        return 1
    print(f"Optional check of your Claude Code version: {_prog()} agent probe")
    return 0


def _after_setup(config_path: Path, changed: bool, program: str) -> int:
    """After an agent's setup: the installed service is reinstalled (so it can start the agent) only when it runs this
    very config; a service of another config is left alone (a probe's, a second install's config must never move it)."""
    service = _service()
    if service is None:
        print(f"Restart the bridge to use it ({_prog()} run, or {_prog()} install-service).")
    elif not runs_config(config_path):
        print(f"The installed service runs another configuration, so it was left alone. Restart the server that uses "
              f"{Path(config_path).resolve()} to use this ({_prog()} --config {Path(config_path).resolve()} run).")
    elif changed:
        print(f"Reinstalling the service so it can start {program}...")
        return install_service(config_path)
    return 0


def cmd_agent_setup_opencode(args) -> int:
    """OpenCode for chats: the agent folder's AGENTS.md and opencode.json, its program and version, and its models (only
    the free ones with free only on). Its credentials file is never read: OpenCode's own commands say what it can
    use."""
    from .chat import opencode

    c = cfg.load(args.config)
    binary = opencode.find_opencode()
    low, below = (".".join(map(str, v)) for v in (opencode.MIN_VERSION, opencode.BELOW_VERSION))
    if binary is None:
        print("OpenCode (opencode) is not installed for this user, or not on your PATH.")
        print(f"Install the version this server runs, for example `npm install -g opencode-ai@{low}`, "
              "then run this again.")
        return 1
    found = opencode.parse_version((subprocess.run([binary, "--version"], capture_output=True, text=True, timeout=30,
                                                   env=opencode.agent_env(os.environ)).stdout or ""))
    shown = ".".join(map(str, found)) if found else "version unknown"
    print(f"OpenCode: {binary} ({shown})")
    if not opencode.version_ok(found):
        print(f"Refused: this server runs OpenCode from {low} up to, but not including, {below}, the versions checked "
              f"with it. Install one, for example `npm install -g opencode-ai@{low}`, and run this again.")
        return 1
    current = Path(c.agent_dir) if c.agent_dir else None
    folder = (args.dir or current or cfg.DEFAULT_AGENT).expanduser().resolve()
    if current is not None and current != folder:
        print(f"The agents share one folder, {current}: run {_prog()} agent setup --dir {folder} first to move it.")
        return 1
    folder.mkdir(parents=True, exist_ok=True)
    body = _agent_instructions()
    if body is not None and opencode.write_agents_md(folder, body):
        print(f"Wrote {folder / 'AGENTS.md'} (instructions for the agent; edit it as you like).")
    else:
        print(f"Agent folder: {folder}" + (" (AGENTS.md kept as is)" if (folder / "AGENTS.md").exists() else ""))
    opencode.write_config(folder, c.free_only)
    print(f"Wrote {folder / opencode.CONFIG_FILE}: " + ("only the free providers, " if c.free_only else "")
          + "no sharing, no auto-update, the Flipper tools allowed (this command writes it again; do not edit it).")
    agent = opencode.OpenCode(replace(c, agent_dir=str(folder), opencode_bin=binary))
    asyncio.run(agent.check())
    if agent.health["config"]:
        print(f"Refused: OpenCode's configuration, with your global one, {agent.health['config']}. Fix the global "
              "file (~/.config/opencode) and run this again" + (": free only is on." if c.free_only else "."))
        return 1
    signed = [p for p in agent.health["providers"] if p != opencode.ZEN]
    if c.free_only and "nvidia" not in signed:
        print("NVIDIA NIM is not signed in, so OpenCode uses OpenCode Zen's free models (no account). For NIM, the "
              "default: create a free key at build.nvidia.com, run `opencode auth login` on this server and choose "
              "NVIDIA, then run this again. Never paste the key in a chat.")
    elif not c.free_only and not signed:
        print("No provider is signed in, so OpenCode uses OpenCode Zen's free models (no account). To use another "
              "provider, NVIDIA NIM's free tier among them, run `opencode auth login` on this server, then run this "
              "again. Never paste a key in a chat.")
    elif not c.free_only:
        print("Signed in: " + ", ".join(opencode.provider_name(p) for p in signed) + ".")
    models = agent.models()
    offered = [m["value"] for m in models if m["available"]]
    if not offered:
        print("Refused: OpenCode lists none of its models here. Check this server's internet access and run this "
              "again.")
        return 1
    free = [m["value"] for m in models if m["available"] and not m["paid"]]
    paid = [m["value"] for m in models if m["available"] and m["paid"]]
    print(f"Free models: {', '.join(free) or 'none'}." + (f" Paid models: {len(paid)}." if paid else ""))
    print(f"Default model for new conversations: {offered[0]} (until one is chosen in the app).")
    print("Free providers may keep what they receive to improve their models, Flipper dumps included.")
    changed = (c.agent_dir, c.opencode_bin) != (str(folder), binary)
    cfg.save(args.config, replace(c, agent_dir=str(folder), opencode_bin=binary))
    print("Saved: agent folder and opencode path.")
    if _after_setup(args.config, changed, "opencode") != 0:
        return 1
    print(f"Optional check of your OpenCode: {_prog()} agent probe --agent opencode")
    return 0


class _NoBridge(Exception):
    """The throwaway bridge or its simulator did not come up; the message says which."""


@contextlib.contextmanager
def _throwaway_bridge(c: cfg.Config, prefix: str, learned: bool = False):
    """A throwaway bridge (its own data folder and ports, under the given config's state folder) with the simulator,
    for the probes: never the running service, its config.json or its MCP listener, which reach the real phone and
    Flipper. Yields (its Config, its folder); everything is stopped and removed afterwards. With learned, it waits
    until the simulator's firmware and apps are learned too."""
    import socket
    import tempfile

    c.state.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=prefix, dir=c.state))
    procs: list[subprocess.Popen] = []
    try:
        ports = []
        for _ in range(2):
            with socket.socket() as s:
                s.bind(("127.0.0.1", 0))
                ports.append(s.getsockname()[1])
        conf = work / "config.json"
        cfg.create(conf, "127.0.0.1", ports[0], ports[1], work / "state")
        throwaway = cfg.load(conf)
        for module in ("bridge.cli", "bridge.sim"):
            argv = [sys.executable, "-I", "-m", module, "--config", str(conf)] + (["run"] if module == "bridge.cli" else [])
            procs.append(subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                          stderr=subprocess.DEVNULL, start_new_session=True))
            deadline = time.monotonic() + 20
            while module == "bridge.cli" and fetch_status(throwaway)[0] is None:
                if time.monotonic() > deadline:
                    raise _NoBridge("The throwaway bridge did not start.")
                time.sleep(0.3)

        def up(data: dict) -> bool:
            firmware = data.get("firmware") or {}
            return bool((data.get("screen") or {}).get("seq")) and (
                not learned or (firmware.get("state") == "done" and not firmware.get("learning")))

        deadline = time.monotonic() + 30
        while not up(fetch_status(throwaway)[0] or {}):
            if time.monotonic() > deadline:
                raise _NoBridge("The simulator sent no screen." if not learned else
                                "The simulator's firmware and apps were not learned.")
            time.sleep(0.3)
        yield throwaway, work
    finally:
        for proc in procs:
            with contextlib.suppress(ProcessLookupError):
                proc.terminate()
        for proc in procs:
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
        shutil.rmtree(work, ignore_errors=True)


def cmd_agent_probe_opencode(args) -> int:
    """The OpenCode probe against a throwaway bridge with the simulator (_throwaway_bridge)."""
    import socket

    from .chat import opencode
    from .chat.probe_opencode import ProbeSettings, run_probe, scrubbed

    c = cfg.load(args.config)
    say = partial_print(args.json)
    binary = c.opencode_bin or opencode.find_opencode()
    if not binary:
        say(f"opencode not found: run {_prog()} agent setup --agent opencode first.")
        return 1
    say("Probing OpenCode with a few short turns on a free model, against a throwaway bridge and the simulator "
        "(1 to 3 minutes" + (", and 2 or 3 more for --notes" if args.notes else "") + ").")
    try:
        with _throwaway_bridge(c, "probe-opencode-", learned=args.notes) as (throwaway, work):
            settings = ProbeSettings(opencode_bin=binary, agent_dir=str(work), mcp_token=throwaway.mcp_token,
                                 mcp_port=throwaway.mcp_port, audit=str(throwaway.state / "audit.jsonl"))
            report = asyncio.run(run_probe(settings, out=say, model=args.model, notes=args.notes))
            secrets = [throwaway.mcp_token, throwaway.phone_token, throwaway.view_token, throwaway.app_token,
                       c.mcp_token, str(work), str(Path.home()), socket.gethostname()]
            report["scrubbed"] = scrubbed(report, secrets)
            if not report["scrubbed"]:
                report = {"report_version": report.get("report_version"), "scrubbed": False}
    except _NoBridge as e:
        say(str(e))
        return 1
    if args.json:
        print(json.dumps(report, sort_keys=True))
        return 0
    print("\nReport (no tokens, paths or texts; paste it whole):\n")
    print(json.dumps(report, separators=(",", ":"), sort_keys=True))
    return 0 if report.get("ok") else 1


def partial_print(to_stderr: bool):
    return lambda *a, **k: print(*a, **k, file=sys.stderr if to_stderr else sys.stdout)


def cmd_agent_probe(args) -> int:
    from functools import partial

    from .chat import command
    from .chat.probe import run_probe

    if getattr(args, "agent", AGENT_IDS[0]) == "opencode":
        return cmd_agent_probe_opencode(args)
    if getattr(args, "notes", False):
        return cmd_agent_probe_notes(args)
    c = cfg.load(args.config)
    say = partial(print, file=sys.stderr if args.json else sys.stdout)  # with --json, stdout is the report
    claude = c.claude_bin or command.find_claude()
    if not claude:
        say(f"claude not found: run {_prog()} agent setup first.")
        return 1
    if args.remote and not args.yes and not _confirm_remote_probe(say):
        return 1
    try:
        data, _ = fetch_status(c, raise_not_ours=True)
    except localpeer.NotOurs as e:
        print(f"Not probing: {e}.", file=sys.stderr)
        return 1
    say("Probing Claude Code with about 17 short turns on Haiku, in a temporary folder (2 to 4 minutes"
        + (", then about 9 more with Remote Control" if args.remote else "") + ").")
    if data is None:
        say("The bridge is not running: the Flipper MCP check will be skipped.")
    say()
    report = run_probe(c, claude, mcp_running=data is not None, out=say, remote=args.remote,
                       fixtures=args.fixtures.resolve() if args.fixtures else None)
    if args.json:
        print(json.dumps(report, sort_keys=True))
        return 0
    if args.fixtures:
        found = report.get("fixtures") or {}
        print(f"\nFixtures: {found.get('files', 0)} turns in {args.fixtures}" if found.get("written")
              else "\nFixtures: none written (the self-check refused them).")
    print("\nReport (no tokens, paths or texts; paste it whole):\n")
    print(json.dumps(report, separators=(",", ":"), sort_keys=True))
    return 0


def cmd_agent_probe_notes(args) -> int:
    """`zf agent probe --notes` with Claude Code: only the notes per app, two tiny Haiku turns against a throwaway
    bridge and the simulator (_throwaway_bridge), never the running service, its Flipper or its notes."""
    import socket

    from .chat import command
    from .chat.probe import run_notes_probe
    from .chat.probe_opencode import scrubbed

    c = cfg.load(args.config)
    say = partial_print(args.json)
    claude = c.claude_bin or command.find_claude()
    if not claude:
        say(f"claude not found: run {_prog()} agent setup first.")
        return 1
    say("Probing the notes per app with Claude Code: two tiny Haiku turns against a throwaway bridge and the "
        "simulator (under a minute).")
    try:
        with _throwaway_bridge(c, "probe-notes-", learned=True) as (throwaway, work):
            report = run_notes_probe(throwaway, claude, out=say)
            secrets = [throwaway.mcp_token, throwaway.phone_token, throwaway.view_token, throwaway.app_token,
                       c.mcp_token, str(work), str(Path.home()), socket.gethostname()]
            report["scrubbed"] = scrubbed(report, secrets)
            if not report["scrubbed"]:
                report = {"v": report.get("v"), "scrubbed": False}
    except _NoBridge as e:
        say(f"{e} Nothing was probed.")
        return 1
    if args.json:
        print(json.dumps(report, sort_keys=True))
        return 0
    print("\nReport (no tokens, paths or texts; paste it whole):\n")
    print(json.dumps(report, separators=(",", ":"), sort_keys=True))
    return 0 if report.get("ok") else 1


def _confirm_remote_probe(say=print) -> bool:
    say("The Remote Control steps start an interactive Claude Code session with Remote Control on, twice.\n"
        "- It shows in your Claude app and at claude.ai/code as \"zeffin probe\" while the probe runs;\n"
        "  the second start resumes the same conversation, so it may show again for a while.\n"
        "- While it is connected, Anthropic stores its transcript (code.claude.com/docs/en/remote-control).\n"
        "  The probe's prompts hold nothing private: a probe word, numbers, test words and a one-colour image.\n"
        "- It uses your subscription, like any other turn. Archive the session in the app afterwards if you like.")
    if not sys.stdin.isatty():
        say("Not a terminal: add --yes to start it anyway.")
        return False
    try:
        say("Start it? [y/N] ", end="", flush=True)
        return input().strip().lower() in ("y", "yes")
    except (EOFError, KeyboardInterrupt):
        say()
        return False


MENU = [
    ("Status", ["status"]),
    ("Chat with the agent", ["chat"]),
    ("Report (last hour)", ["report", "1"]),
    ("Restart", ["restart"]),
    ("Update", ["update"]),
    ("Log (last 100 lines)", ["logs"]),
    ("Pair the app (QR)", ["pair"]),
    ("Server key", ["key"]),
    ("Live view token", ["token", "view"]),
]


def menu(config: Path) -> int:
    while True:
        print()
        for number, (label, _) in enumerate(MENU, 1):
            print(f"  {number}  {label}")
        print("  q  Quit")
        try:
            choice = input("> ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if choice in ("q", "quit", "exit", ""):
            return 0
        if not choice.isdigit() or not 1 <= int(choice) <= len(MENU):
            print("Type a number from the list, or q.")
            continue
        command = MENU[int(choice) - 1][1]
        if command[0] in ("logs", "chat"):  # run as a child so the menu comes back (and Ctrl+C stays there)
            subprocess.run(self_command(config, *command))
            continue
        print()
        main(["--config", str(config), *command])


COMMANDS = {
    "init": cmd_init, "run": cmd_run, "show": cmd_show, "token": cmd_token,
    "mcp-config": cmd_mcp_config, "pair": cmd_pair, "report": cmd_report, "set-host": cmd_set_host,
    "set-free-only": cmd_set_free_only, "notes": cmd_notes,
    "set-firmware-sources": cmd_set_firmware_sources,
    "rotate-tokens": cmd_rotate_tokens, "key": cmd_key,
    "install-service": cmd_install_service, "install-cli": cmd_install_cli,
    "status": cmd_status, "start": cmd_start, "stop": cmd_stop, "restart": cmd_restart,
    "update": cmd_update, "logs": cmd_logs, "agent": cmd_agent, "chat": cmd_chat,
}

if __name__ == "__main__":
    raise SystemExit(main())
