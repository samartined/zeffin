# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The pairing session and what the QR code holds: below the adapters, so the web view, the routes of
pairing.py and zf pair share it. The contract is docs/protocol/pairing.

The QR code holds {"fb": 3, "url": <https base URL>, "code": <code>, "key": <pin of the server's key>}, never the
tokens. While a pairing is open (started by the web view's Pair the app or by zf pair) the code changes every
CODE_PERIOD_S; the one before keeps working for CODE_GRACE_S, and the pairing ends after SESSION_S.

A right code does not hand over the tokens. It makes one request, with a ticket only that phone has and six random
digits the phone and the server both show; the pairing then freezes (no more codes, and every scan of it gets
code_used) until the owner confirms or refuses that request by its `ref`, within CONFIRM_S. The phone collects the
tokens once with its ticket, within DELIVER_S of the confirmation.

Codes, tickets, refs and numbers live in memory only (a restart ends them) and are never logged. Wrong codes only
fail: 128 bits cannot be guessed, and a lockout would let any private-network peer end the owner's pairing. Time is
read from `clock` whenever the session is touched, so there is no timer, and a fake clock tests all of it.
"""
from __future__ import annotations

import base64
import hmac
import io
import json
import math
import secrets
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field

PAIR_VERSION = 3
CODE_PERIOD_S = 30.0
CODE_GRACE_S = 5.0
SESSION_S = 180.0
CONFIRM_S = 120.0
DELIVER_S = 60.0

# What a pairing can be in. Only "showing" has a code that works; only "confirming" has a request the owner answers.
SHOWING, CONFIRMING, CONFIRMED = "showing", "confirming", "confirmed"
PAIRED, REFUSED, EXPIRED, CANCELLED, PHONE_CANCELLED, LOST = (
    "paired", "refused", "expired", "cancelled", "phone_cancelled", "lost")


def _same(a: str, b: object) -> bool:
    return isinstance(b, str) and hmac.compare_digest(a.encode(), b.encode())


@dataclass
class Request:
    """One phone's request, made by a right code."""

    ticket: str     # the phone's: it collects the tokens with it
    ref: str        # the server's screens': confirm and refuse name it
    number: str     # six digits, shown on both sides
    peer: str
    asked: float
    state: str = "waiting"  # waiting, confirmed, refused, expired, withdrawn, delivered
    decided: float | None = None


@dataclass
class _Code:
    value: str
    minted: float
    until: float | None = None  # when it stops working; None while it is the current code


@dataclass
class _Session:
    id: str
    started: float
    ends: float
    state: str = SHOWING
    codes: list[_Code] = field(default_factory=list)
    request: Request | None = None
    ended: float | None = None


class PairCodes:
    """The one pairing that is open, if any, and what became of the last one."""

    def __init__(self, base_url: str = "", pin: str = "", clock: Callable[[], float] = time.time,
                 on_end: Callable[[str, str], object] | None = None):
        self.base_url, self.pin, self.clock = base_url, pin, clock
        self.on_end = on_end            # (outcome, peer) once a request ends: paired, refused, expired, lost, cancelled
        self.session: _Session | None = None
        self.requests: deque[Request] = deque(maxlen=4)  # recent requests, so an old ticket gets its own answer
        self.listeners: set[Callable[[], object]] = set()

    # ------------------------------------------------------------------ the owner's side (web view, zf pair)

    def start(self) -> dict:
        """Open a new pairing with a fresh code; one still open ends (its request, if any, expires)."""
        now = self.clock()
        self._tick(now)
        if self.session is not None and self.session.ended is None:
            self._end(self.session, CANCELLED, now, request_state="expired", outcome=CANCELLED)
        self.session = _Session(secrets.token_urlsafe(6), now, now + SESSION_S, codes=[_Code(self._code(), now)])
        self._changed()
        return self.view()

    def view(self) -> dict:
        """What the owner's screens show. While the code is shown it holds the payload, with the code: for
        authenticated callers only."""
        now = self.clock()
        self._tick(now)
        s = self.session
        if s is None:
            return {"state": "none", "session": None}
        out: dict = {"state": s.state, "session": s.id}
        if s.state == SHOWING:
            current = s.codes[-1]
            out.update(payload=self.payload(current.value), seq=len(s.codes),
                       rotates_in=_left(current.minted + CODE_PERIOD_S - now), ends_in=_left(s.ends - now))
        elif s.state in (CONFIRMING, CONFIRMED) and s.request is not None:
            r = s.request
            out["request"] = {"ref": r.ref, "number": r.number, "peer": r.peer, "expires_in": self.expires_in(r)}
        elif s.state == PAIRED and s.request is not None:
            out["paired_at"] = s.ended
        return out

    def confirm(self, ref: object) -> bool:
        """The owner confirms the request named by ref: its phone may now collect the tokens."""
        now = self.clock()
        self._tick(now)
        s = self.session
        if s is None or s.state != CONFIRMING or s.request is None or not _same(s.request.ref, ref):
            return False
        s.state, s.request.state, s.request.decided = CONFIRMED, "confirmed", now
        self._changed()
        return True

    def refuse(self, ref: object) -> bool:
        """The owner refuses the request named by ref: nothing is sent, and the pairing ends."""
        now = self.clock()
        self._tick(now)
        s = self.session
        if s is None or s.state not in (CONFIRMING, CONFIRMED) or s.request is None or not _same(s.request.ref, ref):
            return False
        self._end(s, REFUSED, now, request_state="refused", outcome=REFUSED)
        return True

    def cancel(self) -> dict:
        """The owner ends the pairing: its code stops working, and a waiting request expires."""
        now = self.clock()
        self._tick(now)
        s = self.session
        if s is not None and s.ended is None:
            self._end(s, CANCELLED, now, request_state="expired", outcome=CANCELLED)
        return self.view()

    # ------------------------------------------------------------------ the phone's side (POST /app/pair...)

    def ask(self, code: object, peer: str) -> tuple[str, Request | None]:
        """A scanned code: ("waiting", request), or ("bad_code" | "code_used" | "code_expired", None)."""
        now = self.clock()
        self._tick(now)
        s = self.session
        if not isinstance(code, str):
            return "bad_code", None
        if s is None:
            return "code_expired", None
        known = any(_same(c.value, code) for c in s.codes)
        if s.state != SHOWING:
            if known and s.state in (CONFIRMING, CONFIRMED, PAIRED):
                return "code_used", None
            return ("code_expired" if known or s.ended is not None else "bad_code"), None
        if not any(_same(c.value, code) for c in s.codes if c.until is None or now < c.until):
            return ("code_expired" if known else "bad_code"), None
        r = Request(secrets.token_urlsafe(16), secrets.token_urlsafe(9), f"{secrets.randbelow(10 ** 6):06d}", peer, now)
        s.state, s.request = CONFIRMING, r
        for c in s.codes:  # the pairing is spent: no code of it works again
            c.until = min(c.until or now, now)
        self.requests.append(r)
        self._changed()
        return "waiting", r

    def poll(self, ticket: object) -> tuple[str, Request | None]:
        """A phone's ticket: "waiting", "paired" (once: the caller hands over the tokens now), "refused", "expired" or
        "unknown"."""
        now = self.clock()
        self._tick(now)
        r = self._find(ticket)
        if r is None or r.state in ("withdrawn", "delivered"):
            return "unknown", None
        if r.state in ("waiting", "refused", "expired"):
            return r.state, r
        r.state = "delivered"
        s = self.session
        if s is not None and s.request is r:
            s.state, s.ended = PAIRED, now
        self._report(PAIRED, r.peer)
        self._changed()
        return "paired", r

    def expires_in(self, r: Request) -> int:
        """Seconds left for the owner to answer (waiting) or for the phone to collect (confirmed)."""
        limit = r.asked + CONFIRM_S if r.state == "waiting" else (r.decided or r.asked) + DELIVER_S
        return _left(limit - self.clock())

    def withdraw(self, ticket: object) -> bool:
        """The phone gives up its request before it collects the tokens."""
        now = self.clock()
        self._tick(now)
        r = self._find(ticket)
        if r is None or r.state not in ("waiting", "confirmed"):
            return False
        s = self.session
        if s is not None and s.request is r:
            self._end(s, PHONE_CANCELLED, now, request_state="withdrawn", outcome=None)
        else:
            r.state = "withdrawn"
        return True

    # ------------------------------------------------------------------ inside

    def payload(self, code: str) -> str:
        """What the QR code and the pairing text hold, as compact JSON."""
        return json.dumps({"fb": PAIR_VERSION, "url": self.base_url, "code": code, "key": self.pin},
                          separators=(",", ":"))

    @staticmethod
    def _code() -> str:
        return secrets.token_urlsafe(16)

    def _find(self, ticket: object) -> Request | None:
        return next((r for r in self.requests if _same(r.ticket, ticket)), None)

    def _tick(self, now: float) -> None:
        s = self.session
        if s is None or s.ended is not None:
            return
        if s.state == SHOWING:
            if now >= s.ends:
                self._end(s, EXPIRED, now, request_state=None, outcome=None)
                return
            current = s.codes[-1]
            if now >= current.minted + CODE_PERIOD_S:
                periods = math.floor((now - current.minted) / CODE_PERIOD_S)
                minted = current.minted + periods * CODE_PERIOD_S
                # The code shown until now keeps its grace; one nobody looked at in time is simply gone.
                current.until = current.minted + CODE_PERIOD_S + CODE_GRACE_S if periods == 1 else minted
                s.codes.append(_Code(self._code(), minted))
                self._changed()
        elif s.state == CONFIRMING and s.request is not None and now >= s.request.asked + CONFIRM_S:
            self._end(s, EXPIRED, now, request_state="expired", outcome=EXPIRED)
        elif s.state == CONFIRMED and s.request is not None and now >= (s.request.decided or now) + DELIVER_S:
            self._end(s, LOST, now, request_state="expired", outcome=LOST)

    def _end(self, s: _Session, state: str, now: float, *, request_state: str | None, outcome: str | None) -> None:
        s.state, s.ended = state, now
        for c in s.codes:
            c.until = min(c.until or now, now)
        r = s.request
        if r is not None and r.state in ("waiting", "confirmed"):
            if request_state is not None:
                r.state = request_state
            if outcome is not None:
                self._report(outcome, r.peer)
        self._changed()

    def _report(self, outcome: str, peer: str) -> None:
        if self.on_end is not None:
            self.on_end(outcome, peer)

    def _changed(self) -> None:
        for listener in list(self.listeners):
            listener()


def _left(seconds: float) -> int:
    return max(0, math.ceil(seconds))


def qr_svg(text: str) -> str:
    """The QR code as an SVG data URI, for the web view (its CSP allows data: images)."""
    import qrcode
    import qrcode.image.svg

    image = qrcode.make(text, image_factory=qrcode.image.svg.SvgPathImage, border=2,
                        error_correction=qrcode.constants.ERROR_CORRECT_M)
    out = io.BytesIO()
    image.save(out)
    return "data:image/svg+xml;base64," + base64.b64encode(out.getvalue()).decode()
