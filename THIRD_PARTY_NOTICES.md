# Third-party notices

## Server

`server/bridge/pb/proto/*.proto` are the Flipper Zero RPC protocol definitions, published by
Flipper Devices Inc. without a license file. They are used under their origin's terms, unchanged
except for the removal of their Java package options, and they are not covered by Zeffin's own
license; `server/bridge/pb/*_pb2.py` are generated from them. The Flipper Zero firmware, which
includes these definitions, is licensed under GPL-3.0.

The firmware profiles and app catalogs in `server/bridge/profile_data/` are written for this
project from the public sources of the official, Momentum and Unleashed firmwares (app ids, names
and where each app is found) and checked on a Flipper; no firmware code is copied into them.

The server's Python dependencies are installed from PyPI (`server/requirements.txt`) and are not
included in this repository: cryptography, mcp, Pillow, protobuf, qrcode, Starlette, Uvicorn and
websockets, each under its own license.

## Repository documents

`CODE_OF_CONDUCT.md` is the Contributor Covenant, version 2.1
(https://www.contributor-covenant.org/version/2/1/code_of_conduct.html), licensed under CC BY 4.0,
with this project's contact filled in.

## Android app

The notices for what the app includes are in `android/licenses/THIRD_PARTY_NOTICES.md`; the app
shows them in About.
