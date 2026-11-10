# Pairing: the QR code, the server's key and the confirmation

The contract between the server and the Zeffin app for pairing: what the QR code holds, how the app proves the server's key, and the exchange that hands over the tokens only after the owner confirms on the server.

Every message has a [JSON Schema](https://json-schema.org/draft/2020-12/schema) (draft 2020-12) in this folder and examples in `examples/`. The schemas are strict, the same way as [the `/app` ones](../app/README.md): a new field goes into its schema first.

| File | What |
|---|---|
| `qr.json` | What the QR code holds (version 3) |
| `pair.request.json`, `pair.waiting.json`, `pair.error.json` | `POST /app/pair` |
| `wait.request.json`, `wait.waiting.json`, `wait.paired.json`, `wait.error.json` | `POST /app/pair/wait` |
| `cancel.request.json`, `cancel.done.json`, `cancel.error.json` | `POST /app/pair/cancel` |
| `examples/<name>[.<variant>].json` | One or more examples of each |
| `key-vector.json` | The shared test vector: a fixed public key, its pin and its fingerprint |

## The server's key

- **The key:** an elliptic-curve key (P-256) that `zf` makes. It lives in the server's data folder, readable only by the owner's user, never in the repository, a log or a chat.
- **The certificate:** self-signed with that key, for TLS 1.3 on the public listener.
  - `/phone`, `/app`, `/view` and the routes below all use https or wss on the same port.
  - The loopback listener (MCP, hooks, `zf`) stays cleartext. `zf` sends a token to it only after `localpeer`'s check that the listener is its own user's.
- **The pin:** the SHA-256 of the key's SubjectPublicKeyInfo (DER), not of the certificate.
  - In the QR code it is base64url without padding: 43 characters.
  - The server renews the certificate on the same key (each year, or when `zf set-host` changes the address in its SAN), and the pin does not change.
  - Only `zf key new` changes it, and that means pairing again. By default it also changes the phone and app tokens.
- **The fingerprint:** the first 10 bytes of the pin, in upper-case hex, in 5 groups of 4, for example `27BA 42BE 3FD7 D25B 86F5`.
  - `zf status`, `zf key`, the web view's pairing and the app all show this same string.
  - A browser does not pin the key. Chrome's certificate viewer shows the key's SHA-256 under "Public key", whose first 20 hex characters are the fingerprint's (needs testing in the browser).

**The fingerprint proves the server; the number proves the request.**

## The QR code

```json
{"fb":3,"url":"https://100.98.12.7:9434","code":"Qm9ndXMtcGFpci1jb2RlMQ","key":"J7pCvj_X0luG9SgZ7rrB0TGpD72G8BVi5G3NaLsCPS8"}
```

- **It holds no token and no lasting secret:** the address, the current code and the pin.
- **The `key` comes from the running server:** it is the key its listener serves, computed once at start. `zf pair` and the web view get the whole payload from the running server.
- **The code rotates** while the pairing screen is open (the web view's Pair the app, or `zf pair`):
  - a new 128-bit code every **30 s**, with a countdown;
  - the previous code keeps working for **5 s**;
  - the pairing stops after **3 minutes**, and the owner starts again.
- **The pairing text:** the same payload as text, for a phone that cannot scan the screen.
  - It is what "Paste the pairing text" reads in the app, for example from the web view open in the phone's browser, or from `zf pair` in an SSH app.
  - The web view shows it under the QR code, with Copy. `zf pair` prints it as one line under the QR code.
  - Both change with each code.
  - It is as private as the QR code, and the clipboard can be read by the keyboard app. That is why the code works once, lives about 35 s, and needs the owner's confirmation.
  - No link or intent opens pairing from a web page: any page could start one with another server.
- **The app**, for a scanned QR code and for pasted text alike:
  - accepts only `fb: 3`;
  - opens TLS to `url` and checks the server's key against `key` before it sends anything;
  - never falls back to cleartext;
  - treats a key that does not match as a hard error, with no "continue anyway": "The server's key changed. Pair again."

## The exchange

Every request and reply is JSON, with `cache-control: no-store`. A body over 1 KiB gets 413 `too_large`. Every other refusal waits half a second. Nothing here is logged: the number, the ticket, the server's `ref` and the codes never reach a log line or a metrics `ev`.

### 1. `POST /app/pair` `{"code"}`

| Status | Body | Meaning |
|---|---|---|
| 202 | `{"pairing", "number", "expires_in"}` | The code was right. The request waits for the owner, who sees the same number on the server |
| 401 | `{"error":"bad_code"}` | Not a code of this pairing, or a body that is not `{"code": "..."}` |
| 409 | `{"error":"code_used"}` | Another request is already waiting with this pairing, or it has already paired |
| 410 | `{"error":"code_expired"}` | The code rotated out more than 5 s ago, or no pairing is open |

- **`pairing`** is the request's ticket: 128 random bits that only this phone has.
- **`number`** is six random digits, made by the server for this request only and sent only to it, over TLS. The app shows it as `482 913`.
- **`expires_in`** is the seconds left to confirm: 120 from the scan.

**A request that waits freezes the pairing.**
- The code stops rotating and is spent: from then on, every scan of this pairing gets 409, so a photographed QR code is useless.
- The server shows one request at a time: the phone's address, its number and Confirm / Refuse.
- Someone else who scanned the QR code gets their own number, over their own connection. They cannot read the owner's, so the two screens show the same number only for the owner's own request.

### 2. `POST /app/pair/wait` `{"pairing"}`

A long poll: the server answers as soon as the owner does, or after **20 s** at most. The app's read timeout is 30 s.

| Status | Body | Meaning |
|---|---|---|
| 200 | `{"url", "phone", "app"}` | The owner confirmed: the address and the two tokens. Sent once: the ticket is spent |
| 202 | `{"state":"waiting", "expires_in"}` | Nobody answered yet: call again |
| 403 | `{"error":"refused"}` | The owner refused |
| 410 | `{"error":"expired"}` | 120 s passed, the owner cancelled the pairing, or a new pairing started |
| 404 | `{"error":"unknown"}` | Not a ticket, or the tokens were already given |

- **A lost 200:** if the connection drops after the server sent the tokens, the next call gets 404. The pairing is lost, and the owner starts again.
- **A request nobody answers** (the phone died) ends after 120 s.

### 3. `POST /app/pair/cancel` `{"pairing"}`

| Status | Body | Meaning |
|---|---|---|
| 200 | `{"state":"cancelled"}` | Cancelled. The server tells the owner "The phone cancelled" |
| 404 | `{"error":"unknown"}` | Not a waiting ticket |

### After a refusal, an expiry or a cancel

- **The pairing ends:** the owner starts a new one, with a new code.
- **The app does not repeat `POST /app/pair`.** A repeated one gets 409, like any second scan.

## What the owner is told

The server's screens and the docs say this:
- **The QR code is as trustworthy as the screen that shows it.** `zf pair` in the server's terminal, local or over SSH, is the most reliable source.
- **Keep the QR code to yourself:** no photo, no screenshot, no sharing. Cancel it when unused.
- **Confirm only the number your phone shows.** If your phone shows no number, refuse.
- **"Code already used" on the phone (409 `code_used`) means another phone got there first.** Refuse that request on the server: nothing was sent. If you already confirmed it, run `zf rotate-tokens`. Then pair again where nobody can see the screen.
  - With the confirmation, a request the owner refused got nothing.
- **Check the phone connections in the web view** ("App paired with a code"): an unknown pairing is an alarm.

## The shared test vector

`key-vector.json` holds a fixed P-256 public key (its private half was never kept), with:
- `spki_der_hex`: its SubjectPublicKeyInfo in DER;
- `public_key_pem`: the same in PEM;
- `pin`: the QR's `key` value;
- `pin_sha256_hex` and `pin_standard_base64`: the same hash in hex and in standard base64, as curl's `--pinnedpubkey sha256//…` takes it;
- `fingerprint`: the short form above.

Both sides' tests compute the pin and the fingerprint from the key and compare them with this file.

## How it is checked

- **Server:**
  - `server/tests/test_pairing_contract.py`: every schema is valid, every schema has an example and every example matches its schema, and the vector agrees with itself;
  - `server/tests/test_tlskey.py`: the server's pin and fingerprint functions give the vector's values;
  - `server/tests/test_pairing.py`: the server's replies match these schemas.
- **App:** A1's tests parse the QR example, the replies and the vector.
