# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Pairing the app: the rotating code, the request the owner confirms, the phone's routes against the
contract in docs/protocol/pairing, the web view, zf pair's loopback routes and zf pair itself."""
import asyncio
import base64
import json
import logging
import urllib.error
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator
from starlette.applications import Starlette
from starlette.testclient import TestClient

from bridge import cli, paircodes, pairing
from bridge.app import mcp_app, pairing_metrics, public_app
from bridge.config import Config
from bridge.hub import Hub

CFG = Config(host="100.98.12.7", phone_port=19434, mcp_port=19435, phone_token="p" * 40,
             mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0)
TOKENS = ("p" * 40, "m" * 40, "v" * 40, "a" * 40)
PEER = ("100.98.12.7", 50000)  # a device on the private network: the public listener serves it
CONTRACT = Path(__file__).resolve().parents[2] / "docs" / "protocol" / "pairing"
VECTOR = json.loads((CONTRACT / "key-vector.json").read_text())
PIN = VECTOR["pin"]
APP = {"Authorization": "Bearer " + "a" * 40}


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def matches(schema: str, body: dict) -> bool:
    """Whether a reply is what docs/protocol/pairing/<schema>.json says."""
    validator = Draft202012Validator(json.loads((CONTRACT / f"{schema}.json").read_text()))
    errors = [e.message for e in validator.iter_errors(body)]
    assert not errors, (schema, errors)
    return True


@pytest.fixture
def hub(tmp_path):
    return Hub(tmp_path, keepalive_s=0)


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def codes(hub, clock):
    return paircodes.PairCodes(CFG.base_url(), PIN, clock=clock, on_end=pairing_metrics(hub))


def code_of(state: dict) -> str:
    return json.loads(state["payload"])["code"]


def app_client(codes):
    """The phone's routes, with /app/pair/wait holding briefly and no delay on refusals, for the tests."""
    app = Starlette(routes=pairing.app_routes(codes, CFG, hold_s=0.2, delay_s=0))
    return TestClient(app, client=PEER, base_url="https://testserver")


# ---------------------------------------------------------------------------------------------- the QR code

def test_the_qr_code_is_version_3_with_https_and_the_pin_never_a_token(codes):
    state = codes.start()
    payload = json.loads(state["payload"])
    assert payload == {"fb": 3, "url": "https://100.98.12.7:19434", "code": payload["code"], "key": PIN}
    assert matches("qr", payload) and len(payload["code"]) == 22
    svg = base64.b64decode(paircodes.qr_svg(state["payload"]).split(",", 1)[1]).decode()
    assert "<svg" in svg and not any(t in state["payload"] or t in svg for t in TOKENS)


def test_nothing_is_open_until_the_owner_starts_a_pairing(codes):
    assert codes.view() == {"state": "none", "session": None}
    assert codes.ask("x" * 22, "100.98.12.7") == ("code_expired", None)


# ---------------------------------------------------------------------------------------------- rotation

def test_the_code_changes_every_30_s_and_the_last_one_keeps_5_s(codes, clock):
    first = codes.start()
    assert first["seq"] == 1 and first["rotates_in"] == 30 and first["ends_in"] == 180
    clock.now += 29.9
    assert codes.view()["seq"] == 1 and code_of(codes.view()) == code_of(first)
    clock.now = 1030.0
    second = codes.view()
    assert second["seq"] == 2 and code_of(second) != code_of(first) and second["rotates_in"] == 30
    clock.now = 1034.9  # the previous code's grace
    assert codes.ask(code_of(first), "100.98.12.7")[0] == "waiting"


def test_after_its_grace_the_old_code_has_expired_and_the_new_one_works(codes, clock):
    first = codes.start()
    clock.now = 1035.0
    assert codes.ask(code_of(first), "100.98.12.7") == ("code_expired", None)
    assert codes.ask(code_of(codes.view()), "100.98.12.7")[0] == "waiting"


