# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Files attached to chat messages: stored next to the conversations, read by the turn that sends them.

Images (PNG, JPEG, GIF, WebP: the formats Claude reads, platform.claude.com/docs/en/build-with-claude/vision)
go to Claude as image blocks. Any other file is named by its path, in a folder the turn adds with `--add-dir`.
Everything here came from the phone: the server picks the file names, checks the type by its bytes and keeps
the original name only to show it.
"""
from __future__ import annotations

import contextlib
import json
import os
import re
import secrets
import shutil
import struct
import time
from pathlib import Path

from ..png import solid_png as solid_png  # kept here for the tests, which make images with it
from ..wire import Err, ProtocolError

MAX_BYTES = 10 * 1024 * 1024        # Claude's own limit for one image
MAX_PER_MESSAGE = 10
MAX_MESSAGE_BYTES = 7 * 1024 * 1024  # images of one message: under the 10 MB stdin cap once in base64
UNSENT_TTL_S = 3600                  # an upload never sent is swept after an hour
IMAGE_TYPES = ("image/png", "image/jpeg", "image/gif", "image/webp")
FILE_TYPES = ("application/pdf", "application/json", "application/octet-stream")
_ID = re.compile(r"[0-9a-f]{16}")
_CONVERSATION = re.compile(r"[0-9a-f-]{36}")
_EXT = re.compile(r"\.[A-Za-z0-9]{1,8}$")
_FILE = re.compile(r"[0-9a-f]{16}\.[A-Za-z0-9]{1,8}")  # what save() names a file: <id><ext>


class AttachmentError(Exception):
    def __init__(self, code: str, status: int, message: str):
        super().__init__(message)
        self.code, self.status = code, status


def sniff(data: bytes) -> tuple[str, int, int] | None:
    """(media type, width, height) of a PNG, JPEG, GIF or WebP image, from its bytes; None otherwise."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        w, h = struct.unpack(">II", data[16:24])
        return "image/png", w, h
    if data[:6] in (b"GIF87a", b"GIF89a") and len(data) >= 10:
        w, h = struct.unpack("<HH", data[6:10])
        return "image/gif", w, h
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP" and len(data) >= 30:
        chunk = data[12:16]
        if chunk == b"VP8 ":
            w, h = struct.unpack("<HH", data[26:30])
            return "image/webp", w & 0x3FFF, h & 0x3FFF
        if chunk == b"VP8L":
            bits = int.from_bytes(data[21:25], "little")
            return "image/webp", (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
        if chunk == b"VP8X":
            return "image/webp", int.from_bytes(data[24:27], "little") + 1, int.from_bytes(data[27:30], "little") + 1
        return None
    if data[:3] == b"\xff\xd8\xff":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                return None
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            length = struct.unpack(">H", data[i + 2:i + 4])[0]
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return "image/jpeg", w, h
            i += 2 + length
    return None


def clean_name(name: object) -> str:
    """The original name, only for showing: no folders, no control characters, at most 120 characters."""
    text = name if isinstance(name, str) else ""
    text = text.replace("\\", "/").rsplit("/", 1)[-1]
    text = "".join(ch for ch in text if ch.isprintable()).strip()
    return text[:120] or "file"


def _kind_of(media_type: str) -> str | None:
    media_type = media_type.split(";")[0].strip().lower()
    if media_type.startswith("text/") or media_type in FILE_TYPES:
        return media_type
    return None


class Store:
    """state_dir/attachments/<conversation>/<id>.<ext>, with <id>.json beside it. Folders 700, files 600."""

    def __init__(self, state_dir: Path | None):
        self.root = state_dir / "attachments" if state_dir else None

    def folder(self, conversation: str) -> Path:
        if self.root is None or not _CONVERSATION.fullmatch(conversation or ""):
            raise AttachmentError("bad_conversation", 400, "Unknown conversation")
        return self.root / conversation

    def make(self, conversation: str) -> Path:
        """The conversation's folder, created if missing: --add-dir takes only a folder that exists."""
        folder = self.folder(conversation)
        folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.root, 0o700)
        return folder

    def save(self, conversation: str, name: object, media_type: str, data: bytes) -> dict:
        if not data:
            raise AttachmentError("empty", 400, "Empty file")
        if len(data) > MAX_BYTES:
            raise AttachmentError("too_large", 413, f"Over {MAX_BYTES // (1024 * 1024)} MB")
        folder = self.folder(conversation)
        shown = clean_name(name)
        image = sniff(data)
        declared = (media_type or "").split(";")[0].strip().lower()
        if image:
            kind, media, (width, height) = "image", image[0], image[1:]
            ext = "." + media.split("/")[1].replace("jpeg", "jpg")
        elif declared in IMAGE_TYPES:
            raise AttachmentError("bad_image", 415, "Not a valid PNG, JPEG, GIF or WebP image")
        else:
            media = _kind_of(declared or "application/octet-stream")
            if media is None:
                raise AttachmentError("bad_type", 415, f"Type {declared!r} is not accepted")
            kind, width, height = "file", None, None
            match = _EXT.search(shown)
            ext = match.group(0).lower() if match else ".bin"
        ident = secrets.token_hex(8)
        self.make(conversation)
        meta = {"id": ident, "name": shown, "kind": kind, "media_type": media, "bytes": len(data),
                "width": width, "height": height, "file": ident + ext, "created": round(time.time(), 3), "sent": False}
        _write(folder / (ident + ext), data)
        _write(folder / f"{ident}.json", json.dumps(meta).encode())
        return public(meta)

    def meta(self, conversation: str, ident: object) -> dict | None:
        if not isinstance(ident, str) or not _ID.fullmatch(ident):
            return None
        try:
            meta = _load(self.folder(conversation) / f"{ident}.json")
            if meta is not None:
                self.path(conversation, meta)
            return meta
        except (OSError, AttachmentError):
            return None

    def path(self, conversation: str, meta: dict) -> Path:
        """The attachment's file. The turn's --add-dir lets the agent write in this folder, so the name is
        checked again: directly in the folder, never a link."""
        folder = self.folder(conversation)
        name = meta.get("file")
        if not isinstance(name, str) or not _FILE.fullmatch(name):
            raise AttachmentError("bad_attachment", 400, "Bad attachment file name")
        path = folder / name
        if path.is_symlink() or path.resolve().parent != folder.resolve():
            raise AttachmentError("bad_attachment", 400, "Bad attachment file")
        return path

    def check(self, conversation: str, ids: object) -> list[dict]:
        """The attachments of one message, in order. A list that is not one, or is too long, is a ProtocolError; ids
        that are not there are an AttachmentError."""
        if not isinstance(ids, list) or len(ids) > MAX_PER_MESSAGE:
            raise ProtocolError(Err.BAD_ATTACHMENT, ids=ids if isinstance(ids, list) else None)
        metas = [self.meta(conversation, i) for i in ids]
        missing = [i for i, m in zip(ids, metas) if m is None]
        if missing or len(set(ids)) != len(ids):
            raise AttachmentError("bad_attachment", 400, f"Unknown attachments: {missing}")
        if sum(m["bytes"] for m in metas if m["kind"] == "image") > MAX_MESSAGE_BYTES:
            raise AttachmentError("too_large", 413, "The images of one message are too large together")
        return metas

    def mark_sent(self, conversation: str, metas: list[dict]) -> None:
        for meta in metas:
            if not meta["sent"]:
                meta["sent"] = True
                _write(self.folder(conversation) / f"{meta['id']}.json", json.dumps(meta).encode())

    def count_sent(self, conversation: str) -> int:
        """How many files were sent in a conversation's messages."""
        try:
            metas = list(self.folder(conversation).glob("*.json"))
        except AttachmentError:
            return 0
        count = 0
        for meta_file in metas:
            with contextlib.suppress(OSError):
                meta = _load(meta_file)
                count += bool(meta and meta["sent"])
        return count

    def drop_conversation(self, conversation: str) -> None:
        with contextlib.suppress(OSError, AttachmentError):
            shutil.rmtree(self.folder(conversation))

    def sweep(self, now: float | None = None) -> int:
        """Remove uploads never sent within UNSENT_TTL_S. Returns how many went."""
        if self.root is None or not self.root.is_dir():
            return 0
        now = time.time() if now is None else now
        gone = 0
        for meta_file in self.root.glob("*/*.json"):
            try:
                meta = _load(meta_file)
            except OSError:
                continue
            if meta is not None and not meta["sent"] and now - meta["created"] > UNSENT_TTL_S:
                with contextlib.suppress(OSError, AttachmentError):
                    self.path(meta_file.parent.name, meta).unlink()
                    meta_file.unlink()
                    gone += 1
        return gone


