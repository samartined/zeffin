# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The JSONL logs keep their newest bytes, and a log write never fails its caller."""
import errno
import json
import logging
import os
import threading
import time

import pytest

from bridge import gate
from bridge.appws import diag_figures
from bridge.gate import LOG_KEEP_BYTES, JsonlLog, write_atomic
from bridge.hub import Hub
from bridge.stats import read_events

KEEP = 2000
THRESHOLD = KEEP + KEEP // 4


def lines_of(path):
    return [json.loads(line) for line in path.read_bytes().split(b"\n") if line]


def line_bytes(n):
    """About the length of a line written with {"n": n, "pad": "x" * 80}, a few bytes over."""
    return len(json.dumps({"ts": round(time.time(), 3), "n": n, "pad": "x" * 80}) + "\n") + 4


def test_the_hubs_logs_keep_the_default():
    assert LOG_KEEP_BYTES == 8 * 1024 * 1024
    hub = Hub(None)
    assert hub.audit.keep_bytes == hub.metrics.keep_bytes == LOG_KEEP_BYTES


def test_a_log_past_the_threshold_keeps_its_newest_lines(tmp_path):
    path = tmp_path / "metrics.jsonl"
    log = JsonlLog(path, keep_bytes=KEEP)
    n, size = 0, 0
    while True:
        log.write({"n": n, "pad": "x" * 80})
        before, size = size, path.stat().st_size
        if size < before:
            break
        assert size <= THRESHOLD + line_bytes(n)
        n += 1
    assert size <= KEEP
    kept = lines_of(path)  # every line parses: the file starts at a line's start
    assert path.read_bytes().endswith(b"\n")
    assert kept[-1]["n"] == n
    assert [e["n"] for e in kept] == list(range(kept[0]["n"], n + 1))
    assert os.stat(path).st_mode & 0o777 == 0o600


def test_below_the_threshold_nothing_is_cut(tmp_path):
    path = tmp_path / "audit.jsonl"
    log = JsonlLog(path, keep_bytes=KEEP)
    n = 0
    while not path.exists() or path.stat().st_size + line_bytes(n) <= THRESHOLD:
        log.write({"n": n, "pad": "x" * 80})
        n += 1
    assert path.stat().st_size > KEEP  # past keep_bytes, still under the threshold
    assert [e["n"] for e in lines_of(path)] == list(range(n))


def test_many_writes_across_several_trims(tmp_path):
    path = tmp_path / "metrics.jsonl"
    log = JsonlLog(path, keep_bytes=KEEP)
    trims, size = 0, 0
    for n in range(500):
        log.write({"n": n, "pad": "x" * (n % 120)})
        before, size = size, path.stat().st_size
        trims += size < before
        assert size <= THRESHOLD + line_bytes(n) + 120
    assert trims >= 5
    kept = [e["n"] for e in lines_of(path)]
    assert kept == list(range(kept[0], 500))