def test_a_code_nobody_looked_at_in_time_is_simply_gone(codes, clock):
    first = codes.start()
    clock.now = 1061.0  # two periods later: the code of 1030 was never shown, and the first one gets no grace
    later = codes.view()
    assert later["seq"] == 2 and later["rotates_in"] == 29
    assert codes.ask(code_of(first), "100.98.12.7") == ("code_expired", None)


def test_the_pairing_ends_after_3_minutes(codes, clock):
    codes.start()
    clock.now = 1179.0
    last = codes.view()
    assert last["state"] == "showing" and last["ends_in"] == 1
    clock.now = 1180.0
    assert codes.view()["state"] == "expired" and "payload" not in codes.view()
    assert codes.ask(code_of(last), "100.98.12.7") == ("code_expired", None)


def test_wrong_codes_never_end_the_pairing(codes):
    state = codes.start()
    for wrong in ["x" * 22] * 10 + [None, 42, ""]:  # any private-network peer can send wrong codes
        assert codes.ask(wrong, "100.98.12.7") == ("bad_code", None)
    assert codes.ask(code_of(state), "100.98.12.7")[0] == "waiting"


# ---------------------------------------------------------------------------------------------- the request

def test_a_request_freezes_the_pairing_and_spends_its_code(codes, clock):
    first = codes.start()
    clock.now = 1030.0
    second = codes.view()
    result, r = codes.ask(code_of(second), "100.98.12.9")
    assert result == "waiting" and len(r.ticket) == 22 and len(r.number) == 6 and r.number.isdigit()
    view = codes.view()
    assert view["state"] == "confirming" and "payload" not in view
    assert view["request"] == {"ref": r.ref, "number": r.number, "peer": "100.98.12.9", "expires_in": 120}
    for code in (code_of(second), code_of(first)):  # a photographed QR code is useless from now on
        assert codes.ask(code, "100.98.12.7") == ("code_used", None)
    assert codes.ask("x" * 22, "100.98.12.7") == ("bad_code", None)
    clock.now += 60
    assert codes.view()["state"] == "confirming"  # no new code while the owner answers


def test_each_request_gets_its_own_number_and_ticket(codes):
    seen = set()
    for _ in range(20):
        _, r = codes.ask(code_of(codes.start()), "100.98.12.7")
        seen.add((r.ticket, r.ref))
        assert r.ticket != r.ref and r.number.isdigit() and len(r.number) == 6
    assert len(seen) == 20


def test_no_tokens_before_the_owner_confirms_and_then_only_once(hub, codes):
    with app_client(codes) as client:
        state = codes.start()
        r = client.post("/app/pair", json={"code": code_of(state)})
        assert r.status_code == 202 and r.headers["cache-control"] == "no-store" and matches("pair.waiting", r.json())
        ticket = r.json()["pairing"]
        waiting = client.post("/app/pair/wait", json={"pairing": ticket})
        assert waiting.status_code == 202 and waiting.json() == {"state": "waiting", "expires_in": 120}
        assert matches("wait.waiting", waiting.json()) and not any(t in waiting.text for t in TOKENS)
        assert codes.confirm(codes.view()["request"]["ref"])
        assert codes.view()["state"] == "confirmed"
        paired = client.post("/app/pair/wait", json={"pairing": ticket})
        assert paired.status_code == 200 and matches("wait.paired", paired.json())
        assert paired.json() == {"url": "https://100.98.12.7:19434", "phone": "p" * 40, "app": "a" * 40}
        # A lost 200: the tokens left once, so the ticket is spent and the owner starts again.
        again = client.post("/app/pair/wait", json={"pairing": ticket})
        assert again.status_code == 404 and again.json() == {"error": "unknown"} and matches("wait.error", again.json())
    assert codes.view()["state"] == "paired"
    lines = [e for e in hub.metrics.recent if e["ev"] in ("paired", "pair_refused")]
    assert [(e["ev"], e["peer"]) for e in lines] == [("paired", "100.98.12.7")]
    assert not any(t in json.dumps(lines) for t in TOKENS)


