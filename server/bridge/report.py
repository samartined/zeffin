# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""What zf report and zf status print: the metrics log summed up, and the running server's /status in words."""
from __future__ import annotations

import os
import time
from pathlib import Path

from .stats import summarize
from .text import terminal_safe


def report(events: list[dict], hours: float) -> str:
    def fmt(ts):
        return time.strftime("%m-%d %H:%M:%S", time.localtime(ts))

    s = summarize(events)
    lines = [f"Last {hours:g} h: {s['events']} events",
             f"Phone connections: {len(s['sessions'])}, reached ready: {s['reached_ready']}"]
    for x in s["sessions"]:
        dur = f"{x['duration'] / 60:.1f} min" if x.get("duration") else "-"
        ff = f"{x['first_frame']:.2f}s" if x.get("first_frame") is not None else "none"
        lines.append(f"  {fmt(x['open'])}  {x['transport'] or '-':4}  up {dur:>10}  first frame {ff:>7}"
                     f"  frames {x.get('frames') if x.get('frames') is not None else '-':>6}"
                     + (f"  MTU {x['mtu']}" if x.get("mtu") else "") + f"  end: {x['reason']}"
                     + (f" (phone: {x['phone_reason']})" if x.get("phone_reason") else ""))
    if s["reconnect_median_s"] is not None:
        lines.append(f"Reconnect time (close -> next ready): median {s['reconnect_median_s']:.1f}s, max {s['reconnect_max_s']:.1f}s")
    if s["minutes"]:
        lines.append(f"Minutes observed: {s['minutes']}, connected: {s['minutes_connected']} "
                     f"({100 * s['minutes_connected'] / s['minutes']:.0f}%)")
    if s["minutes_connected"]:
        lines.append(f"Frames/s while connected: median {s['fps_median']:.2f}, max {s['fps_max']:.2f}; "
                     f"max gap between frames {s['max_gap_s']:.1f}s")
    if s["pings"]:
        lines.append(f"Keepalive pings: {s['pings_ok']}/{s['pings']} ok"
                     + (f"; RTT median {s['rtt_median_ms']:.0f} ms, p95 {s['rtt_p95_ms']:.0f} ms, max {s['rtt_max_ms']:.0f} ms"
                        if s["pings_ok"] else ""))
    if s["rejected"]:
        lines.append("Rejected phone connections: " + ", ".join(f"{k} x{v}" for k, v in sorted(s["rejected"].items()))
                     + f" (last {fmt(s['last_rejected']['ts'])})")
        e = s["last_bad_token"]
        if e:
            lines.append(f"  last bad token: header={e.get('has_header')}, 'Bearer ' prefix={e.get('bearer_prefix')}, "
                         f"length {e.get('token_length')} (expected {e.get('expected_length')})")
    if s["stream_failures"]:
        lines.append(f"Screen stream start failures: {s['stream_failures']} (last: {s['last_stream_failure']})")
    settles = [e for e in events if e.get("ev") == "settle" and "synced" in e]
    if settles:
        lines.append(f"Session starts that found the stop's reply (the stream read from a message boundary): "
                     f"{sum(1 for e in settles if e['synced'])}/{len(settles)}")
    restored = [e for e in events if e.get("ev") == "start" and e.get("gate")]
    if restored:
        e = restored[-1]
        since = e.get("gate_changed_at")
        lines.append(f"Starts with the agent paused or stopped from before the restart: {len(restored)} "
                     f"(last {fmt(e['ts'])}: {e['gate']} by {e.get('gate_by') or '?'}"
                     + (f" since {fmt(since)}" if isinstance(since, (int, float)) else "") + ")")
    return "\n".join(lines)


def _ago(seconds: float | None) -> str:
    if seconds is None:
        return "-"
    seconds = int(seconds)
    if seconds < 60:
        return f"{seconds} s"
    if seconds < 3600:
        return f"{seconds // 60} min"
    return f"{seconds // 3600} h {seconds % 3600 // 60} min"


