# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The /app schemas of docs/protocol/app, for the tests: check a message against its type's schema."""
from __future__ import annotations

import json
from functools import cache
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2] / "docs" / "protocol" / "app"
BASE = "https://zeffin.invalid/protocol/app/"  # the schemas' $id prefix: a name, never fetched
EXAMPLES = ROOT / "examples"
DIRECTIONS = ("server", "app")  # server -> app, app -> server


def schema_files() -> list[Path]:
    return sorted(p for p in ROOT.rglob("*.json") if EXAMPLES not in p.parents)


@cache
def registry() -> Registry:
    resources = []
    for path in schema_files():
        contents = json.loads(path.read_text())
        resources.append((contents["$id"], Resource.from_contents(contents)))
    return Registry().with_resources(resources)


@cache
def validator(rel: str) -> Draft202012Validator | None:
    """The validator of server/<type>.json, app/<type>.json or events/<kind>.json; None if there is none."""
    path = ROOT / rel
    if not path.is_file():
        return None
    schema = json.loads(path.read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, registry=registry())


def event_validator() -> Draft202012Validator:
    """A history event, by its kind (defs.json's event)."""
    return Draft202012Validator({"$ref": BASE + "defs.json#/$defs/event"}, registry=registry())


def problems(direction: str, message: object) -> list[str]:
    """Why a message does not match its type's schema (empty when it does)."""
    if not isinstance(message, dict) or not isinstance(message.get("type"), str):
        return [f"{direction}: not a message with a type: {str(message)[:200]}"]
    v = validator(f"{direction}/{message['type']}.json")
    if v is None:
        return [f"{direction}: no schema for type {message['type']!r}"]
    return [f"{direction} {message['type']}: {'/'.join(map(str, e.absolute_path)) or '(top)'}: {e.message[:300]}"
            for e in v.iter_errors(message)]
