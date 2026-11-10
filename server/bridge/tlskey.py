# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The server's own key: what the public listener's TLS proves, and what the pairing QR code pins.

`zf` makes an elliptic-curve key (P-256) and a self-signed certificate in the data folder, next to config.json:
tls-key.pem (PKCS#8) and tls-cert.pem, both mode 0600. The pin is the SHA-256 of the key's SubjectPublicKeyInfo, not
of the certificate, so the certificate is renewed on the same key (when the bound address in its SAN changes, or near
its end) and the app does not notice. Only `zf key new` changes the key, and that means pairing again.

The key never leaves its file: nothing here prints, logs or returns it, and an error names the file, never its
contents. The fingerprint (the pin's first 10 bytes in hex) is public and may be shown anywhere.

The contract, with the shared test vector, is docs/protocol/pairing.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import ipaddress
import os
import socket
import ssl
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.exceptions import UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

KEY_FILE = "tls-key.pem"
CERT_FILE = "tls-cert.pem"
CERT_DAYS = 397
RENEW_DAYS = 30
FINGERPRINT_BYTES = 10
COMMON_NAME = "zeffin"  # the certificate's only name: no host, user or path


class KeyFileError(Exception):
    """The key or certificate cannot be used: the message names the file, never what is in it."""


class Mismatch(Exception):
    """The server's key is not the pinned one: nothing was sent."""


@dataclass(frozen=True)
class ServerKey:
    """The key the public listener serves: its files, its pin and its fingerprint."""

    key_path: Path
    cert_path: Path
    pin: str
    fingerprint: str


def paths(data_dir: Path) -> tuple[Path, Path]:
    return data_dir / KEY_FILE, data_dir / CERT_FILE


def pin_of(spki_der: bytes) -> str:
    """The QR code's `key`: SHA-256 of the SubjectPublicKeyInfo (DER), base64url without padding."""
    return base64.urlsafe_b64encode(hashlib.sha256(spki_der).digest()).rstrip(b"=").decode()


def fingerprint_of(pin: str) -> str:
    """The short form the owner compares: the pin's first 10 bytes, upper-case hex, in 5 groups of 4."""
    digest = base64.urlsafe_b64decode(pin + "=")
    short = digest[:FINGERPRINT_BYTES].hex().upper()
    return " ".join(short[i:i + 4] for i in range(0, len(short), 4))


def standard_base64(pin: str) -> str:
    """The pin in standard base64, as curl's --pinnedpubkey sha256//... takes it."""
    return base64.b64encode(base64.urlsafe_b64decode(pin + "=")).decode()


def _spki(public_key) -> bytes:
    return public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def cert_pin(cert_der: bytes) -> str:
    """The pin of the key in a certificate (DER), as a TLS peer presents it."""
    return pin_of(_spki(x509.load_der_x509_certificate(cert_der).public_key()))


def _private(path: Path) -> bool:
    return not (path.stat().st_mode & 0o077)


def _load_key(path: Path) -> ec.EllipticCurvePrivateKey:
    if not _private(path):
        raise KeyFileError(f"{path} is readable by other users; run chmod 600 {path}")
    try:
        key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    except (ValueError, TypeError, OSError, UnsupportedAlgorithm):
        key = None
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise KeyFileError(f"{path} is not a key this server can use. It is never replaced by itself: "
                           "zf key new makes a new one, and the app must pair again")
    return key


def _write(path: Path, data: bytes, *, replace: bool) -> bool:
    """Write a 0600 file in one step. Without replace, an existing file wins (False)."""
    fd, tmp = tempfile.mkstemp(prefix=".tls-", dir=path.parent)  # O_EXCL, mode 0600
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o600)
        if replace:
            os.replace(tmp, path)
            return True
        try:
            os.link(tmp, path)
        except FileExistsError:
            return False
        return True
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _key_pem(key: ec.EllipticCurvePrivateKey) -> bytes:
    return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption())


