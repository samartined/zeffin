# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Live view for the operator: the Flipper screen in real time, the agent's actions, and the kill switch.

Served on the public listener under /view. Authentication: the view token is exchanged once
for an HttpOnly, SameSite=Strict cookie that holds an HMAC of the token (never the token itself).
State-changing POSTs also require a custom header, which cross-site forms cannot send.

Pair the app (bridge/paircodes.py, docs/protocol/pairing): POST /view/pair with the view token typed again opens a
pairing and shows its QR code and pairing text, whose code changes every 30 s. It also returns a random secret for that
pairing, which the page keeps in memory only and sends back in the PAIR_SECRET_HEADER header: the cookie alone
is a fixed HMAC of the view token, and must not lead to the app's tokens.
- GET /view/pair is the pairing's state; only with its secret does it hold the current QR code and text, or the phone's
  request to confirm (its ref and number). A pairing zf pair opened is shown as its state only.
- POST /view/pair/confirm and /view/pair/refuse {"ref"} answer that request, and POST /view/pair/cancel ends the
  pairing, only with its secret. A poll never confirms anything.
- When the web view's pairing was confirmed elsewhere (zf pair, or anyone else), its state says confirmed_here: false,
  and the page tells the owner to run zf rotate-tokens.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import secrets
import time
from urllib.parse import parse_qs

from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse, Response, StreamingResponse
from starlette.routing import Route

from . import paircodes, tlskey
from .config import Config
from .events import VIEW_PHONE_EVENTS
from .hub import Hub
from .peers import VIEW
from .screen import png
from .wire import GATE_ACTIONS

COOKIE = "fb_view"
CSRF_HEADER = "x-flipper-bridge"
PAIR_SECRET_HEADER = "x-flipper-bridge-pairing"
MAX_FPS = 10


def _cookie_value(view_token: str) -> str:
    return hmac.new(view_token.encode(), b"flipper-bridge view session v1", hashlib.sha256).hexdigest()


