# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""android/scripts/release-notes.py, the one builder of a release's notes, with fixture files and fake hashes."""
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "android" / "scripts" / "release-notes.py"
COMMIT = "0123456789abcdef0123456789abcdef01234567"
APK_SHA = "a" * 64
CERT = "b" * 64
WHATS_NEW = "## What's new since 1.1.0\n\n### Things\n- One thing changed.\n"


def run(notes_dir: Path, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, "-I", str(SCRIPT), "--notes-dir", str(notes_dir), *args],
                          capture_output=True, text=True, env=env or {}, check=False)


def build(notes_dir: Path, version: str, kind: str, *extra: str) -> subprocess.CompletedProcess[str]:
    return run(notes_dir, "--version", version, "--kind", kind, "--commit", COMMIT,
               "--apk", f"zeffin-v{version}{'-rc.2' if kind == 'rc' else ''}.apk",
               "--apk-sha256", APK_SHA, "--cert-sha256", CERT, *extra)


def notes_dir(tmp_path: Path) -> Path:
    (tmp_path / "1.2.0.md").write_text(WHATS_NEW)
    return tmp_path


SCRIPT_APK = f"""## The APK

**Zeffin** Android app, built with `android/scripts/release-apk.sh` from `android/` of this repository, \
commit `{COMMIT}`. Needs Android 14 or later and the zeffin server. Details in `docs/android-app.md`; \
third-party licenses in the app (About).

**Install:** download `zeffin-v1.2.0.apk` from the phone's browser, open it and allow installing apps from \
this source when Android asks.

- APK SHA-256: `{APK_SHA}`
- Signing certificate SHA-256: `{CERT}`
- Signed with the repository's stable key: later versions install on top.
- Release build, not debuggable.
"""


def test_a_final_release_from_the_script(tmp_path):
    out = build(notes_dir(tmp_path), "1.2.0", "final", "--built-by", "script")
    assert out.returncode == 0, out.stderr
    assert out.stdout == WHATS_NEW + "\n" + SCRIPT_APK


RC_TEXT = ("**Release candidate** from `release/1.2.0`, to try before the release: it is built and signed like it, "
           "installs over the released app, and the final `apk-v1.2.0` installs over it (same version code). Going "
           "back to an earlier version means uninstalling first.")
RUN = "https://github.com/samartined/zeffin/actions/runs/1"


def test_a_candidate_from_the_workflow(tmp_path):
    out = build(notes_dir(tmp_path), "1.2.0", "rc", "--built-by", "actions", "--branch", "release/1.2.0",
                "--run-url", RUN)
    assert out.returncode == 0, out.stderr
    assert out.stdout.startswith(WHATS_NEW + "\n" + RC_TEXT + "\n\n## The APK\n\n")
    assert "built on GitHub Actions from `android/`" in out.stdout
    assert out.stdout.endswith(
        f"- Release build, not debuggable. Workflow: `.github/workflows/release-apk.yml`, run {RUN}\n")
    assert out.stdout.count("Release candidate") == 1


def test_a_one_off_key_is_said(tmp_path):
    out = build(notes_dir(tmp_path), "1.2.0", "final", "--built-by", "actions", "--key", "one-off", "--run-url", RUN)
    assert out.returncode == 0, out.stderr
    assert ("- Signed with a one-off key. To install a later version, uninstall the app first and pair it again.\n"
            in out.stdout)


def test_a_final_release_without_its_file_fails(tmp_path):
    out = build(tmp_path, "1.2.0", "final", "--built-by", "script")
    assert out.returncode != 0
    assert out.stdout == ""
    assert "1.2.0.md" in out.stderr and "What's new" in out.stderr


def test_the_check_fails_for_a_final_release_without_its_file(tmp_path):
    out = run(tmp_path, "--check", "--version", "1.2.0", "--kind", "final")
    assert out.returncode != 0
    assert "1.2.0.md" in out.stderr
    assert run(notes_dir(tmp_path), "--check", "--version", "1.2.0", "--kind", "final").returncode == 0


def test_a_candidate_without_its_file_says_so_and_warns(tmp_path):
    out = build(tmp_path, "1.2.0", "rc", "--built-by", "script", "--branch", "release/1.2.0")
    assert out.returncode == 0, out.stderr
    assert out.stdout.startswith(
        "What's new: not written yet (docs/release-notes/1.2.0.md)\n\n" + RC_TEXT + "\n\n## The APK\n\n")
    check = run(tmp_path, "--check", "--version", "1.2.0", "--kind", "rc", env={"GITHUB_ACTIONS": "true"})
    assert check.returncode == 0
    assert check.stdout.startswith("::warning::") and "1.2.0.md" in check.stdout


def test_a_bad_version_is_refused(tmp_path):
    out = run(notes_dir(tmp_path), "--check", "--version", "../1.2.0", "--kind", "final")
    assert out.returncode != 0


def test_the_workflow_and_the_script_both_use_the_builder():
    root = SCRIPT.parents[2]
    for caller in (root / ".github" / "workflows" / "release-apk.yml", root / "android" / "scripts" / "release-apk.sh"):
        text = caller.read_text()
        assert "android/scripts/release-notes.py --check" in text, caller
        assert "android/scripts/release-notes.py --version" in text, caller
        assert "**Install:**" not in text and "Release candidate" not in text, caller  # no notes of their own