def describe_status(data: dict, installed: str, zf: str = "zf", file_pin: str | None = None) -> list[str]:
    """The running server's /status in words. file_pin is the pin of the key in the data folder, to tell when the
    running server still serves an older one."""
    running = data.get("version")
    lines = [f"Server:  running {running}" + (f" (installed {installed}: {zf} restart)" if running != installed else "")]
    key = data.get("key")
    if isinstance(key, dict) and isinstance(key.get("fingerprint"), str):
        waits = file_pin is not None and key.get("pin") != file_pin
        lines.append(f"Key:     {key['fingerprint']}" + (f" (a new key waits for {zf} restart)" if waits else ""))
    relay = data.get("relay") or {}
    if data.get("connected"):
        parts = [data.get("transport") or "?", f"app {relay['app']}" if relay.get("app") else "older relay app",
                 relay.get("device"), f"MTU {relay['mtu']}" if relay.get("mtu") else None,
                 f"up {_ago(data.get('session_uptime_s'))}"]
        lines.append("Relay:   connected · " + " · ".join(p for p in parts if p))
    elif data.get("phone_attached"):
        lines.append("Relay:   connecting (phone attached, Flipper not ready yet)")
    else:
        lines.append("Relay:   not connected")
    lines += describe_firmware(data.get("firmware"))
    gate = data.get("gate", "?")
    changed = data.get("gate_changed_at")
    by = data.get("gate_by")
    lines.append(f"Agent:   {gate}" + (f" (since {time.strftime('%H:%M', time.localtime(changed))}"
                                         + (f", by {by}" if by else "") + ")" if changed else ""))
    screen = data.get("screen") or {}
    if screen.get("seq"):
        lines.append(f"Screen:  frame #{screen['seq']}, {_ago(screen.get('age_s'))} ago" + (" (stale)" if screen.get("stale") else ""))
    else:
        lines.append("Screen:  no frame yet")
    if data.get("last_ping_ms") is not None:
        lines.append(f"Ping:    {data['last_ping_ms']:.0f} ms round trip to the Flipper")
    if isinstance(data.get("chat"), dict):
        lines += describe_chat(data["chat"], zf)
    lines += describe_devices(data.get("devices"))
    return lines


DEVICE_KINDS = ("relay", "app", "view", "pairing")  # peers.KINDS, in the order zf status names them
NAME_WIDTH = 24


def _clock(ts: object, now: float) -> str:
    """HH:MM today, MM-DD HH:MM on another day."""
    if not isinstance(ts, (int, float)):
        return "?"
    day = time.localtime(ts)[:3] == time.localtime(now)[:3]
    return time.strftime("%H:%M" if day else "%m-%d %H:%M", time.localtime(ts))


def describe_devices(devices: object, now: float | None = None) -> list[str]:
    """The devices that used the public listener since the server started (/status "devices"). Their names and OS come
    from the devices themselves: printed terminal-safe. Nothing from a server without the list."""
    if not isinstance(devices, dict) or not isinstance(devices.get("items"), list):
        return []
    now = time.time() if now is None else now
    since = _clock(devices.get("since"), now)
    items = [d for d in devices["items"] if isinstance(d, dict)]
    if not items:
        return [f"Devices: none since the server started ({since})"]
    connected = sum(1 for d in items if d.get("connected"))
    rows = []
    for d in items:
        if d.get("this_server"):
            name = "this server"
        elif isinstance(d.get("name"), str):
            name = terminal_safe(d["name"])
            name = name if len(name) <= NAME_WIDTH else name[:NAME_WIDTH - 1] + "…"
        else:
            name = "(looking up)" if d.get("lookup") == "pending" else "(no name)"
        os_name = terminal_safe(d["os"]) if isinstance(d.get("os"), str) else ""
        open_now = d.get("connected") if isinstance(d.get("connected"), dict) else {}
        used = [k for k in DEVICE_KINDS if k in (d.get("used") or [])]
        what = ", ".join(k + (f" ×{open_now[k]}" if isinstance(open_now.get(k), int) and open_now[k] > 1 else "")
                         for k in used) or "-"
        when = ("connected now" if open_now else f"last {_clock(d.get('last_seen'), now)}") + \
            f" · first {_clock(d.get('first_seen'), now)}"
        rows.append((name, os_name, terminal_safe(str(d.get("address", "?"))), what, when))
    widths = [max(len(r[i]) for r in rows) for i in range(4)]
    lines = [f"Devices: {connected} connected now · {len(items)} seen since the server started ({since})"]
    lines += ["  " + "  ".join(r[i].ljust(widths[i]) for i in range(4)) + "  " + r[4] for r in rows]
    return lines


