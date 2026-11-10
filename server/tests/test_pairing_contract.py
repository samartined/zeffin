# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The pairing contract in docs/protocol/pairing: the schemas are valid, every example matches its schema, every schema
has an example, and the shared key vector agrees with itself. The server's own replies are checked against these schemas
in test_pairing.py."""
import base64
import hashlib
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2] / "docs" / "protocol" / "pairing"
EXAMPLES = ROOT / "examples"
VECTOR = ROOT / "key-vector.json"


def schema_names() -> list[str]:
    return sorted(p.name.removesuffix(".json") for p in ROOT.glob("*.json") if p != VECTOR)


def example_schema(path: Path) -> str:
    """examples/<schema>[.<variant>].json: the longest schema name the file name starts with."""
    stem = path.name.removesuffix(".json")
    found = [n for n in schema_names() if stem == n or stem.startswith(n + ".")]
    assert found, f"{path.name} names no schema"
    return max(found, key=len)


@pytest.mark.parametrize("name", schema_names())
def test_every_schema_is_valid_and_has_an_example(name):
    schema = json.loads((ROOT / f"{name}.json").read_text())
    Draft202012Validator.check_schema(schema)
    assert schema["$id"] == f"https://zeffin.invalid/protocol/pairing/{name}.json"
    assert schema["additionalProperties"] is False
    assert any(example_schema(p) == name for p in EXAMPLES.glob("*.json")), f"{name} has no example"


@pytest.mark.parametrize("path", sorted(EXAMPLES.glob("*.json")), ids=lambda p: p.name)
def test_every_example_matches_its_schema(path):
    schema = json.loads((ROOT / f"{example_schema(path)}.json").read_text())
    errors = [e.message for e in Draft202012Validator(schema).iter_errors(json.loads(path.read_text()))]
    assert not errors


def test_every_error_code_has_an_example():
    for name in ("pair.error", "wait.error", "cancel.error"):
        codes = json.loads((ROOT / f"{name}.json").read_text())["properties"]["error"]["enum"]
        assert {json.loads((EXAMPLES / f"{name}.{code}.json").read_text())["error"] for code in codes} == set(codes)


def test_the_key_vector_agrees_with_itself():
    v = json.loads(VECTOR.read_text())
    der = bytes.fromhex(v["spki_der_hex"])
    pem_body = "".join(line for line in v["public_key_pem"].splitlines() if not line.startswith("-----"))
    assert v["public_key_pem"].startswith("-----BEGIN PUBLIC KEY-----\n")
    assert base64.b64decode(pem_body) == der
    digest = hashlib.sha256(der).digest()
    assert v["pin"] == base64.urlsafe_b64encode(digest).rstrip(b"=").decode() and len(v["pin"]) == 43
    assert v["pin_sha256_hex"] == digest.hex()
    assert v["pin_standard_base64"] == base64.b64encode(digest).decode()
    short = digest[:10].hex().upper()
    assert v["fingerprint"] == " ".join(short[i:i + 4] for i in range(0, 20, 4))
    assert "PRIVATE" not in VECTOR.read_text()


def test_the_qr_example_carries_the_vector_pin_and_no_token():
    qr = json.loads((EXAMPLES / "qr.json").read_text())
    assert qr["key"] == json.loads(VECTOR.read_text())["pin"]
    assert set(qr) == {"fb", "url", "code", "key"}