def test_threads_writing_at_once_lose_no_line_to_a_trim(tmp_path):
    path = tmp_path / "metrics.jsonl"
    log = JsonlLog(path, keep_bytes=KEEP * 4)

    def writer(t):
        for i in range(300):
            log.write({"t": t, "i": i, "pad": "x" * 40})

    threads = [threading.Thread(target=writer, args=(t,)) for t in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    kept = lines_of(path)
    assert path.stat().st_size <= KEEP * 5 + 200
    for t in range(4):  # what is kept of each thread is the end of what it wrote, with no gap
        mine = [e["i"] for e in kept if e["t"] == t]
        assert mine == list(range(300 - len(mine), 300))


def test_read_events_and_diagnostics_over_a_trimmed_log(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    hub.metrics = JsonlLog(tmp_path / "metrics.jsonl", keep=200, keep_bytes=KEEP * 4)
    hub.audit = JsonlLog(tmp_path / "audit.jsonl", keep=200, keep_bytes=KEEP * 4)
    old = time.time() - 3 * 24 * 3600
    with (tmp_path / "metrics.jsonl").open("a") as f:  # three days ago: a session and many idle minutes
        f.write(json.dumps({"ts": old, "ev": "ws_open"}) + "\n")
        for i in range(200):
            f.write(json.dumps({"ts": old + i, "ev": "window", "connected": False, "frames": 0}) + "\n")
    for _ in range(3):
        hub.metrics.write({"ev": "ws_open"})
        hub.metrics.write({"ev": "ready", "transport": "BLE", "after_open_s": 0.5})
        hub.metrics.write({"ev": "close", "reason": "phone", "frames": 10, "ready_duration_s": 5})
    hub.metrics.write({"ev": "ws_rejected", "reason": "bad_token"})
    hub.audit.write({"tool": "flipper_press", "args": {"keys": ["OK"]}, "result": "ok"})
    path = tmp_path / "metrics.jsonl"
    kept = lines_of(path)
    assert path.stat().st_size <= KEEP * 5  # trimmed: the oldest lines, the old session among them, are gone
    assert kept[0]["ev"] == "window" and kept[0]["ts"] > old
    since = time.time() - 24 * 3600
    assert [e["ev"] for e in read_events(path, since)] == ["ws_open", "ready", "close"] * 3 + ["ws_rejected"]
    diag = diag_figures(hub, None)
    assert diag["connections"] == 3 and diag["reached_ready"] == 3
    assert diag["rejected"] == {"bad_token": 1} and diag["agent_actions"] == 1


@pytest.fixture
def full_disk(monkeypatch):
    """os.write fails as on a full disk while `on` holds True."""
    state = {"on": True}
    real = os.write

    def write(fd, data):
        if state["on"]:
            raise OSError(errno.ENOSPC, "No space left on device")
        return real(fd, data)

    monkeypatch.setattr(gate.os, "write", write)
    return state


async def test_a_failing_write_raises_nothing_and_warns_once_per_streak(tmp_path, full_disk, caplog):
    path = tmp_path / "metrics.jsonl"
    log = JsonlLog(path, keep=10)
    q = log.listen()
    caplog.set_level(logging.INFO, logger="bridge.gate")
    for n in range(3):
        record = log.write({"n": n})
        assert record["n"] == n
    assert [e["n"] for e in log.recent] == [0, 1, 2]
    assert [q.get_nowait()["n"] for _ in range(3)] == [0, 1, 2]
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1 and "metrics.jsonl" in warnings[0].getMessage()

    full_disk["on"] = False
    log.write({"n": 3})
    log.write({"n": 4})
    assert [e["n"] for e in lines_of(path)] == [3, 4]
    back = [r for r in caplog.records if "again" in r.getMessage()]
    assert len(back) == 1

    full_disk["on"] = True
    log.write({"n": 5})
    assert len([r for r in caplog.records if r.levelno == logging.WARNING]) == 2  # a new streak warns again


def test_a_line_cut_short_by_a_full_disk_does_not_swallow_the_next(tmp_path, monkeypatch):
    path = tmp_path / "audit.jsonl"
    log = JsonlLog(path)
    log.write({"n": 0})
    real = os.write
    monkeypatch.setattr(gate.os, "write", lambda fd, data: real(fd, data[:5]))  # the disk fills mid-line
    log.write({"n": 1})
    monkeypatch.setattr(gate.os, "write", real)
    log.write({"n": 2})
    lines = path.read_bytes().split(b"\n")
    assert json.loads(lines[0])["n"] == 0 and json.loads(lines[-2])["n"] == 2 and lines[-1] == b""


def test_a_failing_trim_leaves_the_file_with_the_line_and_no_temporary(tmp_path, monkeypatch, caplog):
    path = tmp_path / "metrics.jsonl"
    log = JsonlLog(path, keep_bytes=KEEP)
    n = 0
    while not path.exists() or path.stat().st_size + line_bytes(n) <= THRESHOLD:
        log.write({"n": n, "pad": "x" * 80})
        n += 1
    before = path.read_bytes()

    def replace(src, dst):
        raise OSError(errno.ENOSPC, "No space left on device")

    monkeypatch.setattr(gate.os, "replace", replace)
    log.write({"n": n, "pad": "x" * 80})  # past the threshold: the trim fails
    after = path.read_bytes()
    assert after.startswith(before) and lines_of(path)[-1]["n"] == n
    assert sorted(p.name for p in tmp_path.iterdir()) == ["metrics.jsonl"]
    assert any(r.levelno == logging.WARNING for r in caplog.records)


def test_write_atomic_removes_its_temporary_when_it_fails(tmp_path, monkeypatch):
    target = tmp_path / "gate.json"
    target.write_text("old")

    def replace(src, dst):
        raise OSError(errno.ENOSPC, "No space left on device")

    monkeypatch.setattr(gate.os, "replace", replace)
    with pytest.raises(OSError):
        write_atomic(target, b"new")
    assert target.read_text() == "old"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["gate.json"]
