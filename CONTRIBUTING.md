# Contributing to Zeffin

Thank you for helping. Zeffin is a server (`server/`, Python) and an Android app (`android/`, Kotlin + Compose) that let an agent drive a Flipper Zero through the phone.

## Before you start

- **English** for code, comments, docs, commit messages, issues and pull requests. The app's texts are written in English and Spanish.
- For anything larger than a small fix, open an issue first so the change can be agreed before you write it.
- Read [docs/architecture.md](docs/architecture.md) before changing a module's structure: it says what each layer may import, and tests enforce it.
- Never put real tokens, keys, IP addresses, host names or other personal data in code, docs, screenshots or commits. Examples use fake values and the IP `100.98.12.7`.

## The checks

Run the checks for what your change touches before you open a pull request, and say in it which ones ran:

```bash
python3 -m venv .venv
.venv/bin/pip install -r server/requirements-dev.txt -e ./server
cd server && ../.venv/bin/pytest -q && ../.venv/bin/ruff check . && ../.venv/bin/mypy
cd ../android && ./gradlew testDebugUnitTest lintDebug detekt :app:assembleRelease   # JDK 21
cd .. && .venv/bin/python -I android/scripts/compare-design-captures.py
PYTHON=$PWD/.venv/bin/python android/scripts/e2e.sh
python3 -I scripts/check-links.py   # every relative link and #anchor in the Markdown files
```

- `server/`: `pytest`, `ruff`, `mypy` and `e2e.sh`.
- `android/` or `docs/design/`: the Gradle line, the captures comparison and `e2e.sh`.
- `docs/protocol/` or the workflows: all of them.
- Other docs only: the link check.

The app needs JDK 21 and the Android SDK (`android/local.properties` with `sdk.dir=…`; Gradle downloads platform 37 if you accepted the licenses). Screenshots of each screen, in English and Spanish, land in `android/app/build/outputs/roborazzi/en` and `…/es`. The tests use a simulated Flipper that speaks the same contract as the app (nothing binary before `ready`, `written` acks, frames of at most 64 KiB, bytes split into arbitrary BLE chunks).

The *Tests* workflow (`.github/workflows/ci.yml`) runs the checks for what a pull request touches, on pull requests to `dev` and `main` that are not drafts, except when a pull request changes only docs no test reads.

`ruff` and `mypy` (server) and `detekt` (app) only check: fix a finding in the code. A change on screen must match the approved design in [docs/design/](docs/design/README.md); the captures comparison fails when a screen differs by a pixel.

## Building and releasing the app

The APK is built from `android/` and published in the repository's *Releases* by GitHub Actions, or with `android/scripts/release-apk.sh` when Actions cannot run, signed with the stable release key, which is never in the repository ([docs/android-app.md](docs/android-app.md)).

## The app catalogs

`server/scripts/build_app_catalog.py` (run with `python3 -I`, standard library only) reads a local checkout of a firmware's public app sources: every `application.fam` is parsed, never run, and each app's code is searched for the capability flags. It writes `server/bridge/profile_data/apps/<origin>.json` and prints a report (apps, descriptions, our own summaries, capability counts). `--help` lists the public projects the shipped catalogs come from and an example. Short or missing descriptions take a line from `apps/<origin>.summaries.json` (written by hand), and the compiled-in apps come from `apps/<origin>.builtin.json`. To rebuild a catalog, check out the sources at a release tag (`--tag`) or a branch (`--branch` with `--reports-version`, the version its builds report: for example, a catalog that follows a `dev` branch, whose builds report a `-dev` version), run the script with the same arguments, and commit the JSON. The same checkout can be named with `zf set-firmware-sources` (the folder `--root` pointed at) for `flipper_app`'s READMEs.

## Stability metrics

The server records metrics in `metrics.jsonl` (connections, time to first frame, frames per minute, gaps, 30 s pings with their latency, the reason for each close) and `zf report HOURS` summarizes them.

## License and sign-off

Zeffin is licensed under the GNU Affero General Public License, version 3 or later ([LICENSE](LICENSE)). By contributing you agree that your contribution is licensed under the same terms.

Every commit you contribute must carry a sign-off under the [Developer Certificate of Origin 1.1](https://developercertificate.org/): you certify that you wrote the change, or otherwise have the right to submit it under the project's license. Sign off with:

```bash
git commit -s
```

which adds a line such as `Signed-off-by: Your Name <you@example.com>` with your Git identity. The sign-off also lets the maintainer offer later versions of Zeffin under other license terms, if that is ever needed, without tracking down every contributor. The maintainer's own commits carry none: their copyright is already the maintainer's.

New source files start with the SPDX header the others carry, with your own name or handle in the copyright line:

```
SPDX-License-Identifier: AGPL-3.0-or-later
SPDX-FileCopyrightText: 2026 your-name
```

## Code of conduct

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md). Security problems are reported privately, as [SECURITY.md](SECURITY.md) explains.