def _certificate(key: ec.EllipticCurvePrivateKey, host: str, now: datetime.datetime) -> bytes:
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, COMMON_NAME)])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(hours=1))
            .not_valid_after(now + datetime.timedelta(days=CERT_DAYS))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(host))]), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                         data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                         crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(key, hashes.SHA256()))
    return cert.public_bytes(serialization.Encoding.PEM)


def _cert_fits(path: Path, key: ec.EllipticCurvePrivateKey, host: str, now: datetime.datetime) -> bool:
    """Whether the certificate on disk is for this key and address, private, and not near its end."""
    try:
        if not _private(path):
            return False
        cert = x509.load_pem_x509_certificate(path.read_bytes())
        sans = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        addresses = sans.get_values_for_type(x509.IPAddress)
    except (OSError, ValueError, x509.ExtensionNotFound):
        return False
    return (_spki(cert.public_key()) == _spki(key.public_key()) and addresses == [ipaddress.ip_address(host)]
            and cert.not_valid_after_utc - now > datetime.timedelta(days=RENEW_DAYS))


def _server_key(data_dir: Path, key: ec.EllipticCurvePrivateKey) -> ServerKey:
    pin = pin_of(_spki(key.public_key()))
    key_path, cert_path = paths(data_dir)
    return ServerKey(key_path, cert_path, pin, fingerprint_of(pin))


def ensure(data_dir: Path, host: str, now: datetime.datetime | None = None) -> tuple[ServerKey, bool]:
    """The server's key, made if there is none, and a certificate that fits it: (key, whether the key is new).

    An existing key is never replaced: one that cannot be used raises KeyFileError.
    """
    now = now or datetime.datetime.now(datetime.timezone.utc)
    key_path, cert_path = paths(data_dir)
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    created = False
    if not key_path.exists():
        created = _write(key_path, _key_pem(ec.generate_private_key(ec.SECP256R1())), replace=False)
    key = _load_key(key_path)
    if created or not _cert_fits(cert_path, key, host, now):
        _write(cert_path, _certificate(key, host, now), replace=True)
    return _server_key(data_dir, key), created


def new(data_dir: Path, host: str, now: datetime.datetime | None = None) -> ServerKey:
    """Replace the key and its certificate: the app must pair again."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    key_path, cert_path = paths(data_dir)
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    key = ec.generate_private_key(ec.SECP256R1())
    _write(key_path, _key_pem(key), replace=True)
    _write(cert_path, _certificate(key, host, now), replace=True)
    return _server_key(data_dir, key)


def read(data_dir: Path) -> ServerKey | None:
    """The key on disk, without changing anything; None if there is none yet."""
    key_path, _ = paths(data_dir)
    if not key_path.exists():
        return None
    return _server_key(data_dir, _load_key(key_path))


def server_context(key: ServerKey) -> ssl.SSLContext:
    """TLS 1.3 only, with this key: what the public listener serves (app.listener_config builds the same)."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(key.cert_path, key.key_path)
    return context


def client_context(cert_path: Path) -> ssl.SSLContext:
    """For an asyncio client of the public listener on this machine (the simulator): TLS 1.3, trusting exactly the
    data folder's certificate, checked in the handshake before anything is sent. Its name is not checked."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.check_hostname = False
    context.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN  # the self-signed certificate is the trust anchor
    context.load_verify_locations(cert_path)
    return context


def client_wrap(pin: str) -> Callable[[socket.socket], ssl.SSLSocket]:
    """For fb's own clients of the public listener: TLS 1.3, then the server's key checked against the pin before
    anything is sent. The certificate's name and dates are not checked: the pin is the trust."""

    def wrap(sock: socket.socket) -> ssl.SSLSocket:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        tls = context.wrap_socket(sock)
        der = tls.getpeercert(binary_form=True)
        if der is None or cert_pin(der) != pin:
            tls.close()
            raise Mismatch("the server's key is not the one in the data folder (zf restart after zf key new?)")
        return tls

    return wrap
