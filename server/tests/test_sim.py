# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The simulator's Dial: a .fap with a hidden long-press key and an odd exit, for the notes probe."""
from bridge import pb
from bridge.phone import build
from bridge.sim import DIAL_PATH, MENU, SimFlipper


def request(name: str, payload: dict | None = None):
    r = build(name, payload)
    r.command_id = 5
    return r


def press(sim: SimFlipper, key: str, long: bool = False) -> list:
    """PRESS, SHORT|LONG, RELEASE as the bridge sends them; the replies to all three."""
    out = []
    for typ in ("PRESS", "LONG" if long else "SHORT", "RELEASE"):
        out += sim.handle(request("gui_send_input_event_request", {"key": key, "type": typ}))
    return out


def frames(replies) -> int:
    return sum(1 for r in replies if r.WhichOneof("content") == "gui_screen_frame")


def started() -> SimFlipper:
    sim = SimFlipper()
    sim.handle(request("gui_start_screen_stream_request"))
    replies = sim.handle(request("app_start_request", {"name": DIAL_PATH, "args": ""}))
    assert replies[0].command_status == pb.CommandStatus.Value("OK") and sim.dial == "dial"
    return sim


def test_dial_is_on_the_sd_card_as_a_fap():
    sim = SimFlipper()
    listed = sim.handle(request("storage_list_request", {"path": "/ext/apps/Tools"}))[0].storage_list_response
    assert [f.name for f in listed.file] == ["sim_dial.fap"]
    names = [f.name for f in sim.handle(request("storage_list_request", {"path": "/ext/apps"}))[0]
             .storage_list_response.file]
    assert names == ["Tools"]


def test_dial_turns_and_its_short_ok_is_a_decoy():
    sim = started()
    press(sim, "UP")
    press(sim, "UP")
    press(sim, "DOWN")
    assert sim.dial_value == 1
    press(sim, "OK")
    assert sim.dial == "saved"
    press(sim, "BACK")  # a short BACK does nothing on the dial
    assert sim.app == DIAL_PATH and sim.dial == "saved"


def test_a_long_ok_opens_about_and_back_returns():
    sim = started()
    assert frames(press(sim, "OK", long=True)) == 1 and sim.dial == "about"
    press(sim, "BACK")
    assert sim.dial == "dial"


def test_the_exit_is_a_long_back_then_right_and_app_exit_does_not_work():
    sim = started()
    exit_reply = sim.handle(request("app_exit_request"))[0]
    assert exit_reply.command_status == pb.CommandStatus.Value("ERROR_APP_NOT_RUNNING") and sim.app == DIAL_PATH
    press(sim, "BACK", long=True)
    assert sim.dial == "exit"
    press(sim, "LEFT")
    assert sim.dial == "dial"
    press(sim, "BACK", long=True)
    press(sim, "RIGHT")
    assert sim.app is None and sim.dial is None
    assert sim.handle(request("app_start_request", {"name": DIAL_PATH, "args": ""}))[0].command_status == 0


def test_the_menu_and_the_generic_apps_still_ignore_long_presses():
    sim = SimFlipper()
    sim.handle(request("gui_start_screen_stream_request"))
    assert frames(press(sim, "DOWN", long=True)) == 0 and sim.selected == 0
    press(sim, "OK")
    assert sim.app == MENU[0] and sim.dial is None
    assert frames(press(sim, "BACK", long=True)) == 0 and sim.app == MENU[0]
    press(sim, "BACK")
    assert sim.app is None
    sim.handle(request("app_start_request", {"name": "Sub-GHz", "args": ""}))
    assert sim.handle(request("app_exit_request"))[0].command_status == 0 and sim.app is None


def test_dial_step_reads_counts_and_flags_from_the_audit():
    from bridge.chat.probe_opencode import DIAL_CODE, DIAL_PATH as PROBE_PATH, dial_step
    from bridge.sim import DIAL_CODE as SIM_CODE

    assert (PROBE_PATH, DIAL_CODE) == (DIAL_PATH, SIM_CODE)  # the probe's copy matches the simulator
    turn = {"outcome": "done", "error": None, "seconds": 9.0, "texts": [f"The code word is {SIM_CODE}."],
            "results": [{"images": 1}, {"images": 1}, {"images": 0}]}
    records = [{"tool": "flipper_app_start", "result": "rpc_error: ERROR_APP_SYSTEM_LOCKED"},
               {"tool": "flipper_app_start", "result": "ok", "note": True},
               {"tool": "flipper_press", "result": "ok", "args": {"keys": ["ok:long"]}},
               {"tool": "flipper_press", "result": "ok", "args": {"keys": ["BACK", "BACK:long", "RIGHT"]}},
               {"tool": "flipper_note", "result": "ok", "args": {"appid": "sim_dial", "chars": 90}, "chars": 90}]
    step = dial_step(turn, records)
    assert step == {"outcome": "done", "error": None, "seconds": 9.0, "told_code": True, "keys": 4, "images": 2,
                    "long_ok_in_first_two_keys": True, "left_by_keys": True, "app_exit_tried": False,
                    "start_refused": True,
                    "note_calls": 1, "note_saved": True, "note_chars": 90, "note_returned": True}
