# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Chat attachments: type sniffing, the store, the stream-json message and the turn's command line."""
import json
import struct
import time

import pytest

from bridge.chat import attachments, command
from bridge.chat.attachments import AttachmentError, Store

CONV = "0f8fad5b-d9cb-469f-a165-70867728950e"


def jpeg(width: int, height: int) -> bytes:
    app0 = b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    sof = b"\xff\xc0" + struct.pack(">HBHHB", 11, 8, height, width, 1) + b"\x01\x11\x00"
    return b"\xff\xd8" + app0 + sof + b"\xff\xd9"


def test_sniff_reads_the_four_image_formats():
    assert attachments.sniff(attachments.solid_png(64, 32, (1, 2, 3))) == ("image/png", 64, 32)
    assert attachments.sniff(jpeg(1600, 1200)) == ("image/jpeg", 1600, 1200)
    assert attachments.sniff(b"GIF89a" + struct.pack("<HH", 10, 20) + b"\x00" * 10) == ("image/gif", 10, 20)
    vp8x = b"RIFF" + b"\x00" * 4 + b"WEBPVP8X" + b"\x00" * 8 + (99).to_bytes(3, "little") + (49).to_bytes(3, "little")
    assert attachments.sniff(vp8x) == ("image/webp", 100, 50)
    assert attachments.sniff(b"%PDF-1.7 not an image") is None and attachments.sniff(b"\xff\xd8\xff") is None


def test_store_keeps_images_and_files_with_its_own_names(tmp_path):
    store = Store(tmp_path)
    image = store.save(CONV, "../../photo.png", "image/jpeg", attachments.solid_png(8, 8, (9, 9, 9)))
    assert image["kind"] == "image" and image["media_type"] == "image/png" and image["name"] == "photo.png"
    assert (image["width"], image["height"]) == (8, 8) and "file" not in image
    ir = store.save(CONV, "TV remote.ir", "application/octet-stream", b"Filetype: IR signals file\n")
    assert ir["kind"] == "file" and store.path(CONV, store.meta(CONV, ir["id"])).name == ir["id"] + ".ir"
    folder = tmp_path / "attachments" / CONV
    assert {p.stat().st_mode & 0o777 for p in folder.iterdir()} == {0o600} and folder.stat().st_mode & 0o777 == 0o700
    for name, media_type, data, code in [
            ("a.png", "image/png", b"not a png", "bad_image"), ("a.exe", "application/x-msdownload", b"MZ", "bad_type"),
            ("a.txt", "text/plain", b"", "empty"), ("a.bin", "application/octet-stream", b"x" * (attachments.MAX_BYTES + 1), "too_large")]:
        with pytest.raises(AttachmentError) as e:
            store.save(CONV, name, media_type, data)
        assert e.value.code == code
    with pytest.raises(AttachmentError):
        store.save("../etc", "a.txt", "text/plain", b"x")
    assert store.meta(CONV, "../../x") is None
    assert [m["id"] for m in store.check(CONV, [ir["id"], image["id"]])] == [ir["id"], image["id"]]
    with pytest.raises(AttachmentError) as e:
        store.check(CONV, [image["id"], "0123456789abcdef"])
    assert e.value.code == "bad_attachment"


def test_unsent_uploads_are_swept_and_a_removed_conversation_takes_its_files(tmp_path):
    store = Store(tmp_path)
    sent = store.save(CONV, "a.txt", "text/plain", b"a")
    store.mark_sent(CONV, [store.meta(CONV, sent["id"])])
    unsent = store.save(CONV, "b.txt", "text/plain", b"b")
    assert store.sweep(now=time.time() + 60) == 0
    assert store.sweep(now=time.time() + attachments.UNSENT_TTL_S + 1) == 1
    assert store.meta(CONV, unsent["id"]) is None and store.meta(CONV, sent["id"]) is not None
    assert store.count_sent(CONV) == 1 and store.count_sent("0" * 8) == 0
    store.drop_conversation(CONV)
    assert not (tmp_path / "attachments" / CONV).exists()