def test_a_refused_request_gets_nothing(hub, codes):
    with app_client(codes) as client:
        state = codes.start()
        ticket = client.post("/app/pair", json={"code": code_of(state)}).json()["pairing"]
        assert codes.refuse(codes.view()["request"]["ref"])
        r = client.post("/app/pair/wait", json={"pairing": ticket})
        assert r.status_code == 403 and r.json() == {"error": "refused"} and matches("wait.error", r.json())
        r = client.post("/app/pair", json={"code": code_of(state)})
        assert r.status_code == 410 and r.json() == {"error": "code_expired"} and matches("pair.error", r.json())
    assert codes.view()["state"] == "refused"
    assert [e["why"] for e in hub.metrics.recent if e["ev"] == "pair_refused"] == ["refused"]
    assert not any(e["ev"] == "paired" for e in hub.metrics.recent)


def test_a_second_scan_gets_code_used(codes):
    with app_client(codes) as client:
        state = codes.start()
        assert client.post("/app/pair", json={"code": code_of(state)}).status_code == 202
        r = client.post("/app/pair", json={"code": code_of(state)})
        assert r.status_code == 409 and r.json() == {"error": "code_used"} and matches("pair.error", r.json())


def test_a_request_nobody_answers_ends_after_120_s(hub, codes, clock):
    with app_client(codes) as client:
        ticket = client.post("/app/pair", json={"code": code_of(codes.start())}).json()["pairing"]
        clock.now += 119
        assert client.post("/app/pair/wait", json={"pairing": ticket}).json() == {"state": "waiting", "expires_in": 1}
        clock.now += 1
        r = client.post("/app/pair/wait", json={"pairing": ticket})
        assert r.status_code == 410 and r.json() == {"error": "expired"}
    assert codes.view()["state"] == "expired" and not codes.confirm("anything")
    assert [e["why"] for e in hub.metrics.recent if e["ev"] == "pair_refused"] == ["expired"]


def test_a_confirmed_request_the_phone_never_collects_is_lost(hub, codes, clock):
    with app_client(codes) as client:
        ticket = client.post("/app/pair", json={"code": code_of(codes.start())}).json()["pairing"]
        assert codes.confirm(codes.view()["request"]["ref"])
        clock.now += paircodes.DELIVER_S
        r = client.post("/app/pair/wait", json={"pairing": ticket})
        assert r.status_code == 410 and r.json() == {"error": "expired"}
    assert codes.view()["state"] == "lost"
    assert [e["why"] for e in hub.metrics.recent if e["ev"] == "pair_refused"] == ["lost"]


def test_the_phone_can_cancel_its_request(codes):
    with app_client(codes) as client:
        ticket = client.post("/app/pair", json={"code": code_of(codes.start())}).json()["pairing"]
        r = client.post("/app/pair/cancel", json={"pairing": ticket})
        assert r.status_code == 200 and r.json() == {"state": "cancelled"} and matches("cancel.done", r.json())
        assert client.post("/app/pair/wait", json={"pairing": ticket}).status_code == 404
        r = client.post("/app/pair/cancel", json={"pairing": ticket})
        assert r.status_code == 404 and r.json() == {"error": "unknown"} and matches("cancel.error", r.json())
    assert codes.view()["state"] == "phone_cancelled" and not codes.confirm(codes.requests[-1].ref)


def test_cancelling_or_a_new_pairing_expires_a_waiting_request(hub, codes):
    with app_client(codes) as client:
        ticket = client.post("/app/pair", json={"code": code_of(codes.start())}).json()["pairing"]
        codes.start()  # another Pair the app, or zf pair
        assert client.post("/app/pair/wait", json={"pairing": ticket}).json() == {"error": "expired"}
        ticket = client.post("/app/pair", json={"code": code_of(codes.view())}).json()["pairing"]
        assert codes.cancel()["state"] == "cancelled"
        assert client.post("/app/pair/wait", json={"pairing": ticket}).json() == {"error": "expired"}
    assert [e["why"] for e in hub.metrics.recent if e["ev"] == "pair_refused"] == ["cancelled", "cancelled"]


