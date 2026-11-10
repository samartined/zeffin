# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The models and effort levels the chat offers, from what the installed Claude Code reports.

Claude Code lists its models in the answer to the `initialize` control request
(code.claude.com/docs/en/agent-sdk/typescript: ModelInfo, with value, resolvedModel, displayName, description,
supportedEffortLevels and supportsAutoMode). ModelInfo has no billing field: the only sign that a model bills usage
credits is its own text, as the /model picker's "Requires usage credits" on a Fable row
(code.claude.com/docs/en/model-config). In `claude -p` such a request is billed without asking.

The rule "covered by the subscription", per row:
- `default`, `best` and `opusplan` are left out: the account's default, an alias of another row, and a mode;
- available: an alias (opus, sonnet, haiku, fable) or a pinned name (claude-...), with no [1m], whose name and
  description do not mention credits;
- usage_credits: its name or description mentions credits;
- unchecked: the rows cannot prove it, as a [1m] variant (included or billed depending on the plan and the model)
  or a value of a kind the server does not know.
Every model kept is listed, in Claude Code's order, with its availability. Without the list, the fixed opus, sonnet
and haiku are offered, and Fable is not.

That rule holds while the "free only" switch is on (config.free_only). Off, every model kept is available: the user
chose their login. Either way each entry says whether it is `paid`: it may bill per use (the rule above finds a
reason), or the login is not a subscription, so every model bills (an API key).
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..wire import EFFORTS, ModelReason

ALIASES = ("opus", "sonnet", "haiku", "fable")
SKIPPED = ("default", "best", "opusplan")
PINNED = "claude-"
LONG_CONTEXT = "[1m]"
LABEL_CHARS = 40
USAGE_CREDITS, UNCHECKED = ModelReason.USAGE_CREDITS, ModelReason.UNCHECKED


@dataclass
class Catalog:
    models: list[dict]  # chat.status.models
    blocked: set[str] = field(default_factory=set)  # the values and resolved names of the models not available
    allowed: set[str] = field(default_factory=set)  # the same for the models available


def fixed(subscription: bool = True) -> list[dict]:
    return [{"value": v, "label": v.capitalize(), "efforts": list(EFFORTS), "auto": True, "images": True,
             "available": True, "reason": None, "paid": not subscription} for v in ("opus", "sonnet", "haiku")]


def reason(row: dict) -> str | None:
    """Why the subscription may not cover this row's model, or None when it does."""
    value = str(row.get("value"))
    text = f"{row.get('displayName', '')} {row.get('description', '')}".lower()
    if "credit" in text:
        return USAGE_CREDITS
    if LONG_CONTEXT in value.lower() or not (value in ALIASES or value.startswith(PINNED)):
        return UNCHECKED
    return None


def entry(row: dict, free_only: bool = True, subscription: bool = True) -> dict:
    value = row["value"]
    levels = row.get("supportedEffortLevels")
    # No levels listed: none, unless the row says the model takes an effort (then every level, as before).
    efforts = [e for e in levels if e in EFFORTS] if isinstance(levels, list) else (
        list(EFFORTS) if row.get("supportsEffort") is True else [])
    name = row.get("displayName")
    label = name.strip() if isinstance(name, str) and name.strip() else (
        value.capitalize() if value in ALIASES else value)
    why = reason(row)
    paid = why is not None or not subscription
    if not free_only:
        why = None
    # ModelInfo has no field for images: every model is said to take them, as every turn has sent them so far (a
    # stream-json user message with image blocks, command.py, and the Flipper's screen in a tool result).
    return {"value": value, "label": label[:LABEL_CHARS], "efforts": efforts,
            "auto": row.get("supportsAutoMode") is True, "images": True, "available": why is None, "reason": why,
            "paid": paid}


def build(rows: list | None, free_only: bool = True, subscription: bool = True) -> Catalog:
    """The catalog from Claude Code's rows; None or no usable row: the fixed list. `subscription`: the login is one."""
    models: dict[str, dict] = {}
    blocked: set[str] = set()
    allowed: set[str] = set()
    for row in rows or []:
        if not isinstance(row, dict) or not isinstance(row.get("value"), str) or not row["value"].strip():
            continue
        if row["value"] in SKIPPED or row["value"] in models:
            continue
        item = entry(row, free_only, subscription)
        models[item["value"]] = item
        names = blocked if not item["available"] else allowed
        names.add(item["value"])
        if isinstance(row.get("resolvedModel"), str):
            names.add(row["resolvedModel"])
    if not models:
        return Catalog(fixed(subscription))
    return Catalog(list(models.values()), blocked, allowed - blocked)