def describe_firmware(firmware: object) -> list[str]:
    """The detected firmware's name and version (device text, made terminal-safe), and the detection's state."""
    if not isinstance(firmware, dict):
        return []
    name = " ".join(terminal_safe(str(firmware[k])) for k in ("origin", "version") if firmware.get(k))
    state, detected = firmware.get("state"), firmware.get("detected_at")
    when = f"detected {_ago(time.time() - detected)} ago" if isinstance(detected, (int, float)) else None
    if state == "detecting":
        note = "detecting now" + (f"; last {when}" if when else "")
    elif state == "failed":
        note = f"detection failed: {terminal_safe(str(firmware.get('why') or '?'))}" + (f"; last {when}" if when else "")
    elif state == "done":
        note = when or "detected"
    else:
        note = (f"last {when}" if when else "not detected yet") + ", no Flipper since the server started"
    if firmware.get("learning"):
        note += ", learning its apps"
    return [f"Flipper: firmware {name} ({note})" if name else f"Flipper: firmware unknown ({note})"]


CHAT_PROBLEMS = {
    "not_configured": "not set up for chats: run {zf} agent setup",
    "agent_missing": "claude not found where it was: run {zf} agent setup again",
    "checking": "checking the claude CLI...",
    "not_logged_in": "not signed in: run claude, type /login, then {zf} restart",
    "not_subscription": "signs in with {auth}, not a subscription login: chats are refused",
    "unsafe_agent_settings": "the agent folder's .claude settings are not valid JSON, or set an API key or "
                             "apiKeyHelper while free only is on: chats are refused",
}


AGENT_PROBLEMS = {
    "not_configured": "not set up: {zf} agent setup --agent {id}",
    "agent_missing": "not found where it was: run {zf} agent setup --agent {id} again",
    "checking": "being checked...",
    "agent_version": "a version this server does not support: {zf} agent setup --agent {id} says which",
    "unsafe_agent_settings": "its configuration could make it leak, or pay while free only is on: {zf} agent setup "
                             "--agent {id} writes it again",
    "not_logged_in": "no model to use: {zf} agent setup --agent {id} says how to sign in",
}


def describe_agent(agent: dict, zf: str = "zf") -> str:
    """One line for an agent beside the first: its version, the providers it uses and its default model."""
    name = terminal_safe(str(agent.get("name") or agent.get("id")))
    label = f"{name}:".ljust(8) + " "
    problem = agent.get("problem")
    if problem:
        return label + AGENT_PROBLEMS.get(problem, problem).format(zf=zf, id=agent.get("id"))
    return label + f"{agent.get('version')} · {terminal_safe(str(agent.get('login')))} · model {agent.get('model')}"


def describe_chat(chat: dict, zf: str = "zf") -> list[str]:
    claude = chat.get("claude") or {}
    problem = chat.get("problem")
    if problem:
        text = CHAT_PROBLEMS.get(problem, problem).format(zf=zf, auth=claude.get("auth"))
        lines = [f"Claude:  {text}"]
    else:
        folder = chat.get("agent_dir") or "-"
        home = str(Path.home())
        if folder == home or folder.startswith(home + os.sep):
            folder = "~" + folder[len(home):]
        lines = [f"Claude:  {claude.get('version')} · {claude.get('auth')} login · model {chat.get('model')} · agent {folder}"]
    if chat.get("configured"):
        turn = chat.get("turn")
        state = "no turn running" if not turn else f"turn running for {_ago(time.time() - turn['started_at'])}" + (
            ", waiting for a permission answer" if turn.get("waiting") else "")
        last = chat.get("last_turn_at")
        lines.append(f"Chat:    {state} · {chat.get('conversations', 0)} saved conversations ({zf} chat --list)"
                     + (f" · last turn at {time.strftime('%H:%M', time.localtime(last))}" if last else ""))
        remote = chat.get("remote")
        if remote:
            state = "on" if remote.get("state") == "on" else "starting"
            lines.append(f"Remote:  Remote Control {state} for conversation {remote['conversation'][:8]} for "
                         f"{_ago(time.time() - remote['since'])}"
                         + (", connected" if remote.get("connected") else ", not connected yet")
                         + (" · its turn came from another device" if (turn or {}).get("via") == "remote" else ""))
    lines += [describe_agent(agent, zf) for agent in (chat.get("agents") or [])[1:] if isinstance(agent, dict)]
    if chat.get("free_only") is True:
        lines.append(f"Free:    free only is on: a subscription or free providers only ({zf} set-free-only off)")
    found = chat.get("service_env_billing_vars") or []
    if found:
        lines.append(f"Note:    the service environment has {', '.join(found)}; it is never passed to claude.")
    return lines
