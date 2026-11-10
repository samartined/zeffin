# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Figures over the metrics log, shared by `zeffin report` and the app's Diagnostics screen."""
from __future__ import annotations

import json
import statistics
import time
from pathlib import Path

from .events import Ev


def read_events(path: Path, since: float, max_bytes: int = 8 * 1024 * 1024) -> list[dict]:
    """Events of a JSONL log newer than `since`, reading at most the last `max_bytes` of the file."""
    if not path.exists():
        return []
    with path.open("rb") as f:
        f.seek(0, 2)
        size = f.tell()
        f.seek(max(0, size - max_bytes))
        if size > max_bytes:
            f.readline()  # skip the partial first line
        data = f.read()
    events = []
    for line in data.splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if isinstance(e, dict) and e.get("ts", 0) >= since:
            events.append(e)
    return events


def _quantile(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(p * len(values)))] if values else None


def summarize(events: list[dict]) -> dict:
    """Figures over a list of metrics events: phone sessions, reconnects, frames, pings and rejections."""
    sessions, current = [], None
    for e in events:
        ev = e.get("ev")
        if ev == Ev.WS_OPEN:
            current = {"open": e["ts"], "ready": None, "transport": None, "first_frame": None}
        elif ev == Ev.READY and current is not None:
            current.update(ready=e["ts"], transport=e.get("transport"), open_to_ready=e.get("after_open_s"), mtu=e.get("mtu"))
        elif ev == Ev.FIRST_FRAME and current is not None:
            current["first_frame"] = e.get("after_ready_s")
        elif ev == Ev.CLOSE and current is not None:
            current.update(close=e["ts"], reason=e.get("reason"), frames=e.get("frames"),
                           duration=e.get("ready_duration_s"), phone_reason=e.get("phone_reason"))
            sessions.append(current)
            current = None
    if current is not None:
        current.update(close=None, reason="(still open)", frames=None,
                       duration=round(time.time() - current["ready"], 1) if current["ready"] else None)
        sessions.append(current)
    gaps = [b["ready"] - a["close"] for a, b in zip(sessions, sessions[1:]) if a.get("close") and b["ready"]]
    windows = [e for e in events if e.get("ev") == Ev.WINDOW]
    connected = [w for w in windows if w.get("connected")]
    pings = [e for e in events if e.get("ev") == Ev.PING]
    ok = [p["rtt_ms"] for p in pings if p.get("ok")]
    rejected = [e for e in events if e.get("ev") == Ev.WS_REJECTED]
    by_reason: dict[str, int] = {}
    for e in rejected:
        by_reason[e.get("reason", "?")] = by_reason.get(e.get("reason", "?"), 0) + 1
    streams = [e for e in events if e.get("ev") == Ev.STREAM and e.get("status") not in ("OK", "ERROR_VIRTUAL_DISPLAY_ALREADY_STARTED")]
    return {
        "events": len(events),
        "sessions": sessions,
        "reached_ready": sum(1 for s in sessions if s["ready"]),
        "reconnect_median_s": round(statistics.median(gaps), 1) if gaps else None,
        "reconnect_max_s": round(max(gaps), 1) if gaps else None,
        "minutes": len(windows),
        "minutes_connected": len(connected),
        "fps_median": round(statistics.median([w["fps"] for w in connected]), 2) if connected else None,
        "fps_max": max(w["fps"] for w in connected) if connected else None,
        "max_gap_s": max(w["max_gap_s"] for w in connected) if connected else None,
        "pings": len(pings),
        "pings_ok": len(ok),
        "rtt_median_ms": round(statistics.median(ok)) if ok else None,
        "rtt_p95_ms": round(_quantile(ok, .95)) if ok else None,
        "rtt_max_ms": round(max(ok)) if ok else None,
        "rejected": by_reason,
        "last_rejected": ({"reason": rejected[-1].get("reason"), "ts": rejected[-1]["ts"]} if rejected else None),
        "last_bad_token": next((e for e in reversed(rejected) if e.get("reason") == "bad_token"), None),
        "stream_failures": len(streams),
        "last_stream_failure": streams[-1].get("status") if streams else None,
    }