def test_confirming_needs_the_requests_own_ref(codes):
    _, first = codes.ask(code_of(codes.start()), "100.98.12.7")
    assert not codes.confirm(first.ticket) and not codes.confirm(None) and not codes.confirm("x" * 12)
    codes.start()
    _, second = codes.ask(code_of(codes.view()), "100.98.12.7")
    assert not codes.confirm(first.ref) and not codes.refuse(first.ref)  # a stale screen never confirms a newer request
    assert codes.view()["state"] == "confirming" and codes.confirm(second.ref)


def test_the_phones_routes_refuse_large_bodies_and_odd_ones(codes):
    with app_client(codes) as client:
        for path in ("/app/pair", "/app/pair/wait", "/app/pair/cancel"):
            r = client.post(path, content=b"{" * 2000)
            assert r.status_code == 413 and r.json() == {"error": "too_large"}
        assert client.post("/app/pair", content=b"not json").json() == {"error": "bad_code"}
        assert client.post("/app/pair", json={"code": "x" * 22}).json() == {"error": "code_expired"}  # none open
        codes.start()
        assert client.post("/app/pair", json={"code": "x" * 22}).status_code == 401
        assert client.post("/app/pair/wait", content=b"[]").status_code == 404
        assert client.post("/app/pair/wait", json={"pairing": "x" * 22}).json() == {"error": "unknown"}


async def test_the_long_poll_answers_as_soon_as_the_owner_does(codes):
    app = Starlette(routes=pairing.app_routes(codes, CFG, hold_s=10, delay_s=0))
    transport = httpx.ASGITransport(app=app, client=PEER)
    async with httpx.AsyncClient(transport=transport, base_url="https://testserver") as client:
        ticket = (await client.post("/app/pair", json={"code": code_of(codes.start())})).json()["pairing"]
        waiting = asyncio.create_task(client.post("/app/pair/wait", json={"pairing": ticket}))
        await asyncio.sleep(0.2)
        assert not waiting.done()
        loop = asyncio.get_running_loop()
        started = loop.time()
        assert codes.confirm(codes.view()["request"]["ref"])
        r = await asyncio.wait_for(waiting, 5)
        assert r.status_code == 200 and loop.time() - started < 2


def test_nothing_secret_reaches_a_log_or_the_metrics(hub, codes, caplog):
    caplog.set_level(logging.DEBUG)
    with app_client(codes) as client:
        state = codes.start()
        r = client.post("/app/pair", json={"code": code_of(state)}).json()
        ref = codes.view()["request"]["ref"]
        client.post("/app/pair", json={"code": "y" * 22})
        codes.confirm(ref)
        client.post("/app/pair/wait", json={"pairing": r["pairing"]})
    written = caplog.text + json.dumps(list(hub.metrics.recent))
    for secret in (code_of(state), r["pairing"], r["number"], ref, *TOKENS):
        assert secret not in written


# ---------------------------------------------------------------------------------------------- the owner's screens

def _signed_in(client):
    r = client.post("/view/login", data={"token": "v" * 40}, follow_redirects=False)
    assert r.status_code == 303 and "secure" in r.headers["set-cookie"].lower()
    return {"x-flipper-bridge": "1"}


