# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""install.sh's logic without a real system: the script runs from a copy of the clone, in a temporary home, with
small stand-ins for python3, pip, zeffin, tailscale, systemctl, loginctl, claude and opencode on a PATH that holds
nothing else but a few basic tools."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(os.name != "posix" or shutil.which("sh") is None, reason="needs a POSIX shell")

SCRIPT = Path(__file__).resolve().parents[2] / "install.sh"
TOKEN = "FAKE-TOKEN-0123456789"
BASIC_TOOLS = ("sh", "dirname", "head", "sed", "cat")

PYTHON3 = f"""#!{sys.executable}
import os, subprocess, sys
args = sys.argv[1:]
if args[:2] == ["-m", "venv"]:
    venv = args[2]
    subprocess.run([sys.executable, "-m", "venv", "--without-pip", venv], check=True)
    with open(os.path.join(venv, "bin", "pip"), "w") as f:
        f.write(open(os.environ["FAKE_PIP"]).read())
    os.chmod(os.path.join(venv, "bin", "pip"), 0o755)
    with open(os.environ["FAKE_LOG"], "a") as f:
        f.write("venv\\n")
    sys.exit(0)
if os.environ.get("FAKE_PYTHON_VERSION") and len(args) > 1 and args[0] == "-c" and "version_info" in args[1]:
    print(os.environ["FAKE_PYTHON_VERSION"])
    sys.exit(0)
os.execv(sys.executable, [sys.executable, *args])
"""

# pip: "installs" a package named bridge into the venv and the zeffin command beside it.
PIP = f"""#!{sys.executable}
import os, sys, sysconfig
bindir = os.path.dirname(os.path.abspath(sys.argv[0]))
venv = os.path.dirname(bindir)
lib = sysconfig.get_paths(vars={{"base": venv, "platbase": venv}})["purelib"]
os.makedirs(os.path.join(lib, "bridge"), exist_ok=True)
open(os.path.join(lib, "bridge", "__init__.py"), "w").close()
zeffin = os.path.join(bindir, "zeffin")
with open(zeffin, "w") as f:
    f.write(open(os.environ["FAKE_ZEFFIN"]).read())
os.chmod(zeffin, 0o755)
with open(os.environ["FAKE_LOG"], "a") as f:
    f.write("pip " + " ".join(sys.argv[1:]) + "\\n")
"""

# zeffin: what the real CLI's commands leave behind, and the tokens zf init prints, which install.sh must hide.
ZEFFIN = f"""#!{sys.executable}
import json, os, shutil, sys
args = sys.argv[1:]
assert args[0] == "--config"
config, cmd, rest = args[1], args[2], args[3:]
with open(os.environ["FAKE_LOG"], "a") as f:
    f.write("zeffin " + " ".join([cmd, *rest]) + "\\n")
home = os.environ["HOME"]
unit = os.path.join(home, ".config", "systemd", "user", "zeffin.service")
if cmd == "init":
    os.makedirs(os.path.dirname(config), exist_ok=True)
    fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({{"host": rest[1], "phone_token": "{TOKEN}", "agent_dir": "", "claude_bin": "",
                   "opencode_bin": ""}}, f)
    print("Phone token: {TOKEN}")
elif cmd == "install-service":
    os.makedirs(os.path.dirname(unit), exist_ok=True)
    open(unit, "w").write("minimal" if "--minimal" in rest else "sandboxed")
    print("Wrote " + unit + "; service: active, restarts=0.")
elif cmd == "agent":
    agent = "opencode" if "opencode" in rest else "claude"
    if os.environ.get("FAKE_NOT_SIGNED_IN") == agent:
        print("Claude Code is not signed in. Run `claude`, type /login, and run this again.")
        sys.exit(1)
    c = json.load(open(config))
    c["agent_dir"] = os.path.join(os.path.dirname(os.path.dirname(config)), "zeffin-agent")
    os.makedirs(c["agent_dir"], exist_ok=True)  # as zf agent setup does
    c["opencode_bin" if agent == "opencode" else "claude_bin"] = shutil.which(agent)
    json.dump(c, open(config, "w"))
    print("Saved.")
elif cmd == "pair":
    print("QR code")
elif cmd == "mcp-config":
    print("Registered `flipper` for your user.")
"""

TAILSCALE = """#!/bin/sh
[ -n "${FAKE_TAILSCALE_DOWN:-}" ] && exit 1
echo 100.98.12.7
"""

SYSTEMCTL = """#!/bin/sh
case "$*" in
    "--user show-environment") [ -z "${FAKE_NO_USER_MANAGER:-}" ] ;;
    "--user is-active --quiet zeffin.service")
        unit="$HOME/.config/systemd/user/zeffin.service"  # FAKE_SANDBOX_REJECTED: only the minimal unit runs
        [ -f "$unit" ] && { [ -z "${FAKE_SANDBOX_REJECTED:-}" ] || [ "$(cat "$unit")" = minimal ]; } ;;
    *) exit 0 ;;
esac
"""

