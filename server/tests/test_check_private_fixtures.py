# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""server/scripts/check-private-fixtures.py, with fake values."""
import json
import runpy
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check-private-fixtures.py"
m = runpy.run_path(str(SCRIPT))
ENV = {"USER": "jdoe", "HOME": "/home/jdoe"}
CLONE = Path("/home/jdoe/work/acme/zeffin")


def values() -> list[str]:
    return m["personal_values"](ENV, "my-server.lan", CLONE, "Europe/Lisbon")


def test_the_values_leave_out_generic_folders():
    assert values() == sorted({"jdoe", "my-server.lan", "my-server", "Europe/Lisbon", "Lisbon", "acme"})


def test_a_value_streamed_in_pieces_is_found(tmp_path):
    folder = tmp_path / "stream"
    folder.mkdir()
    clean = {"type": "stream_event", "event": {"delta": {"text": "<home>/x"}}}
    (folder / "01-a.jsonl").write_text(json.dumps(clean) + "\n")
    assert m["check"](m["files"]([str(folder)]), values()) == []
    pieces = [{"event": {"delta": {"partial_json": '{"file_path": "/home/jd'}}},
              {"event": {"delta": {"partial_json": 'oe/x"}'}}}]
    (folder / "02-b.jsonl").write_text("".join(json.dumps(p) + "\n" for p in pieces))
    found = m["check"](m["files"]([str(folder)]), values())
    assert found == [values().index("jdoe") + 1]


def test_short_values_only_as_whole_words(tmp_path):
    f = tmp_path / "r.json"
    f.write_text(json.dumps({"text": "the ACME tool, used by ed"}))
    found = m["check"]([f], m["personal_values"]({"USER": "ed", "HOME": "/home/ed"}, "h", Path("/srv/x"), ""))
    assert found  # "ed" alone
    f.write_text(json.dumps({"text": "it was used"}))
    assert m["check"]([f], ["ed"]) == []
    f.write_text(json.dumps({"time": "8pm (europe/LISBON)"}))
    assert len(m["check"]([f], values())) == 2  # the zone and its city, in any case


def test_it_prints_no_value_and_fails(tmp_path, monkeypatch):
    f = tmp_path / "x.jsonl"
    f.write_text(json.dumps({"cwd": str(tmp_path)}) + "\n")
    env = {"PATH": "/usr/bin:/bin", "USER": "nobody-probe", "HOME": str(tmp_path)}
    run = subprocess.run([sys.executable, "-I", str(SCRIPT), str(f)], capture_output=True, text=True, env=env,
                         cwd=tmp_path)
    assert run.returncode == 1 and "FOUND: value" in run.stdout and str(tmp_path.name) not in run.stdout
    f.write_text(json.dumps({"cwd": "<workdir>"}) + "\n")
    run = subprocess.run([sys.executable, "-I", str(SCRIPT), str(f)], capture_output=True, text=True, env=env,
                         cwd=tmp_path)
    assert run.returncode == 0 and run.stdout.startswith("clean:")
