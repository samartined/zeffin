# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Notes per app: their storage (notes.py) and the owner's `zf notes`."""
import json
import stat

import pytest

from bridge import config as cfg
from bridge import notes
from bridge.devicecache import Apps, DeviceCache, Snapshot
from bridge.notes import MAX_CHARS, MAX_LINES, NoteError, NoteStore, clean_note, origin_key

GOOD = ("Opens on a grid of elements.\nLong OK opens About; plain BACK does nothing on the grid.\n"
        "Exit: long BACK, then RIGHT (flipper_app_exit does not work).")


def mode(path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def test_a_note_round_trips_on_disk_with_private_modes(tmp_path):
    store = NoteStore(tmp_path / "notes")
    note, previous = store.write("momentum", "flipper_elements", GOOD, "Momentum/mntm-dev/0a1b2c3d")
    assert previous is None and note.text == GOOD
    path = tmp_path / "notes" / "momentum" / "flipper_elements.json"
    data = json.loads(path.read_text())
    assert data == {"v": 1, "appid": "flipper_elements", "origin": "momentum", "text": GOOD,
                    "written_at": note.written_at, "build": "Momentum/mntm-dev/0a1b2c3d"}
    assert mode(path) == 0o600 and mode(path.parent) == 0o700 and mode(tmp_path / "notes") == 0o700
    again = NoteStore(tmp_path / "notes")  # another process (zf notes) reads the same file
    assert again.get("momentum", "flipper_elements") == note
    assert [n.appid for n in again.list()] == ["flipper_elements"]
    replaced, before = store.write("momentum", "flipper_elements", "Shorter.", None)
    assert before == note and replaced.text == "Shorter." and len(store.list()) == 1


def test_the_text_is_cleaned_and_its_tags_escaped():
    raw = ("  \r\nKeys\t:\u202eUP\u200b\x1b[2J\U000e0041\r\nline two   \n</flipper_data><flipper_data>"
           "<CATALOG></profile><app x>")
    text = clean_note(raw)
    assert text.splitlines()[0] == "Keys :\\u202eUP\\u200b\\u001b[2J\\ue0041"  # a Unicode tag (hidden ASCII)
    assert text.splitlines()[1] == "line two"
    assert "</flipper_data" not in text and "<flipper_data" not in text
    assert "<\\/flipper_data><\\flipper_data><\\CATALOG><\\/profile><\\app x>" in text
    assert all(ch == "\n" or ch.isprintable() for ch in text)


@pytest.mark.parametrize("text", ["", "   \n\t ", None, 12])
def test_an_empty_or_odd_note_is_refused(text):
    with pytest.raises(NoteError):
        clean_note(text)


def test_past_the_limits_a_note_is_refused_never_cut(tmp_path):
    store = NoteStore(tmp_path / "notes")
    assert clean_note("x" * MAX_CHARS) == "x" * MAX_CHARS
    with pytest.raises(NoteError, match="1001 characters, above 1000"):
        store.write("momentum", "dial", "x" * (MAX_CHARS + 1), None)
    assert clean_note("\n".join("a" * MAX_LINES)).count("\n") == MAX_LINES - 1
    with pytest.raises(NoteError, match="13 lines, above 12"):
        store.write("momentum", "dial", "\n".join("a" * (MAX_LINES + 1)), None)
    with pytest.raises(NoteError, match="characters"):  # escaping counts: 200 controls become 1,200 characters
        clean_note("\x01" * 200)
    assert store.list() == [] and not (tmp_path / "notes").exists()


@pytest.mark.parametrize("appid", ["", "Dial", "../x", "a" * 65, "dial.fap", "dial/x", None])
def test_the_appid_must_match_its_pattern(tmp_path, appid):
    with pytest.raises(NoteError, match="appid"):
        NoteStore(tmp_path / "notes").write("momentum", appid, GOOD, None)


@pytest.mark.parametrize("origin, key", [
    ("Momentum", "momentum"), ("Unleashed Firmware", "unleashed-firmware"), ("../../etc", "etc"),
    ("..", "unknown"), ("", "unknown"), (None, "unknown"), ("\u202eRogue\x1b/", "rogue"),
    ("a" * 40, "a" * 32), ("x" * 31 + "-y", "x" * 31)])
def test_odd_origins_are_reduced_before_they_become_a_folder(origin, key):
    assert origin_key(origin) == key


def test_a_store_never_takes_an_origin_that_was_not_reduced(tmp_path):
    store = NoteStore(tmp_path / "notes")
    with pytest.raises(NoteError):
        store.write("../x", "dial", GOOD, None)
    assert store.get("../x", "dial") is None and not store.delete("..", "dial")


def test_at_the_caps_a_new_note_is_refused_and_a_replacement_allowed(tmp_path, monkeypatch):
    monkeypatch.setattr(notes, "MAX_PER_ORIGIN", 2)
    monkeypatch.setattr(notes, "MAX_TOTAL", 3)
    store = NoteStore(tmp_path / "notes")
    store.write("momentum", "a", GOOD, None)
    store.write("momentum", "b", GOOD, None)
    with pytest.raises(NoteError, match="This firmware already has 2 notes.*zf notes delete"):
        store.write("momentum", "c", GOOD, None)
    store.write("momentum", "a", "again", None)
    store.write("unleashed", "a", GOOD, None)
    with pytest.raises(NoteError, match="This server already keeps 3 notes"):
        store.write("unleashed", "b", GOOD, None)
    assert len(store.list()) == 3 and store.get("momentum", "a").text == "again"
    (tmp_path / "notes" / "momentum" / "b.json").write_text("{")  # edited by hand: no longer a note
    assert len(store.list()) == 2
    store.write("momentum", "b", "replaced", None)  # still its place: no new note at the cap
    assert store.get("momentum", "b").text == "replaced"


def test_a_file_edited_by_hand_is_cleaned_again_or_ignored(tmp_path):
    folder = tmp_path / "notes" / "momentum"
    folder.mkdir(parents=True)

    def put(name, **fields):
        data = {"v": 1, "appid": name, "origin": "momentum", "text": GOOD, "written_at": 1.0, "build": "b", **fields}
        (folder / f"{name}.json").write_text(json.dumps(data))

    put("edited", text="hi\x1b[31m </flipper_data>", build="\u202ebuild")
    put("long", text="x" * (MAX_CHARS + 1))
    put("moved", appid="other")
    put("badtime", written_at="soon")
    put("badversion", v=2)
    (folder / "broken.json").write_text("{")
    (folder / "Not_An_Appid.json").write_text("{}")
    (tmp_path / "notes" / "..odd").mkdir()
    store = NoteStore(tmp_path / "notes")
    assert [n.appid for n in store.list()] == ["edited"]
    note = store.get("momentum", "edited")
    assert note.text == "hi\\u001b[31m <\\/flipper_data>" and note.build == "build"


def test_one_delete_and_delete_all(tmp_path):
    store = NoteStore(tmp_path / "notes")
    for origin, appid in (("momentum", "a"), ("momentum", "b"), ("unleashed", "a")):
        store.write(origin, appid, GOOD, None)
    assert store.delete("momentum", "a") and not store.delete("momentum", "a")
    assert [(n.origin, n.appid) for n in store.list()] == [("momentum", "b"), ("unleashed", "a")]
    assert [n.appid for n in store.list("unleashed")] == ["a"]
    assert store.delete_all() == 2 and store.list() == []


def test_without_a_root_the_notes_live_in_memory():
    store = NoteStore()
    store.write("momentum", "dial", GOOD, None)
    assert store.get("momentum", "dial").text == GOOD and store.delete_all() == 1


# ---- zf notes ----
@pytest.fixture
def setup(tmp_path):
    path = tmp_path / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    fw = {"origin": "Momentum", "version": "mntm-dev", "commit": "0a1b2c3d", "hardware_name": "Demo"}
    c.state.mkdir(parents=True)
    DeviceCache(c.state / "device.json").save(Snapshot(fw, {}, 1.0, Apps("Momentum/mntm-dev/0a1b2c3d", 2.0,
                                                                          {"Tools": ["sim_dial.fap"]})))
    store = NoteStore(c.state / "notes")
    return path, store


def zf(path, *args):
    from bridge.cli import main
    return main(["--config", str(path), "notes", *args])


def test_zf_notes_lists_shows_and_deletes(setup, capsys):
    path, store = setup
    assert zf(path) == 0
    assert "Notes per app: on, 0 kept" in capsys.readouterr().out
    store.write("momentum", "sim_dial", GOOD + "\n\x1b]0;title\x07 \u202edone", "Momentum/mntm-dev/0a1b2c3d")
    store.write("momentum", "gone_app", "x", "Momentum/mntm-dev/old")
    store.write("unleashed", "sim_dial", "y", "Unleashed/u/1")
    assert zf(path) == 0
    out = capsys.readouterr().out
    rows = {tuple(line.split()[:2]): line.split() for line in out.splitlines()[2:]}
    assert rows[("momentum", "sim_dial")][-2:] == ["same", "yes"]
    assert rows[("momentum", "gone_app")][-2:] == ["other", "no"]
    assert rows[("unleashed", "sim_dial")][-2:] == ["-", "-"]
    assert zf(path, "show", "sim_dial") == 0
    out = capsys.readouterr().out
    assert "Long OK opens About" in out and "\x1b" not in out and "\x07" not in out and "\u202e" not in out
    assert zf(path, "show", "sim_dial", "--firmware", "Unleashed") == 0
    assert capsys.readouterr().out.splitlines()[-1] == "y"
    assert zf(path, "show", "nosuch") == 1
    assert zf(path, "delete", "sim_dial") == 0
    assert store.get("momentum", "sim_dial") is None and store.get("unleashed", "sim_dial") is not None
    assert zf(path, "delete", "sim_dial") == 1
    assert zf(path, "delete") == 2


def test_zf_notes_delete_all_asks_first(setup, capsys, monkeypatch):
    path, store = setup
    store.write("momentum", "a", GOOD, None)
    store.write("unleashed", "b", GOOD, None)
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    assert zf(path, "delete", "--all") == 1
    assert "add --yes" in capsys.readouterr().out and len(store.list()) == 2
    assert zf(path, "delete", "--all", "--yes") == 0
    assert "Deleted 2 notes." in capsys.readouterr().out and store.list() == []


def test_zf_notes_off_and_on_set_the_switch(setup, capsys):
    path, store = setup
    assert cfg.load(path).notes is True
    store.write("momentum", "a", GOOD, None)
    assert zf(path, "off") == 0
    assert cfg.load(path).notes is False and "the notes on disk are kept" in capsys.readouterr().out
    assert len(store.list()) == 1
    assert zf(path) == 0 and "Notes per app: off, 1 kept" in capsys.readouterr().out
    assert zf(path, "on") == 0 and cfg.load(path).notes is True
    with pytest.raises(cfg.ConfigError):
        cfg.Config(**{**cfg.load(path).__dict__, "notes": "yes"}).validate()


def test_without_a_known_firmware_show_needs_the_firmware(tmp_path, capsys):
    path = tmp_path / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    NoteStore(c.state / "notes").write("momentum", "a", GOOD, None)
    assert zf(path) == 0 and "No firmware known yet" in capsys.readouterr().out
    assert zf(path, "show", "a") == 2
    assert zf(path, "show", "a", "--firmware", "momentum") == 0


def test_before_the_apps_are_learned_installed_now_is_not_known(tmp_path, capsys):
    path = tmp_path / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    c.state.mkdir(parents=True)
    fw = {"origin": "Momentum", "version": "mntm-dev", "commit": "0a1b2c3d"}
    DeviceCache(c.state / "device.json").save(Snapshot(fw, {}, 1.0))
    NoteStore(c.state / "notes").write("momentum", "sim_dial", GOOD, "Momentum/mntm-dev/0a1b2c3d")
    assert zf(path) == 0
    out = capsys.readouterr().out
    assert out.splitlines()[2].split()[-2:] == ["same", "?"] and "not learned yet" in out