def routes(hub: Hub, config: Config, codes: paircodes.PairCodes) -> list[Route]:
    view_token = config.view_token
    expected = _cookie_value(view_token)

    def authed(request: Request) -> bool:
        ok = hmac.compare_digest(request.cookies.get(COOKIE, ""), expected)
        if ok:
            hub.peers.seen(_host(request), VIEW)
        return ok

    async def page(request: Request) -> Response:
        if not authed(request):
            return HTMLResponse(LOGIN_HTML, headers=_no_cache())
        return HTMLResponse(VIEW_HTML, headers=_no_cache())

    async def login(request: Request) -> Response:
        body = await request.body()
        if len(body) > 4096:
            return Response("Too large", status_code=413)
        token = parse_qs(body.decode("utf-8", "replace")).get("token", [""])[0].strip()
        if not hmac.compare_digest(token.encode(), view_token.encode()):
            await asyncio.sleep(1.0)  # slow down guessing
            return HTMLResponse(LOGIN_HTML.replace("<!--error-->", "<p class=err>Wrong token</p>"),
                                status_code=401, headers=_no_cache())
        hub.peers.seen(_host(request), VIEW)
        response = RedirectResponse("/view", status_code=303)
        response.set_cookie(COOKIE, expected, max_age=30 * 86400, path="/view", httponly=True, samesite="strict",
                            secure=True)
        return response

    async def logout(request: Request) -> Response:
        response = RedirectResponse("/view", status_code=303)
        response.delete_cookie(COOKIE, path="/view")
        return response

    def guard_post(request: Request) -> Response | None:
        if not authed(request):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        if request.headers.get(CSRF_HEADER) != "1":
            return JSONResponse({"error": "missing header"}, status_code=403)
        return None

    async def set_gate(request: Request) -> Response:
        if (denied := guard_post(request)) is not None:
            return denied
        action = request.path_params["action"]
        if action not in GATE_ACTIONS:
            return JSONResponse({"error": "unknown action"}, status_code=404)
        return JSONResponse(await hub.operator(action, by=f"view {request.client.host if request.client else '?'}"))

    fingerprint = tlskey.fingerprint_of(codes.pin) if codes.pin else ""
    # The pairing this web view opened: its session, its secret (never logged) and whether this screen confirmed it.
    opened: dict = {"session": None, "secret": "", "confirmed": False}

    def holds_secret(request: Request, state: dict) -> bool:
        """Whether the request carries the secret of the pairing open now, and the web view opened it."""
        given = request.headers.get(PAIR_SECRET_HEADER, "")
        return (opened["session"] is not None and state.get("session") == opened["session"] and bool(opened["secret"])
                and hmac.compare_digest(given.encode(), opened["secret"].encode()))

    def pairing(state: dict, mine: bool) -> dict:
        """The pairing's state for the dialog. Its code, QR code and request only for the holder of its secret."""
        if not mine:
            return {"state": state["state"], "session": state.get("session"), "fingerprint": fingerprint}
        out = {**state, "fingerprint": fingerprint}
        if "payload" in state:
            out["qr"] = paircodes.qr_svg(state["payload"])
        if state["state"] in (paircodes.CONFIRMED, paircodes.PAIRED):
            out["confirmed_here"] = opened["confirmed"]
        return out

    def not_this_pairing() -> JSONResponse:
        return JSONResponse({"error": "not_this_pairing"}, status_code=403, headers=_no_cache())

    async def pair(request: Request) -> Response:
        """Opens a pairing after the view token is typed again: its code leads to every token."""
        if (denied := guard_post(request)) is not None:
            return denied
        body = await request.body()
        if len(body) > 4096:
            return JSONResponse({"error": "too large"}, status_code=413)
        try:
            token = json.loads(body).get("token")
        except (ValueError, AttributeError):
            token = None
        if not isinstance(token, str) or not hmac.compare_digest(token.strip().encode(), view_token.encode()):
            await asyncio.sleep(1.0)  # slow down guessing
            return JSONResponse({"error": "wrong_token"}, status_code=401, headers=_no_cache())
        state = codes.start()
        opened.update(session=state["session"], secret=secrets.token_urlsafe(32), confirmed=False)
        return JSONResponse({**pairing(state, True), "secret": opened["secret"]}, headers=_no_cache())

    async def pair_state(request: Request) -> Response:
        if not authed(request):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        state = codes.view()
        return JSONResponse(pairing(state, holds_secret(request, state)), headers=_no_cache())

    async def pair_cancel(request: Request) -> Response:
        if (denied := guard_post(request)) is not None:
            return denied
        if not holds_secret(request, codes.view()):
            return not_this_pairing()
        return JSONResponse(pairing(codes.cancel(), True), headers=_no_cache())

    def pair_answer(method, confirming: bool):
        async def endpoint(request: Request) -> Response:
            if (denied := guard_post(request)) is not None:
                return denied
            body = await request.body()
            if len(body) > 4096:
                return JSONResponse({"error": "too large"}, status_code=413)
            try:
                ref = json.loads(body).get("ref")
            except (ValueError, AttributeError):
                ref = None
            if not holds_secret(request, codes.view()):
                return not_this_pairing()
            done = method(ref)
            if done and confirming:
                opened["confirmed"] = True
            return JSONResponse({"done": done, **pairing(codes.view(), True)}, status_code=200 if done else 409,
                                headers=_no_cache())
        return endpoint

    async def events(request: Request) -> Response:
        if not authed(request):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        return StreamingResponse(_event_stream(hub, _host(request)), media_type="text/event-stream",
                                 headers={**_no_cache(), "X-Accel-Buffering": "no"})

    async def snapshot(request: Request) -> Response:
        if not authed(request):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        if hub.screen.frame is None:
            return Response(status_code=404)
        scale = max(1, min(int(request.query_params.get("scale", "4")), 8))
        return Response(png(hub.screen.frame, scale), media_type="image/png", headers=_no_cache())

    return [
        Route("/view", page, methods=["GET"]),
        Route("/view/login", login, methods=["POST"]),
        Route("/view/logout", logout, methods=["POST"]),
        Route("/view/events", events, methods=["GET"]),
        Route("/view/screen.png", snapshot, methods=["GET"]),
        Route("/view/pair", pair, methods=["POST"]),
        Route("/view/pair", pair_state, methods=["GET"]),
        Route("/view/pair/cancel", pair_cancel, methods=["POST"]),
        Route("/view/pair/confirm", pair_answer(codes.confirm, True), methods=["POST"]),
        Route("/view/pair/refuse", pair_answer(codes.refuse, False), methods=["POST"]),
        Route("/view/{action}", set_gate, methods=["POST"]),
    ]


def _host(request: Request) -> str | None:
    return request.client.host if request.client else None


def _no_cache() -> dict[str, str]:
    return {"Cache-Control": "no-store", "X-Frame-Options": "DENY", "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; "
                                       "script-src 'unsafe-inline'; frame-ancestors 'none'"}


