# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The server's TLS key (bridge/tlskey.py): its files and their mode, the pin and the fingerprint against the shared test
vector, renewal on the same key, a new key, and a key file that cannot be used."""
import datetime
import json
import os
from pathlib import Path

import pytest
from cryptography import x509

from bridge import tlskey

VECTOR = json.loads((Path(__file__).resolve().parents[2] / "docs" / "protocol" / "pairing" / "key-vector.json").read_text())
NOW = datetime.datetime(2026, 10, 31, 12, 0, tzinfo=datetime.timezone.utc)


def mode(path: Path) -> int:
    return path.stat().st_mode & 0o777


def cert(path: Path) -> x509.Certificate:
    return x509.load_pem_x509_certificate(path.read_bytes())


def test_the_pin_and_the_fingerprint_are_the_shared_vectors():
    assert tlskey.pin_of(bytes.fromhex(VECTOR["spki_der_hex"])) == VECTOR["pin"]
    assert tlskey.fingerprint_of(VECTOR["pin"]) == VECTOR["fingerprint"]
    assert tlskey.standard_base64(VECTOR["pin"]) == VECTOR["pin_standard_base64"]


def test_the_key_is_made_once_private_and_kept_across_starts(tmp_path):
    key, created = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    assert created and mode(key.key_path) == 0o600 and mode(key.cert_path) == 0o600
    assert key.key_path.name == "tls-key.pem" and key.cert_path.name == "tls-cert.pem"
    again, created = tlskey.ensure(tmp_path, "100.98.12.7", NOW + datetime.timedelta(days=1))
    assert not created and again == key and tlskey.read(tmp_path) == key
    assert len(key.pin) == 43 and len(key.fingerprint) == 24 and key.fingerprint == tlskey.fingerprint_of(key.pin)
    assert not [p.name for p in tmp_path.iterdir() if p.name.startswith(".tls-")]  # no temporary file left


def test_the_certificate_names_nothing_but_the_bound_address(tmp_path):
    key, _ = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    c = cert(key.cert_path)
    assert c.subject.rfc4514_string() == "CN=zeffin" and c.issuer == c.subject
    sans = c.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    assert [str(ip) for ip in sans.get_values_for_type(x509.IPAddress)] == ["100.98.12.7"]
    assert not sans.get_values_for_type(x509.DNSName)
    assert c.extensions.get_extension_for_class(x509.BasicConstraints).value.ca is False
    assert (c.not_valid_after_utc - NOW).days == tlskey.CERT_DAYS
    assert tlskey.cert_pin(c.public_bytes(tlskey.serialization.Encoding.DER)) == key.pin


def test_the_certificate_is_renewed_on_the_same_key(tmp_path):
    key, _ = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    first = cert(key.cert_path).serial_number
    moved, created = tlskey.ensure(tmp_path, "100.98.12.8", NOW)  # zf set-host: a new certificate, never a new key
    assert not created and moved.pin == key.pin and cert(key.cert_path).serial_number != first
    second = cert(key.cert_path).serial_number
    tlskey.ensure(tmp_path, "100.98.12.8", NOW + datetime.timedelta(days=tlskey.CERT_DAYS - tlskey.RENEW_DAYS - 1))
    assert cert(key.cert_path).serial_number == second  # still more than 30 days left
    late, _ = tlskey.ensure(tmp_path, "100.98.12.8", NOW + datetime.timedelta(days=tlskey.CERT_DAYS - tlskey.RENEW_DAYS))
    assert cert(key.cert_path).serial_number != second and late.pin == key.pin


def test_a_new_key_changes_the_pin(tmp_path):
    old, _ = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    new = tlskey.new(tmp_path, "100.98.12.7", NOW)
    assert new.pin != old.pin and tlskey.read(tmp_path) == new and mode(new.key_path) == 0o600
    assert tlskey.cert_pin(cert(new.cert_path).public_bytes(tlskey.serialization.Encoding.DER)) == new.pin


def test_a_key_other_users_can_read_is_refused(tmp_path):
    key, _ = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    os.chmod(key.key_path, 0o644)
    with pytest.raises(tlskey.KeyFileError, match="chmod 600"):
        tlskey.ensure(tmp_path, "100.98.12.7", NOW)


def test_a_key_that_cannot_be_used_stops_the_server_and_is_never_replaced(tmp_path):
    key, _ = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    secret = key.key_path.read_bytes()
    broken = secret[:40] + b"!" + secret[41:]
    key.key_path.write_bytes(broken)
    with pytest.raises(tlskey.KeyFileError) as e:
        tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    assert str(key.key_path) in str(e.value) and "zf key new" in str(e.value)
    assert b"PRIVATE" not in str(e.value).encode() and secret[30:60].decode() not in str(e.value)
    assert key.key_path.read_bytes() == broken  # left as it was: a new key means pairing again


def test_nothing_returned_or_raised_holds_the_key(tmp_path):
    key, _ = tlskey.ensure(tmp_path, "100.98.12.7", NOW)
    body = key.key_path.read_text()
    assert "PRIVATE KEY" in body
    assert "PRIVATE" not in repr(key) and body.splitlines()[1] not in repr(key)