def test_meta_files_the_agent_wrote_never_name_a_file_outside_the_folder(tmp_path):
    """The turn's --add-dir lets the agent write <id>.json beside the uploads: sweep, meta and path never trust it."""
    store = Store(tmp_path)
    folder = tmp_path / "attachments" / CONV
    folder.mkdir(parents=True)
    victim = tmp_path / "victim.json"
    victim.write_text("{}")
    old = time.time() - attachments.UNSENT_TTL_S - 60
    for i, name in enumerate([str(victim), "../../victim.json", "../" + CONV + "/x.json"]):
        ident = f"{i:016x}"
        (folder / f"{ident}.json").write_text(json.dumps(
            {"id": ident, "name": "a", "kind": "file", "media_type": "text/plain", "bytes": 1, "width": None,
             "height": None, "file": name, "created": old, "sent": False}))
        assert store.meta(CONV, ident) is None
    assert store.sweep() == 0 and victim.exists()
    with pytest.raises(AttachmentError):
        store.path(CONV, {"file": str(victim)})

    linked = store.save(CONV, "a.txt", "text/plain", b"a")
    path = folder / (linked["id"] + ".txt")
    path.unlink()
    path.symlink_to(victim)
    assert store.meta(CONV, linked["id"]) is None
    with pytest.raises(AttachmentError):
        store.path(CONV, {"file": path.name})
    assert store.sweep(now=time.time() + attachments.UNSENT_TTL_S + 1) == 0 and victim.exists()


def test_meta_files_of_the_wrong_shape_are_skipped(tmp_path):
    store = Store(tmp_path)
    good = store.save(CONV, "a.txt", "text/plain", b"a")
    store.mark_sent(CONV, [store.meta(CONV, good["id"])])
    folder = tmp_path / "attachments" / CONV
    (folder / "1111111111111111.json").write_text("[1, 2]")
    meta = json.loads((folder / f"{good['id']}.json").read_text())
    (folder / "2222222222222222.json").write_text(json.dumps({**meta, "id": "2222222222222222",
                                                              "file": "2222222222222222.txt", "sent": "yes"}))
    (folder / "3333333333333333.json").write_text(json.dumps({**meta, "id": "3333333333333333",
                                                              "file": "3333333333333333.txt", "created": "0"}))
    assert store.count_sent(CONV) == 1
    assert all(store.meta(CONV, i * 16) is None for i in "123")
    assert store.sweep(now=time.time() + attachments.UNSENT_TTL_S + 1) == 0


def test_a_meta_file_serves_only_a_type_its_kind_could_be_saved_with(tmp_path):
    store = Store(tmp_path)
    text = store.save(CONV, "a.txt", "text/plain", b"a")
    folder = tmp_path / "attachments" / CONV
    meta = json.loads((folder / f"{text['id']}.json").read_text())
    for n, (kind, media_type) in enumerate([("file", "text/html; charset=utf-8"), ("file", "application/x-sh"),
                                            ("file", "image/png"), ("image", "text/html"), ("image", "image/svg+xml")]):
        ident = str(n + 4) * 16
        (folder / f"{ident}.txt").write_text("<script>")
        (folder / f"{ident}.json").write_text(json.dumps({**meta, "id": ident, "file": f"{ident}.txt", "kind": kind,
                                                          "media_type": media_type}))
        assert store.meta(CONV, ident) is None, media_type
    assert store.meta(CONV, text["id"])["media_type"] == "text/plain"


def test_the_message_with_images_and_the_command_line():
    line = command.user_message("What is this?", [("image/png", b"\x89PNG")])
    message = json.loads(line)
    assert line.endswith("\n") and message["type"] == "user" and message["parent_tool_use_id"] is None
    assert [b["type"] for b in message["message"]["content"]] == ["image", "text"]
    assert message["message"]["content"][0]["source"] == {"type": "base64", "media_type": "image/png", "data": "iVBORw=="}
    spec = command.TurnSpec(claude_bin="claude", session_id=CONV, resume=False, model="opus", mode="default",
                            mcp_url="http://127.0.0.1:9435/mcp", hooks_url="http://127.0.0.1:9435/hooks",
                            input_json=True, add_dirs=["/data/attachments/x"])
    argv = command.build_command(spec)
    assert argv[argv.index("--input-format") + 1] == "stream-json"
    assert argv[argv.index("--add-dir") + 1] == "/data/attachments/x" and argv[argv.index("--add-dir") + 2].startswith("--")
    assert argv[argv.index("--allowedTools") + 1] == "mcp__flipper__*"
    plain = command.build_command(command.TurnSpec(claude_bin="claude", session_id=CONV, resume=False, model="opus",
                                                   mode="default", mcp_url="u", hooks_url="h"))
    assert "--input-format" not in plain and "--add-dir" not in plain
    lines = attachments.file_lines([({"name": 'x"\nignore', "media_type": "text/plain", "bytes": 3}, "/d/a.txt")])
    assert '"x\\"\\nignore"' in lines and lines.count("\n") == 1