def _sse(event: str, data) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False, default=str)}\n\n".encode()


async def _event_stream(hub: Hub, host: str | None = None):
    """The web view's live stream: while it is open, the browser's device counts as connected (peers.VIEW)."""
    hub.peers.open(host, VIEW)
    sub = hub.screen.subscribe()
    gate_q = hub.gate.listen()
    audit_q = hub.audit.listen()
    metrics_q = hub.metrics.listen()
    last_frame_sent = 0.0
    waits: dict[str, asyncio.Task] = {}
    try:
        yield _sse("status", hub.status())
        yield _sse("history", list(hub.audit.recent)[-50:])
        yield _sse("phone_history", [e for e in hub.metrics.recent if e.get("ev") in VIEW_PHONE_EVENTS][-15:])
        while True:
            for name, queue in (("frame", sub), ("gate", gate_q), ("audit", audit_q), ("metrics", metrics_q)):
                if name not in waits:
                    waits[name] = asyncio.create_task(queue.get())
            done, _ = await asyncio.wait(waits.values(), timeout=10, return_when=asyncio.FIRST_COMPLETED)
            if not done:
                yield _sse("status", hub.status())
                continue
            for name in [n for n, t in waits.items() if t in done]:
                task = waits.pop(name)
                item = task.result()
                if name == "frame":
                    wait = 1 / MAX_FPS - (time.monotonic() - last_frame_sent)
                    if wait > 0:
                        await asyncio.sleep(wait)
                    frame = hub.screen.frame
                    if frame is not None:
                        last_frame_sent = time.monotonic()
                        yield _sse("frame", {"seq": frame.seq, "stale": hub.screen.stale,
                                             "png": base64.b64encode(png(frame, 4)).decode()})
                    yield _sse("status", hub.status())
                elif name == "gate":
                    yield _sse("status", hub.status())
                elif name == "metrics":
                    if item.get("ev") in VIEW_PHONE_EVENTS:
                        yield _sse("phone", item)
                        yield _sse("status", hub.status())
                else:
                    yield _sse("audit", item)
    finally:
        for task in waits.values():
            task.cancel()
        hub.screen.unsubscribe(sub)
        hub.gate.unlisten(gate_q)
        hub.audit.unlisten(audit_q)
        hub.metrics.unlisten(metrics_q)
        hub.peers.close(host, VIEW)


LOGIN_HTML = """<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>Zeffin</title>
<style>body{font:16px system-ui;background:#111;color:#eee;display:grid;place-items:center;min-height:100vh;margin:0}
form{display:flex;flex-direction:column;gap:12px;width:min(360px,90vw)}input,button{font:inherit;padding:10px;border-radius:8px;border:1px solid #555;background:#222;color:#eee}
button{background:#f60;border:0;color:#000;font-weight:600}.err{color:#f66}</style></head>
<body><form method=post action=/view/login><h2>Zeffin</h2><!--error-->
<input name=token type=password placeholder="View token" autocomplete=current-password autofocus>
<button>Sign in</button></form></body></html>"""

