# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Text helpers shared by every layer: bound, clean and shorten text. Imports nothing from bridge."""
from __future__ import annotations

import re
from typing import Any

# C0 but \t and \n, DEL, C1 (CSI is U+009B) and the bidirectional marks, overrides and isolates
_TERMINAL = re.compile("[\x00-\x08\x0b-\x1f\x7f-\x9f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]")


def truncate(value: Any, limit: int = 300) -> Any:
    """Shorten arguments for the audit log (never log tokens; tools never receive them)."""
    if isinstance(value, str):
        return value if len(value) <= limit else value[:limit] + f"...(+{len(value) - limit})"
    if isinstance(value, dict):
        return {k: truncate(v, limit) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [truncate(v, limit) for v in value[:50]]
    return value


def clean_text(value, limit: int = 64) -> str | None:
    """Optional text from the phone: printable characters only, bounded, else None."""
    if not isinstance(value, str):
        return None
    text = "".join(ch for ch in value if ch.isprintable()).strip()[:limit]
    return text or None


def one_line(text: str, limit: int) -> str:
    """Collapse whitespace and drop control characters, for titles and previews."""
    clean = " ".join("".join(ch if ch.isprintable() else " " for ch in text).split())
    return clean if len(clean) <= limit else clean[:limit - 1].rstrip() + "…"


def cut(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[:limit] + f"…(+{len(text) - limit})"


def terminal_safe(text: str) -> str:
    """For a terminal: control characters written as \\uXXXX, so text from the agent or the Flipper can neither move
    the cursor, erase or recolour what was printed, nor reorder it. Keeps \\n and \\t."""
    return _TERMINAL.sub(lambda m: f"\\u{ord(m.group()):04x}", text)