def test_the_web_view_opens_a_pairing_after_the_view_token_again(hub, codes):
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as client:
        assert client.post("/view/pair", json={"token": "v" * 40}, headers={"x-flipper-bridge": "1"}).status_code == 401
        header = _signed_in(client)
        assert client.post("/view/pair", json={"token": "v" * 40}).status_code == 403  # no custom header: cross-site
        r = client.post("/view/pair", json={"token": "wrong"}, headers=header)
        assert r.status_code == 401 and r.json() == {"error": "wrong_token"} and codes.session is None
        r = client.post("/view/pair", json={"token": "v" * 40}, headers=header)
        data = r.json()
        assert r.status_code == 200 and data["state"] == "showing" and data["qr"].startswith("data:image/svg+xml;base64,")
        assert json.loads(data["payload"])["key"] == PIN and data["fingerprint"] == VECTOR["fingerprint"]
        assert r.headers["cache-control"] == "no-store" and not any(t in r.text for t in TOKENS)
        assert client.get("/view/pair").json()["session"] == data["session"]
        page = client.get("/view").text
        assert "Pair the app" in page and "If your phone shows no number, refuse." in page and "Copy" in page


SECRET = "x-flipper-bridge-pairing"  # the header the web view sends its pairing's secret in


def _opened(client, header) -> tuple[dict, dict]:
    """A pairing opened from the web view with the view token typed again: its state and the headers with its secret."""
    r = client.post("/view/pair", json={"token": "v" * 40}, headers=header)
    assert r.status_code == 200 and len(r.json()["secret"]) >= 32
    return r.json(), {**header, SECRET: r.json()["secret"]}


def _with_cookie_only(hub, codes, client):
    """Another browser with a copy of the view cookie, but not the view token nor the pairing's secret."""
    other = TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver")
    other.cookies.update(client.cookies)
    return other


def test_the_web_view_confirms_only_with_its_secret_its_header_and_the_ref(hub, codes):
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as client:
        header = _signed_in(client)
        state, mine = _opened(client, header)
        assert client.get("/view/pair", headers=mine).json()["payload"] == state["payload"]
        assert client.post("/app/pair", json={"code": code_of(state)}).status_code == 202
        for _ in range(3):  # a poll never confirms anything
            polled = client.get("/view/pair", headers=mine).json()
            assert polled["state"] == "confirming" and "qr" not in polled and "payload" not in polled
        ref = polled["request"]["ref"]
        assert client.post("/view/pair/confirm", json={"ref": ref}, headers={SECRET: mine[SECRET]}).status_code == 403
        assert client.post("/view/pair/confirm", json={"ref": "x" * 12}, headers=mine).status_code == 409
        r = client.post("/view/pair/confirm", json={"ref": ref}, headers=mine)
        assert r.status_code == 200 and r.json()["done"] and r.json()["state"] == "confirmed"
        assert r.json()["confirmed_here"] is True
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as stranger:
        assert stranger.post("/view/pair/refuse", json={"ref": ref}, headers=mine).status_code == 401


def test_the_view_cookie_alone_sees_only_the_state_and_acts_on_nothing(hub, codes):
    """a copied cookie, without the view token, neither reads the code or the request nor answers or cancels."""
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as client:
        header = _signed_in(client)
        state, mine = _opened(client, header)
        with _with_cookie_only(hub, codes, client) as thief:
            seen = thief.get("/view/pair", headers={**header, SECRET: "x" * 43}).json()
            assert seen["state"] == "showing" and seen["session"] == state["session"]
            assert not {"payload", "qr", "request", "secret"} & set(seen) and code_of(state) not in json.dumps(seen)
            assert client.post("/app/pair", json={"code": code_of(state)}).status_code == 202
            seen = thief.get("/view/pair").json()
            ref, number = codes.view()["request"]["ref"], codes.view()["request"]["number"]
            assert seen["state"] == "confirming" and "request" not in seen
            assert ref not in json.dumps(seen) and number not in json.dumps(seen)
            for path, body in (("/view/pair/confirm", {"ref": ref}), ("/view/pair/refuse", {"ref": ref}),
                               ("/view/pair/cancel", None)):
                for headers in (header, {**header, SECRET: "x" * 43}, {**header, SECRET: ""}):
                    r = thief.post(path, json=body, headers=headers)
                    assert r.status_code == 403 and r.json() == {"error": "not_this_pairing"}, (path, headers)
        assert codes.view()["state"] == "confirming"  # nothing changed
        assert client.post("/view/pair/confirm", json={"ref": ref}, headers=mine).status_code == 200


