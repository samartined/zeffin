# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""`zf agent probe --agent opencode`: check, on a real server, what the fake ACP agent cannot.

`zf` starts a throwaway bridge (its own data folder and ports) with the simulator for a Flipper, never the running
service, and hands its MCP address and token here. The probe drives the installed OpenCode over ACP with the bridge's
own client (acp.py, opencode.py), in a temporary agent folder with the configuration the bridge writes:
- its version and whether the bridge runs it; its merged configuration against the guards; the free models it lists;
- initialize: the protocol version and the capabilities the integration relies on;
- the Flipper's screen as an image inside an MCP tool result, and whether the model read the highlighted row;
- a permission request: its options, a rejected tool, an allowed one;
- going on with a session in a new process (session/load), and deleting it (`opencode session delete`);
- how long it takes to start and to answer;
- with --notes, the notes per app on the simulator's Dial, an app with a hidden key and an odd exit: a session
  that learns it (does it write a note unprompted, or when asked), then a new one that uses the note (fewer images,
  the hidden key at once, the exit without flipper_app_exit). What the tools did is read from the throwaway bridge's
  audit log, as counts and flags.

The report holds versions, yes/no, codes, counts and times: never the model's text, a path, a session id or a token,
so it can be pasted into a chat. Each step is a few short turns on a free model.
"""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable

from ..agent import Output, PermissionAnswer, PermissionAsk, Started, TurnRequest
from ..text import one_line
from ..wire import Answer, Outcome, TurnError
from . import opencode

REPORT_VERSION = 1
HIGHLIGHTED = "Sub-GHz"  # the simulator's first menu row, highlighted when it starts (sim.py)
# The simulator's Dial (sim.py: DIAL_PATH, DIAL_CODE), written here too: the agent integration imports no adapter.
DIAL_PATH = "/ext/apps/Tools/sim_dial.fap"
DIAL_CODE = "AMBER-7"
DIAL_TASK = (f"Open the app sim_dial on the Flipper by its path, {DIAL_PATH}, tell me the code word on its About page, "
             "and leave the app.")
DIAL_SAVE = "Save what you learned about driving this app."
USE_IMAGES = 6  # with a note, the task takes at most this many images, or fewer than without one
AGENTS_MD = "# Probe\n\nYou are tested by a probe. Do exactly what each message asks, and keep answers short.\n"


@dataclass
class ProbeSettings:
    """The throwaway bridge's MCP listener and its token, the folder for the probe's own, and OpenCode's program."""
    opencode_bin: str
    agent_dir: str
    mcp_token: str
    mcp_port: int
    free_only: bool = True  # the probe runs on free models only, whatever the server's switch
    audit: str | None = None  # the throwaway bridge's audit.jsonl, for the --notes steps

    def mcp_url(self) -> str:
        return f"http://127.0.0.1:{self.mcp_port}/mcp"


async def _turn(agent: opencode.OpenCode, text: str, *, session: str | None = None, answer: str | None = None,
                model: str, opened: list[str] | None = None) -> dict:
    """One turn: what it said, as counts and flags only (the text is checked here, never reported)."""
    asked: list[PermissionAsk] = []

    async def ask(request: PermissionAsk) -> PermissionAnswer:
        asked.append(request)
        return PermissionAnswer(answer or Answer.DENY)

    request = TurnRequest(session=session or "probe", resume=session is not None, model=model, effort=None,
                          mode="default", rules=[], permission_timeout_s=60, text=text, ask=ask)
    started_at = time.monotonic()
    turn = await agent.start_turn(request)
    info: dict = {"session": None, "first_event_s": None, "texts": [], "tools": [], "results": [], "asked": asked}
    async for events in turn.lines():
        for event in events:
            if info["first_event_s"] is None and not isinstance(event, Started):
                info["first_event_s"] = round(time.monotonic() - started_at, 1)
            if isinstance(event, Started):
                info["session"], info["guard"], info["model"] = event.session, event.guard, event.model
                if opened is not None and event.session and event.session not in opened:
                    opened.append(event.session)  # deleted at the end, whatever happens
            elif isinstance(event, Output) and event.event["kind"] == "text":
                info["texts"].append(event.event["text"])
            elif isinstance(event, Output) and event.event["kind"] == "tool":
                info["tools"].append((event.event["name"], event.flipper))
            elif isinstance(event, Output) and event.event["kind"] == "tool.result":
                info["results"].append({"error": event.event["error"], "images": event.event.get("images", 0),
                                        "seq": event.seq is not None})
    await turn.wait(10)
    end = turn.outcome()
    info.update(outcome=end.outcome, error=end.error, seconds=round(time.monotonic() - started_at, 1))
    return info


