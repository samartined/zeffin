# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Builds a release's notes: the one builder the Release APK workflow and android/scripts/release-apk.sh both use.

    python3 -I android/scripts/release-notes.py --check --version 1.0.0 --kind final
    python3 -I android/scripts/release-notes.py --version 1.0.0 --kind rc --built-by actions \\
        --branch release/1.0.0 --commit <sha> --apk zeffin-v1.0.0-rc.2.apk \\
        --apk-sha256 <hex> --cert-sha256 <hex> --run-url <url>

It prints, in this order:
  1. docs/release-notes/<version>.md, what the version brings, written by the maintainer on the release branch;
  2. for a candidate, the paragraph that says it is one;
  3. "## The APK": the commit, how to install it, the APK's and the certificate's SHA-256, the key.

A final release without its file fails, naming the file, and --check makes that check before anything is built or
tagged. A candidate without it gets one line saying it is not written yet; --check prints a warning (a GitHub
Actions warning when GITHUB_ACTIONS is true). Standard library only (docs/android-app.md, "Publish a version").
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NOTES_DIR = ROOT / "docs" / "release-notes"
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def notes_path(notes_dir: Path, version: str) -> Path:
    return notes_dir / f"{version}.md"


def shown(version: str) -> str:
    """The file's path as the notes and the messages name it."""
    return f"docs/release-notes/{version}.md"


def whats_new(notes_dir: Path, version: str, kind: str) -> str:
    path = notes_path(notes_dir, version)
    if path.is_file():
        return path.read_text(encoding="utf-8").strip() + "\n"
    if kind == "final":
        raise SystemExit(f"release-notes: {shown(version)} is missing: a release needs its \"What's new\". "
                         f"Write it on the release branch before releasing (CLAUDE.md, \"Branches and versions\").")
    return f"What's new: not written yet ({shown(version)})\n"


def rc_text(branch: str, version: str) -> str:
    return (f"**Release candidate** from `{branch}`, to try before the release: it is built and signed like it, "
            f"installs over the released app, and the final `apk-v{version}` installs over it (same version code). "
            f"Going back to an earlier version means uninstalling first.")


def apk_part(a: argparse.Namespace) -> str:
    built = "built on GitHub Actions" if a.built_by == "actions" else "built with `android/scripts/release-apk.sh`"
    if a.key == "stable":
        key = "Signed with the repository's stable key: later versions install on top."
    else:
        key = "Signed with a one-off key. To install a later version, uninstall the app first and pair it again."
    last = "Release build, not debuggable."
    if a.run_url:
        last += f" Workflow: `.github/workflows/release-apk.yml`, run {a.run_url}"
    return (
        "## The APK\n\n"
        f"**Zeffin** Android app, {built} from `android/` of this repository, commit `{a.commit}`. Needs "
        "Android 14 or later and the zeffin server. Details in `docs/android-app.md`; third-party licenses "
        "in the app (About).\n\n"
        f"**Install:** download `{a.apk}` from the phone's browser, open it and allow installing apps from this "
        "source when Android asks.\n\n"
        f"- APK SHA-256: `{a.apk_sha256}`\n"
        f"- Signing certificate SHA-256: `{a.cert_sha256}`\n"
        f"- {key}\n"
        f"- {last}\n"
    )


def build(a: argparse.Namespace) -> str:
    parts = [whats_new(a.notes_dir, a.version, a.kind)]
    if a.kind == "rc":
        parts.append(rc_text(a.branch, a.version) + "\n")
    parts.append(apk_part(a))
    return "\n".join(parts)


def check(a: argparse.Namespace) -> None:
    if notes_path(a.notes_dir, a.version).is_file():
        print(f"release-notes: {shown(a.version)} is there")
        return
    whats_new(a.notes_dir, a.version, a.kind)  # a final release fails here
    prefix = "::warning::" if os.environ.get("GITHUB_ACTIONS") == "true" else "warning: "
    print(f"{prefix}{shown(a.version)} is not written yet: the candidate's notes say so")


def parse(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Builds a release's notes (see the module's docstring).")
    p.add_argument("--version", required=True, help="the app version, X.Y.Z (fb.version)")
    p.add_argument("--kind", required=True, choices=["final", "rc"])
    p.add_argument("--check", action="store_true", help="only check the version's file, before building")
    p.add_argument("--notes-dir", type=Path, default=NOTES_DIR, help=argparse.SUPPRESS)
    p.add_argument("--built-by", choices=["actions", "script"])
    p.add_argument("--branch", help="the release branch, for a candidate")
    p.add_argument("--commit")
    p.add_argument("--apk", help="the APK's file name")
    p.add_argument("--apk-sha256")
    p.add_argument("--cert-sha256")
    p.add_argument("--key", choices=["stable", "one-off"], default="stable")
    p.add_argument("--run-url", help="the workflow run, when built on GitHub Actions")
    a = p.parse_args(argv)
    if not VERSION.match(a.version):
        p.error(f"--version {a.version!r} is not X.Y.Z")
    if not a.check:
        needed = ["built_by", "commit", "apk", "apk_sha256", "cert_sha256"] + (["branch"] if a.kind == "rc" else [])
        missing = ["--" + n.replace("_", "-") for n in needed if not getattr(a, n)]
        if missing:
            p.error("missing " + ", ".join(missing))
    return a


def main(argv: list[str]) -> None:
    a = parse(argv)
    if a.check:
        check(a)
    else:
        sys.stdout.write(build(a))


if __name__ == "__main__":
    main(sys.argv[1:])