def test_a_pairing_fb_pair_opened_shows_only_its_state_in_the_web_view(hub, codes):
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as client:
        header = _signed_in(client)
        _, old = _opened(client, header)
        opened = codes.start()  # zf pair, on the loopback listener: it replaces the web view's pairing
        seen = client.get("/view/pair", headers=old).json()
        assert seen["state"] == "showing" and seen["session"] == opened["session"] and "payload" not in seen
        codes.ask(code_of(opened), "100.98.12.7")
        ref = codes.view()["request"]["ref"]
        assert "request" not in client.get("/view/pair", headers=old).json()
        for path in ("/view/pair/confirm", "/view/pair/refuse"):
            assert client.post(path, json={"ref": ref}, headers=old).status_code == 403
        assert client.post("/view/pair/cancel", headers=old).status_code == 403
        assert codes.view()["state"] == "confirming"


def test_a_web_pairing_paired_without_this_screens_confirmation_says_rotate_the_tokens(hub, codes):
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as client:
        header = _signed_in(client)
        state, mine = _opened(client, header)
        ticket = client.post("/app/pair", json={"code": code_of(state)}).json()["pairing"]
        assert codes.confirm(codes.view()["request"]["ref"])  # not this screen: zf pair, or someone else
        assert client.get("/view/pair", headers=mine).json()["confirmed_here"] is False
        assert client.post("/app/pair/wait", json={"pairing": ticket}).status_code == 200
        seen = client.get("/view/pair", headers=mine).json()
        assert seen["state"] == "paired" and seen["confirmed_here"] is False
        page = client.get("/view").text
        assert "not confirmed on this screen" in page and "zf rotate-tokens" in page


def test_the_pairing_secret_reaches_no_log(hub, codes, caplog):
    caplog.set_level(logging.DEBUG)
    with TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as client:
        header = _signed_in(client)
        _, mine = _opened(client, header)
        client.get("/view/pair", headers=mine)
        client.post("/view/pair/cancel", headers=mine)
    assert mine[SECRET] not in caplog.text + json.dumps(list(hub.metrics.recent))


def test_zf_pairs_routes_take_the_app_token_never_the_mcp_token(codes, hub):
    with TestClient(mcp_app(hub, CFG, codes=codes), base_url="http://127.0.0.1") as client:
        for token in ("m" * 40, "v" * 40, "p" * 40, ""):
            for method, path in (("POST", "/pair"), ("GET", "/pair"), ("POST", "/pair/confirm"),
                                 ("POST", "/pair/refuse"), ("POST", "/pair/cancel")):
                assert client.request(method, path, headers={"Authorization": f"Bearer {token}"}).status_code == 401
        assert codes.session is None
        r = client.post("/pair", headers=APP)
        assert r.status_code == 200 and json.loads(r.json()["payload"]) == {
            "fb": 3, "url": "https://100.98.12.7:19434", "code": code_of(r.json()), "key": PIN}
        codes.ask(code_of(r.json()), "100.98.12.7")
        ref = client.get("/pair", headers=APP).json()["request"]["ref"]
        assert client.post("/pair/confirm", headers=APP, json={"ref": "nope"}).status_code == 409
        assert client.post("/pair/refuse", headers=APP, json={"ref": ref}).json()["state"] == "refused"
        client.post("/pair", headers=APP)
        assert client.post("/pair/cancel", headers=APP).json()["state"] == "cancelled"


def test_a_code_from_fb_pair_works_at_the_public_listener(hub, codes):
    """Both listeners take the one store they are given: a code shown by zf pair works at /app/pair."""
    with TestClient(mcp_app(hub, CFG, codes=codes), base_url="http://127.0.0.1") as local, \
            TestClient(public_app(hub, CFG, codes=codes), client=PEER, base_url="https://testserver") as public:
        code = code_of(local.post("/pair", headers=APP).json())
        assert public.post("/app/pair", json={"code": code}).status_code == 202
        assert local.get("/pair", headers=APP).json()["state"] == "confirming"
    with pytest.raises(TypeError):
        public_app(hub, CFG)  # never a store of its own