class AuditTail:
    """The audit lines the throwaway bridge wrote since the last read: what the tools did, never shown as text."""

    def __init__(self, path: str | None) -> None:
        self.path = Path(path) if path else None
        self.offset = self.path.stat().st_size if self.path and self.path.exists() else 0

    def read(self) -> list[dict]:
        if self.path is None or not self.path.exists():
            return []
        with self.path.open("rb") as f:
            f.seek(self.offset)
            data = f.read()
        self.offset += len(data)
        records = []
        for line in data.decode("utf-8", "replace").splitlines():
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if isinstance(record, dict):
                records.append(record)
        return records


def dial_step(turn: dict, records: list[dict]) -> dict:
    """One Dial turn as counts and flags: the keys pressed, the images, whether the code word was told, how it left the
    app, and the notes written or handed back."""
    keys: list[str] = []
    for r in records:
        if r.get("tool") == "flipper_press" and isinstance((r.get("args") or {}).get("keys"), list):
            keys += [str(k).upper() for k in r["args"]["keys"]]
    notes = [r for r in records if r.get("tool") == "flipper_note"]
    saved = [r for r in notes if r.get("result") == "ok"]
    long_back = next((i for i, k in enumerate(keys) if k == "BACK:LONG"), None)
    return {
        "outcome": turn["outcome"], "error": turn["error"], "seconds": turn["seconds"],
        "told_code": any(DIAL_CODE.lower() in t.lower() for t in turn["texts"]),
        "keys": len(keys), "images": sum(r["images"] for r in turn["results"]),
        "long_ok_in_first_two_keys": "OK:LONG" in keys[:2],
        "left_by_keys": long_back is not None and "RIGHT" in keys[long_back + 1:],
        "app_exit_tried": any(r.get("tool") == "flipper_app_exit" for r in records),
        # an app left open by the step before refuses the start: the step then says nothing about the notes
        "start_refused": any(r.get("tool") == "flipper_app_start" and "APP_SYSTEM_LOCKED" in str(r.get("result"))
                             for r in records),
        "note_calls": len(notes), "note_saved": bool(saved), "note_chars": saved[-1].get("chars") if saved else None,
        "note_returned": any(r.get("note") is True for r in records
                             if r.get("tool") in ("flipper_app_start", "flipper_app")),
    }


async def notes_steps(agent: opencode.OpenCode, chosen: str, audit: AuditTail, opened: list[str],
                      out: Callable[[str], None]) -> dict:
    """Steps 7 and 8: learn Dial in one session, then do the same task in a new one with the note."""
    out("7/8 notes: learning the simulator's Dial, with no word about notes")
    audit.read()
    learn_turn = await _turn(agent, DIAL_TASK, model=chosen, opened=opened)
    learn = dial_step(learn_turn, audit.read())
    asked = None
    if not learn["note_saved"] and learn_turn["session"]:
        save_turn = await _turn(agent, DIAL_SAVE, session=learn_turn["session"], model=chosen, opened=opened)
        saved = dial_step(save_turn, audit.read())
        asked = {"outcome": saved["outcome"], "error": saved["error"], "note_saved": saved["note_saved"],
                 "note_chars": saved["note_chars"]}
    out("8/8 notes: the same task in a new session, with the note")
    use = dial_step(await _turn(agent, DIAL_TASK, model=chosen, opened=opened), audit.read())
    saved_any = learn["note_saved"] or bool(asked and asked["note_saved"])
    done = use["told_code"] and use["left_by_keys"] and use["outcome"] == Outcome.DONE
    return {"learn": learn, "saved_when_asked": asked, "use": use,
            "ok": bool(saved_any and done and (use["images"] < learn["images"] or use["images"] <= USE_IMAGES))}