ID = """#!/bin/sh
case "$1" in
    -u) echo "${FAKE_UID:-1000}" ;;
    *) echo demo ;;
esac
"""

LOGINCTL = """#!/bin/sh
echo "Linger=${FAKE_LINGER:-no}"
"""

STUB = "#!/bin/sh\nexit 0\n"


class World:
    """A temporary home with a copy of the clone in ~/projects/zeffin, and the stand-ins on the PATH."""

    def __init__(self, tmp: Path):
        self.home = tmp / "home"
        self.base = self.home / "projects"
        self.clone = self.base / "zeffin"
        (self.clone / "server" / "bridge").mkdir(parents=True)
        shutil.copy(SCRIPT, self.clone / "install.sh")
        self.bin = tmp / "bin"
        self.bin.mkdir()
        self.fakes = tmp / "fakes"
        self.fakes.mkdir()
        self.log = tmp / "calls.log"
        self.log.touch()
        for tool in BASIC_TOOLS:
            (self.bin / tool).symlink_to(shutil.which(tool))  # type: ignore[arg-type]
        for name, body in (("python3", PYTHON3), ("git", STUB), ("tailscale", TAILSCALE),
                           ("systemctl", SYSTEMCTL), ("loginctl", LOGINCTL), ("id", ID)):
            self.tool(name, body)
        for name, body in (("pip", PIP), ("zeffin", ZEFFIN)):
            (self.fakes / name).write_text(body)
        self.env = {"HOME": str(self.home), "USER": "demo", "PATH": str(self.bin), "LANG": "C.UTF-8",
                    "FAKE_LOG": str(self.log), "FAKE_PIP": str(self.fakes / "pip"),
                    "FAKE_ZEFFIN": str(self.fakes / "zeffin")}

    def tool(self, name: str, body: str = STUB) -> None:
        path = self.bin / name
        path.write_text(body)
        path.chmod(0o755)

    def run(self, *args: str, **env: str) -> subprocess.CompletedProcess:
        out = subprocess.run(["sh", str(self.clone / "install.sh"), *args], env={**self.env, **env},
                             capture_output=True, text=True, timeout=120, stdin=subprocess.DEVNULL)
        assert TOKEN not in out.stdout + out.stderr
        return out

    def calls(self) -> list[str]:
        return self.log.read_text().splitlines()

    @property
    def config(self) -> Path:
        return self.base / "zeffin-data" / "config.json"

    @property
    def unit(self) -> Path:
        return self.home / ".config" / "systemd" / "user" / "zeffin.service"


@pytest.fixture
def world(tmp_path: Path) -> World:
    return World(tmp_path)


def test_a_fresh_run_installs_configures_and_pairs(world: World):
    world.tool("claude")
    out = world.run()
    assert out.returncode == 0, out.stdout + out.stderr
    calls = world.calls()
    assert calls[0] == "venv"
    assert calls[1].startswith("pip install -q --disable-pip-version-check -r ") and calls[1].endswith(f"-e {world.clone}/server")
    assert calls[2:] == ["zeffin init --host 100.98.12.7", "zeffin install-service", "zeffin agent setup",
                         "zeffin pair"]
    assert world.config.exists() and world.unit.read_text() == "sandboxed"
    assert "sudo loginctl enable-linger demo" in out.stdout
    assert "optional: to drive the Flipper also from an interactive claude session" in out.stdout
    assert "Pair with QR code" in out.stdout and out.stdout.rstrip().endswith("QR code")
    assert "zf is linked in ~/.local/bin, which is not on this shell's PATH yet" in out.stdout


def test_a_second_run_changes_nothing(world: World):
    world.tool("claude")
    assert world.run("--no-pair").returncode == 0
    before = (world.config.read_bytes(), world.unit.read_bytes(), world.calls())
    out = world.run("--no-pair", FAKE_LINGER="yes")
    assert out.returncode == 0, out.stdout + out.stderr
    assert (world.config.read_bytes(), world.unit.read_bytes(), world.calls()) == before
    assert out.stdout.count("already done") == 5
    assert "sudo" not in out.stdout


def test_an_agent_whose_folder_is_gone_is_set_up_again(world: World):
    """An agent is "already done" only while its folder is there: a config.json moved from an older install can name a
    folder that no longer exists (a docs review before 1.0.0)."""
    world.tool("claude")
    assert world.run("--no-pair").returncode == 0
    shutil.rmtree(world.base / "zeffin-agent")
    out = world.run("--no-pair")
    assert out.returncode == 0, out.stdout + out.stderr
    assert world.calls().count("zeffin agent setup") == 2 and "Claude Code is set up for chats." in out.stdout