# ---------------------------------------------------------------------------------------------- zf pair

def _fb_pair(folder, monkeypatch, answers, *, stdin_tty=True, answer=True):
    from bridge import config as cfg
    path = folder / "config.json"
    c = cfg.create(path, "100.98.12.7", 9434, 9435, folder)
    calls = []

    def local(_c, method, route, body=None, timeout=5.0):
        calls.append((method, route, body))
        return next(answers)
    monkeypatch.setattr(cli, "_local", local)
    monkeypatch.setattr(cli.time, "sleep", lambda s: None)
    monkeypatch.setattr(cli.sys.stdin, "isatty", lambda: stdin_tty, raising=False)
    monkeypatch.setattr(cli, "_answer_within", lambda question, seconds: answer)
    return cli.main(["--config", str(path), "pair"]), calls, c


def _showing(seq=1):
    p = paircodes.PairCodes("https://100.98.12.7:9434", PIN)
    return {"state": "showing", "session": "s1", "seq": seq, "rotates_in": 30, "ends_in": 180,
            "payload": p.payload(f"Qm9ndXMtcGFpci1jb2Rl{seq:02d}")}


ASKING = {"state": "confirming", "session": "s1",
          "request": {"ref": "r" * 12, "number": "482913", "peer": "100.98.12.9", "expires_in": 120}}


def test_zf_pair_shows_each_code_and_confirms_the_number(tmp_path, monkeypatch, capsys):
    answers = iter([_showing(1), _showing(1), _showing(2), ASKING, {"done": True, "state": "confirmed"},
                    {"state": "confirmed", "session": "s1"}, {"state": "paired", "session": "s1", "paired_at": 1100.0}])
    code, calls, c = _fb_pair(tmp_path, monkeypatch, answers)
    out = capsys.readouterr().out
    assert code == 0 and ("POST", "/pair/confirm", {"ref": "r" * 12}) in calls
    assert out.count("█") > 10 and _showing(2)["payload"] in out and VECTOR["fingerprint"] in out
    assert "482 913" in out and "If your phone shows no number, refuse." in out and "Paired at" in out
    assert not any(t in out for t in (c.phone_token, c.app_token, c.mcp_token, c.view_token))


def test_zf_pair_refuses_when_the_owner_says_no_or_cannot_answer(tmp_path, monkeypatch, capsys):
    answers = iter([_showing(), ASKING, {"done": True}, {"state": "refused", "session": "s1"}])
    code, calls, _ = _fb_pair(tmp_path, monkeypatch, answers, answer=False)
    assert code == 1 and ("POST", "/pair/refuse", {"ref": "r" * 12}) in calls
    assert "Refused: nothing was sent" in capsys.readouterr().out
    (tmp_path / "b").mkdir()
    answers = iter([_showing(), ASKING, {"done": True}])
    code, calls, _ = _fb_pair(tmp_path / "b", monkeypatch, answers, stdin_tty=False)
    assert code == 1 and calls[-1] == ("POST", "/pair/refuse", {"ref": "r" * 12})
    assert "No terminal to confirm in: refusing." in capsys.readouterr().out


def test_zf_pair_says_when_the_server_is_down(tmp_path, monkeypatch, capsys):
    from bridge import config as cfg
    path = tmp_path / "config.json"
    cfg.create(path, "100.98.12.7", 9434, 9435, tmp_path)

    def down(*a, **k):
        raise urllib.error.URLError("refused")
    monkeypatch.setattr(cli, "_local", down)
    assert cli.main(["--config", str(path), "pair"]) == 1
    assert "not running" in capsys.readouterr().err