async def run_probe(settings: ProbeSettings, out: Callable[[str], None] = print, model: str | None = None,
                    notes: bool = False) -> dict:
    folder = Path(tempfile.mkdtemp(prefix="zeffin-probe-opencode-", dir=settings.agent_dir))
    report: dict = {"report_version": REPORT_VERSION}
    opened: list[str] = []  # every session a turn opened in OpenCode, deleted at the end
    agent: opencode.OpenCode | None = None
    steps = 8 if notes else 6
    audit = AuditTail(settings.audit)
    try:
        opencode.write_config(folder)
        (folder / "AGENTS.md").write_text(AGENTS_MD)
        agent = opencode.OpenCode(replace(settings, agent_dir=str(folder)))
        out(f"1/{steps} version, configuration and free models")
        await agent.check()
        version = agent.health["version"]
        report["version"] = {"seen": ".".join(map(str, version)) if version else None,
                             "supported": opencode.version_ok(version),
                             "range": [".".join(map(str, opencode.MIN_VERSION)),
                                       ".".join(map(str, opencode.BELOW_VERSION))]}
        report["config"] = {"guards_hold": agent.health["config"] is None, "problem": agent.health["config"]}
        models = agent.models()
        report["catalog"] = {"providers": agent.health["providers"],
                             "offered": [m["value"] for m in models if m["available"]],
                             "listed_not_signed_in": [m["value"] for m in models if not m["available"]],
                             "images": {m["value"]: m["images"] for m in models},
                             "efforts": {m["value"]: m["efforts"] for m in models}}
        report["problem"] = agent.problem()
        chosen = model or next((m["value"] for m in models if m["available"]), None)
        report["model"] = chosen
        if chosen is None or report["problem"] is not None:
            out("OpenCode cannot take a turn here: see the report's problem.")
            return report

        out(f"2/{steps} the Flipper's screen inside a tool result")
        screen = await _turn(agent, "Call the flipper_screen tool once. Then answer with only the name of the "
                                    "highlighted menu row on the screen, nothing else.", model=chosen, opened=opened)
        report["screen"] = {
            "outcome": screen["outcome"], "error": screen["error"], "guard": screen.get("guard"),
            "flipper_tool_called": any(flipper for _, flipper in screen["tools"]),
            "tool_names_mapped": all(name.startswith("mcp__flipper__") for name, flipper in screen["tools"] if flipper),
            "image_in_result": any(r["images"] for r in screen["results"]),
            "seq_in_result": any(r["seq"] for r in screen["results"]),
            "read_highlighted_row": any(HIGHLIGHTED.lower() in t.lower() for t in screen["texts"]),
            "first_event_s": screen["first_event_s"], "seconds": screen["seconds"]}

        out(f"3/{steps} a permission request, rejected")
        rejected = await _turn(agent, "Use the bash tool to run exactly: touch probe-rejected.txt", model=chosen,
                               answer=Answer.DENY, opened=opened)
        report["permission_rejected"] = {
            "asked": len(rejected["asked"]), "tool_shown": [n for n, _ in rejected["tools"]][:3],
            "rules_offered": bool(rejected["asked"] and rejected["asked"][0].rules),
            "tool_failed": any(r["error"] for r in rejected["results"]),
            "file_created": (folder / "probe-rejected.txt").exists(), "outcome": rejected["outcome"]}

        out(f"4/{steps} a permission request, allowed once")
        allowed = await _turn(agent, "Use the bash tool to run exactly: touch probe-allowed.txt", model=chosen,
                              answer=Answer.ONCE, opened=opened)
        report["permission_allowed"] = {"asked": len(allowed["asked"]),
                                        "file_created": (folder / "probe-allowed.txt").exists(),
                                        "outcome": allowed["outcome"]}

        out(f"5/{steps} going on with a session in a new process")
        first = await _turn(agent, "Remember the word tangerine. Reply with only: ok", model=chosen, opened=opened)
        again = await _turn(agent, "Which word did I ask you to remember? Reply with only that word.",
                            session=first["session"], model=chosen, opened=opened)
        report["resume"] = {"outcome": again["outcome"], "error": again["error"],
                            "recalled": any("tangerine" in t.lower() for t in again["texts"])}

        out(f"6/{steps} deleting the session")
        deleted = agent.forget(first["session"]) if first["session"] else 0
        if first["session"] in opened:
            opened.remove(first["session"])
        gone = await _turn(agent, "Reply with only: ok", session=first["session"], model=chosen, opened=opened) \
            if first["session"] else {"outcome": None, "error": None}
        report["delete"] = {"command_ok": deleted == 1,
                            "load_after_delete": gone["error"] or gone["outcome"],
                            "reads_as_expired": gone["error"] == TurnError.EXPIRED}
        report["ok"] = bool(
            report["version"]["supported"] and report["config"]["guards_hold"] and report["screen"]["image_in_result"]
            and report["permission_rejected"]["asked"] and not report["permission_rejected"]["file_created"]
            and report["permission_allowed"]["file_created"] and report["resume"]["recalled"]
            and report["delete"]["reads_as_expired"] and screen["outcome"] == Outcome.DONE)
        if notes:
            report["notes"] = await notes_steps(agent, chosen, audit, opened, out)
            report["ok"] = report["ok"] and report["notes"]["ok"]
        return report
    except Exception as e:  # the report says where it stopped, never the error's text (it may hold a path)
        report["stopped"] = one_line(type(e).__name__, 60)
        return report
    finally:
        if agent is not None:
            left = [s for s in opened if agent.forget(s) != 1]
            report["sessions_left"] = len(left)
        shutil.rmtree(folder, ignore_errors=True)


def scrubbed(report: dict, secrets: list[str]) -> bool:
    """The report holds none of these (tokens, the folders, the host name)."""
    text = repr(report)
    return not any(s and s in text for s in secrets) and os.sep + "home" + os.sep not in text