VIEW_HTML = """<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>Zeffin</title>
<style>
:root{--bg:#111;--fg:#eee;--mut:#999;--card:#1b1b1b;--line:#333}
body{font:15px system-ui;background:var(--bg);color:var(--fg);margin:0;padding:16px}
main{max-width:900px;margin:auto;display:grid;gap:16px}
.screen{background:#f8a13c;border-radius:12px;padding:12px;display:grid;place-items:center}
.screen img{width:100%;max-width:768px;image-rendering:pixelated;display:block}
.stale img{opacity:.35}
.bar{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.pill{padding:4px 10px;border-radius:99px;background:var(--card);border:1px solid var(--line)}
.ok{color:#6d6}.bad{color:#f66}.warn{color:#fc6}
button{font:inherit;padding:10px 16px;border-radius:8px;border:0;font-weight:600;cursor:pointer}
#pause{background:#fc6}#resume{background:#6d6}#stop{background:#f44;color:#fff}
ol{list-style:none;margin:0;padding:0;font:13px ui-monospace,monospace;max-height:35vh;overflow:auto;
background:var(--card);border:1px solid var(--line);border-radius:8px}
li{padding:6px 10px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}
li .t{color:var(--mut)}li.w{border-left:3px solid #fc6}li.e{border-left:3px solid #f66}
[hidden]{display:none!important}
.scrim{position:fixed;inset:0;background:rgba(0,0,0,.65)}
.dialog{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);width:min(440px,90vw);background:var(--card);
border:1px solid var(--line);border-radius:12px;padding:20px}.dialog>div{display:grid;gap:12px}.dialog h3{margin:0}
.dialog p{margin:0;color:#bbb;line-height:1.45}.dialog .mut{color:var(--mut);font-size:14px}.err{color:#f66!important}
.dialog input{font:inherit;padding:10px;border-radius:8px;border:1px solid #555;background:#222;color:var(--fg)}
.row{display:flex;gap:8px;justify-content:flex-end}.go{background:#f60;color:#000}.no{background:#333;color:var(--fg)}
.qr{background:#fff;border-radius:8px;padding:8px;justify-self:center}.qr img{width:260px;height:260px;display:block}
.text{display:flex;gap:8px}.text input{flex:1;min-width:0;font:12px ui-monospace,monospace}
.fp,.num{font:600 15px ui-monospace,monospace;letter-spacing:.04em}.num{font-size:28px;color:var(--fg);text-align:center}
.dialog{max-height:92vh;overflow:auto}
</style></head><body><main>
<div class=bar><strong>Zeffin</strong>
<span id=conn class=pill>…</span><span id=gate class=pill>…</span><span id=age class=pill>…</span>
<button id=pair class=go style=margin-left:auto>Pair the app</button>
<form method=post action=/view/logout><button style="background:#333;color:#eee">Sign out</button></form></div>
<div id=screenbox class="screen stale"><img id=screen alt="Flipper screen"></div>
<div class=bar><button id=pause>Pause agent</button><button id=resume>Resume</button>
<button id=stop>Stop relay</button></div>
<h3 style="margin:0">Phone connections</h3>
<ol id=phone></ol>
<h3 style="margin:0">Agent actions</h3>
<ol id=log></ol></main>
<div id=pairbox hidden><div class=scrim></div><div class=dialog>
<div id=p-ask><h3>Pair the app</h3><p>Type the view token again. The QR code that appears pairs one phone, after you
confirm it here. Its code changes every 30 seconds, and it does not hold the tokens.</p>
<p class=mut>The QR code is as trustworthy as the screen that shows it: zf pair in the server's terminal is the most
reliable.</p>
<input id=p-token type=password placeholder="View token" autocomplete=current-password><p id=p-err class=err hidden>Wrong token</p>
<div class=row><button class=no data-close>Cancel</button><button id=p-show class=go>Show the QR code</button></div></div>
<div id=p-code hidden><h3>Pair the app</h3><div class=qr><img id=p-qr alt="Pairing QR code"></div>
<p>In the app: Settings, Server, <b>Pair with QR code</b>, and point the camera here. On the phone itself: copy the
pairing text and paste it in the app.</p>
<div class=text><input id=p-text readonly aria-label="Pairing text"><button id=p-copy class=no>Copy</button></div>
<p class=mut>New code in <span id=p-rot>30</span> s · stops in <span id=p-left>3:00</span></p>
<p class=mut>Server key: <span id=p-fp class=fp></span>. The app shows the same after it scans.</p>
<p class=mut>Keep the QR code and the text to yourself: no photo, no screenshot, no sharing. Cancel when unused.</p>
<div class=row><button id=p-cancel class=no>Cancel</button></div></div>
<div id=p-ask2 hidden><h3>Confirm the phone</h3><p>A phone (<span id=p-peer></span>) asks to pair. Does it show
this number?</p><div class=num id=p-num></div>
<p class=warn>If your phone shows no number, refuse.</p>
<p class=mut>Server key: <span id=p-fp2 class=fp></span>, the same as on the phone. The number proves the request; the key
proves the server. Answer within <span id=p-ans>2:00</span>.</p>
<div class=row><button id=p-refuse class=no>Refuse</button><button id=p-confirm class=go>Confirm</button></div></div>
<div id=p-wait hidden><h3>Confirmed</h3><p>Waiting for the phone to collect the address and the tokens…</p>
<p class="err p-elsewhere" hidden></p></div>
<div id=p-done hidden><h3 class=ok>Paired</h3><p>A phone paired at <span id=p-at></span> and got the address and the
tokens. Check the phone connections: an unknown pairing is an alarm.</p><p class="err p-elsewhere" hidden></p>
<div class=row><button class=no data-close>Close</button></div></div>
<div id=p-end hidden><h3 class=warn id=p-endt>The pairing ended</h3><p id=p-why></p>
<div class=row><button class=no data-close>Close</button><button id=p-again class=go>Start again</button></div></div>
</div></div>
<script>
const $=id=>document.getElementById(id);
function fmt(ts){return new Date(ts*1000).toLocaleTimeString()}
function item(r){const li=document.createElement('li');
 if(r.writes)li.className='w'; if(r.result&&r.result!=='ok')li.className='e';
 const t=document.createElement('span');t.className='t';t.textContent=fmt(r.ts)+' ';
 li.append(t,document.createTextNode((r.tool||'')+' '+(r.action||'')+' '+JSON.stringify(r.args||{})+' → '+(r.result||'')));
 return li}
function addLog(r){const l=$('log');l.prepend(item(r));while(l.children.length>200)l.lastChild.remove()}
const REASONS={bad_token:'token rejected',busy:'another phone is already connected',stopped:'relay stopped from this view'};
function phoneText(e){
 if(e.ev==='ws_rejected'){let t='REJECTED: '+(REASONS[e.reason]||e.reason);
  if(e.reason==='bad_token')t+=e.has_header?(e.bearer_prefix?' (length '+e.token_length+', expected '+e.expected_length+')':' (header without "Bearer")'):' (no token header)';
  return t}
 if(e.ev==='ws_open')return 'Phone connected to the server ('+e.peer+')';
 if(e.ev==='ready')return 'Flipper ready over '+e.transport;
 if(e.ev==='first_frame')return 'First screen received';
 if(e.ev==='paired')return 'App paired with a code ('+e.peer+')';
 if(e.ev==='pair_refused')return 'Pairing request got nothing: '+e.why+' ('+e.peer+')';
 if(e.ev==='stream')return 'Screen stream: '+e.status;
 if(e.ev==='close')return 'Session closed: '+(e.reason||'');
 return e.ev}
function addPhone(e){const l=$('phone');const li=document.createElement('li');
 if(e.ev==='ws_rejected'||e.ev==='close'||e.ev==='pair_refused')li.className='e';
 const t=document.createElement('span');t.className='t';t.textContent=fmt(e.ts)+' ';
 li.append(t,document.createTextNode(phoneText(e)));l.prepend(li);while(l.children.length>15)l.lastChild.remove()}
function status(s){
 $('conn').textContent=s.connected?('Connected over '+s.transport):(s.phone_attached?'Phone connecting…':'No phone');
 $('conn').className='pill '+(s.connected?'ok':'bad');
 $('gate').textContent={active:'Agent active',paused:'Agent PAUSED',stopped:'Relay STOPPED'}[s.gate]||s.gate;
 $('gate').className='pill '+(s.gate==='active'?'ok':(s.gate==='paused'?'warn':'bad'));
 $('age').textContent=s.screen.age_s==null?'no screen':('screen #'+s.screen.seq+(s.screen.stale?' (old)':''));
 $('screenbox').classList.toggle('stale',!!s.screen.stale||!s.connected)}
async function act(a){const r=await fetch('/view/'+a,{method:'POST',headers:{'x-flipper-bridge':'1'}});
 if(r.ok)status(await r.json())}
$('pause').onclick=()=>act('pause');$('resume').onclick=()=>act('resume');
$('stop').onclick=()=>{if(confirm('Close the phone session and block reconnections until Resume?'))act('stop')};
let pPoll=0,pSession=null,pSeq=0,pSecret='';  // the pairing's secret: in this page's memory only
const P_ELSEWHERE='This pairing was not confirmed on this screen. If you did not confirm it with zf pair, someone else '+
 'did: run zf rotate-tokens on the server, then pair again.';
function pHeaders(extra){return Object.assign({'x-flipper-bridge':'1','x-flipper-bridge-pairing':pSecret},extra||{})}
function pElsewhere(s){document.querySelectorAll('.p-elsewhere').forEach(e=>{e.textContent=P_ELSEWHERE;e.hidden=s.confirmed_here!==false})}
const P_SCREENS=['p-ask','p-code','p-ask2','p-wait','p-done','p-end'];
const P_ENDS={refused:['Refused','Nothing was sent. If it was not your phone, someone else saw the QR code: start again where nobody can see the screen.'],
 expired:['The pairing ended','Nobody confirmed in time, or no phone scanned within 3 minutes. Nothing was sent. Start again when the app is ready to scan.'],
 cancelled:['Cancelled','The code no longer works. Nothing was sent.'],
 phone_cancelled:['The phone cancelled','Nothing was sent. Start again when the app is ready.'],
 lost:['The pairing was lost','The phone did not collect the tokens in time. Start again.'],
 replaced:['Replaced','A newer pairing started (another Pair the app, or zf pair).']};
function mmss(t){return Math.floor(t/60)+':'+String(t%60).padStart(2,'0')}
function pShow(id){for(const s of P_SCREENS)$(s).hidden=s!==id;$('pairbox').hidden=false}
function pStop(){clearInterval(pPoll);pPoll=0;$('p-qr').removeAttribute('src');$('p-text').value=''}
function pClose(){pStop();$('pairbox').hidden=true;$('p-token').value='';pSession=null;pSecret=''}
function pAsk(){$('p-err').hidden=true;pShow('p-ask');$('p-token').focus()}
function pEnd(why){pStop();const [t,w]=P_ENDS[why]||P_ENDS.expired;$('p-endt').textContent=t;$('p-why').textContent=w;pShow('p-end')}
function pRender(s){
 if(s.session!==pSession)return pEnd('replaced');
 $('p-fp').textContent=$('p-fp2').textContent=s.fingerprint||'';
 if(s.state==='showing'){if(s.seq!==pSeq){pSeq=s.seq;$('p-qr').src=s.qr;$('p-text').value=s.payload}
  $('p-rot').textContent=s.rotates_in;$('p-left').textContent=mmss(s.ends_in);if($('p-code').hidden)pShow('p-code')}
 else if(s.state==='confirming'){$('p-qr').removeAttribute('src');$('p-text').value='';
  $('p-peer').textContent=s.request.peer;$('p-num').textContent=s.request.number.slice(0,3)+' '+s.request.number.slice(3);
  $('p-ask2').dataset.ref=s.request.ref;$('p-ans').textContent=mmss(s.request.expires_in);if($('p-ask2').hidden)pShow('p-ask2')}
 else if(s.state==='confirmed'){pElsewhere(s);pShow('p-wait')}
 else if(s.state==='paired'){pStop();pElsewhere(s);$('p-at').textContent=fmt(s.paired_at);pShow('p-done')}
 else pEnd(s.state)}
async function pCheck(){const r=await fetch('/view/pair',{headers:pHeaders()});if(!r.ok)return;pRender(await r.json())}
async function pStart(){
 const r=await fetch('/view/pair',{method:'POST',headers:{'x-flipper-bridge':'1','content-type':'application/json'},
  body:JSON.stringify({token:$('p-token').value})});
 if(!r.ok){$('p-err').textContent=r.status===401?'Wrong token':'No pairing: HTTP '+r.status;$('p-err').hidden=false;return}
 const s=await r.json();$('p-token').value='';pSession=s.session;pSecret=s.secret;pSeq=0;pRender(s);
 clearInterval(pPoll);pPoll=setInterval(pCheck,1000)}
async function pAnswer(what){const ref=$('p-ask2').dataset.ref;
 const r=await fetch('/view/pair/'+what,{method:'POST',headers:pHeaders({'content-type':'application/json'}),
  body:JSON.stringify({ref:ref})});if(r.status===200||r.status===409)pRender(await r.json());else if(r.status===403)pEnd('replaced')}
$('pair').onclick=pAsk;$('p-again').onclick=pAsk;$('p-show').onclick=pStart;
$('p-token').onkeydown=e=>{if(e.key==='Enter')pStart()};
$('p-confirm').onclick=()=>pAnswer('confirm');$('p-refuse').onclick=()=>pAnswer('refuse');
$('p-copy').onclick=async()=>{try{await navigator.clipboard.writeText($('p-text').value)}catch(e){$('p-text').select()}};
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=pClose);
$('p-cancel').onclick=async()=>{await fetch('/view/pair/cancel',{method:'POST',headers:pHeaders()});pClose()};
const es=new EventSource('/view/events');
es.addEventListener('status',e=>status(JSON.parse(e.data)));
es.addEventListener('frame',e=>{const f=JSON.parse(e.data);$('screen').src='data:image/png;base64,'+f.png});
es.addEventListener('history',e=>JSON.parse(e.data).forEach(addLog));
es.addEventListener('audit',e=>addLog(JSON.parse(e.data)));
es.addEventListener('phone_history',e=>JSON.parse(e.data).forEach(addPhone));
es.addEventListener('phone',e=>addPhone(JSON.parse(e.data)));
es.onerror=()=>{$('conn').textContent='View disconnected';$('conn').className='pill bad'};
</script></body></html>"""