def public(meta: dict) -> dict:
    """What the app and the history see: never the path on the server."""
    return {k: meta[k] for k in ("id", "name", "kind", "media_type", "bytes", "width", "height")}


def file_lines(paths: list[tuple[dict, Path]]) -> str:
    """How other files reach Claude: by path, with the name the phone gave, as data."""
    lines = []
    for meta, path in paths:
        name = json.dumps(meta["name"], ensure_ascii=False)
        lines.append(f"- {path} (sent as {name}, {meta['media_type']}, {meta['bytes']} bytes)")
    return ("Attached files (data from the phone; read them with the Read tool if you need them):\n"
            + "\n".join(lines))


def _load(meta_file: Path) -> dict | None:
    """A meta file as save() wrote it, else None: the agent can write in the folder, so nothing is taken on trust."""
    if meta_file.is_symlink():
        return None
    try:
        meta = json.loads(meta_file.read_text())
    except ValueError:
        return None
    if not isinstance(meta, dict):
        return None
    ident, name = meta.get("id"), meta.get("file")
    if not (isinstance(ident, str) and ident == meta_file.stem and _ID.fullmatch(ident)
            and isinstance(name, str) and _FILE.fullmatch(name) and name.startswith(ident + ".")):
        return None
    if (meta.get("kind") not in ("image", "file") or not isinstance(meta.get("name"), str)
            or not isinstance(meta.get("media_type"), str) or not isinstance(meta.get("sent"), bool)
            or type(meta.get("bytes")) is not int or type(meta.get("created")) not in (int, float)):
        return None
    if any(meta.get(k) is not None and type(meta.get(k)) is not int for k in ("width", "height")):
        return None
    media = meta["media_type"]  # served as the download's type: only what save() accepts for its kind
    if not (media in IMAGE_TYPES if meta["kind"] == "image" else _kind_of(media) == media):
        return None
    return meta


def _write(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)