def test_a_missing_requirement_stops_before_installing(world: World):
    (world.bin / "tailscale").unlink()
    out = world.run(FAKE_PYTHON_VERSION="3.10")
    assert out.returncode == 1
    assert "missing: Python 3.11 or later" in out.stdout
    assert "missing: Tailscale: install it" in out.stdout
    assert "ok: git." in out.stdout and "ok: systemd's user manager" in out.stdout
    assert "Nothing was installed" in out.stdout
    assert world.calls() == [] and not (world.clone / ".venv").exists()


def test_tailscale_not_connected_and_no_user_manager(world: World):
    out = world.run(FAKE_TAILSCALE_DOWN="1", FAKE_NO_USER_MANAGER="1")
    assert out.returncode == 1
    assert "Tailscale is installed but not connected" in out.stdout
    assert "systemd's user manager does not answer" in out.stdout
    assert world.calls() == []


@pytest.mark.parametrize("old", ["flipper-bridge-data", "flipper-agent", "unit"])
def test_an_old_install_is_reported_never_moved(world: World, old: str):
    path = world.home / ".config/systemd/user/flipper-bridge.service" if old == "unit" else world.base / old
    path.parent.mkdir(parents=True, exist_ok=True)
    path.mkdir() if old != "unit" else path.write_text("[Unit]\n")
    out = world.run()
    assert out.returncode == 1
    assert "Found an install under the old names" in out.stdout and str(path) in out.stdout
    assert "systemctl --user disable --now flipper-bridge.service" in out.stdout
    if old == "flipper-bridge-data":
        assert f'mv "{path}" "{world.base}/zeffin-data"' in out.stdout
    assert f'rm -f "{world.home}/.local/bin/fb"' in out.stdout and "pair it" in out.stdout
    assert "stays paired" not in out.stdout
    assert path.exists() and world.calls() == [] and not (world.clone / ".venv").exists()


def test_neither_agent_found(world: World):
    out = world.run("--no-pair")
    assert out.returncode == 0, out.stdout + out.stderr
    assert not any(c.startswith("zeffin agent") for c in world.calls())
    assert "no agent found on your PATH" in out.stdout
    assert "- Claude Code, with your Claude subscription" in out.stdout
    assert "- OpenCode, on free providers only: npm install -g opencode-ai" in out.stdout
    assert "zf mcp-config" not in out.stdout
    assert "skipped (--no-pair)" in out.stdout and "zeffin pair" not in world.calls()


def test_both_agents_found(world: World):
    world.tool("claude")
    world.tool("opencode")
    out = world.run("--no-pair", "--yes")
    assert out.returncode == 0, out.stdout + out.stderr
    calls = world.calls()
    assert "zeffin agent setup" in calls and "zeffin agent setup --agent opencode" in calls
    assert "zeffin mcp-config --yes" in calls
    config = json.loads(world.config.read_text())
    assert config["claude_bin"] == str(world.bin / "claude") and config["opencode_bin"] == str(world.bin / "opencode")
    assert "Claude Code is set up for chats." in out.stdout and "OpenCode is set up for chats." in out.stdout


def test_an_agent_not_signed_in_is_explained_and_the_rest_goes_on(world: World):
    world.tool("claude")
    out = world.run("--no-pair", FAKE_NOT_SIGNED_IN="claude")
    assert out.returncode == 0
    assert "Claude Code is not signed in" in out.stdout
    assert "Claude Code is not set up yet: do what the lines above say, then run ./install.sh again." in out.stdout
    out = world.run("--no-pair")
    assert "Claude Code is set up for chats." in out.stdout


def test_a_service_that_does_not_stay_up_offers_minimal(world: World):
    out = world.run("--no-pair", FAKE_SANDBOX_REJECTED="1")
    assert out.returncode == 1
    assert "the service zeffin does not stay running" in out.stdout
    assert "run: ./install.sh --minimal" in out.stdout
    out = world.run("--no-pair", "--minimal", FAKE_SANDBOX_REJECTED="1")
    assert out.returncode == 0 and world.unit.read_text() == "minimal"
    assert "zeffin install-service --minimal" in world.calls()


def test_help_and_unknown_options(world: World):
    out = world.run("--help")
    assert out.returncode == 0 and "--no-pair" in out.stdout and "--minimal" in out.stdout
    out = world.run("--force")
    assert out.returncode == 2 and "Unknown option: --force" in out.stderr
    assert world.calls() == []


def test_root_is_refused(world: World):
    out = world.run(FAKE_UID="0")
    assert out.returncode == 1 and "not as root and not with sudo" in out.stderr
    assert world.calls() == []
