# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The agents' own configuration and instructions: paths an edit, delete or move of never goes through without the
owner's card, in any mode, whatever "always" granted (a finding of a security scan before 1.0.0).

An agent misled by what it reads (the Flipper's screen, a file name) could otherwise rewrite what the next turn runs
with: OpenCode's opencode.json or .opencode/ (permissions, plugins, agents, commands), the instructions in AGENTS.md
or CLAUDE.md, Claude Code's .claude/ or .mcp.json, or OpenCode's global configuration folder. Both agents run in the
same folder, so each one's files are guarded from the other too.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Mapping

# In the agent folder: the files and folders either agent reads its configuration or instructions from.
FOLDER_FILES = ("opencode.json", "opencode.jsonc", "AGENTS.md", "CLAUDE.md", "CLAUDE.local.md", ".mcp.json")
FOLDER_DIRS = (".opencode", ".claude")
# The names a shell command that touches one of them would carry (the plain names: a check on a command's text, not
# a boundary; see is_named).
NAMES = ("opencode.json", ".opencode", "AGENTS.md", "CLAUDE.md", "CLAUDE.local.md", ".claude", ".mcp.json",
         ".config/opencode")


def opencode_global(environ: Mapping[str, str] | None = None, home: Path | None = None) -> Path:
    """OpenCode's global configuration folder: $XDG_CONFIG_HOME/opencode, else ~/.config/opencode."""
    env = os.environ if environ is None else environ
    base = env.get("XDG_CONFIG_HOME") or str((home or Path.home()) / ".config")
    return Path(base) / "opencode"


def guarded(agent_dir: str | Path, environ: Mapping[str, str] | None = None, home: Path | None = None) -> list[Path]:
    """Every guarded path, absolute, for an agent folder."""
    folder = Path(agent_dir)
    user = home or Path.home()
    return ([folder / name for name in FOLDER_FILES + FOLDER_DIRS]
            + [opencode_global(environ, user), user / ".claude", user / ".claude.json"])


def _real(path: Path) -> str:
    """The path with symlinks resolved and . and .. gone, case-folded: a case-insensitive file system or a symlinked
    folder does not hide a guarded file."""
    return os.path.realpath(path).casefold()


def is_guarded(path: str, agent_dir: str | Path, environ: Mapping[str, str] | None = None,
               home: Path | None = None) -> bool:
    """Whether a path (relative ones are the agent folder's) is a guarded one, inside one, or a folder that holds one
    (moving or deleting it would take the guarded file with it)."""
    target = Path(path).expanduser() if path.startswith("~") else Path(path)
    if not target.is_absolute():
        target = Path(agent_dir) / target
    real = _real(target)
    for g in guarded(agent_dir, environ, home):
        mine = _real(g)
        if real == mine or real.startswith(mine.rstrip(os.sep) + os.sep) or mine.startswith(real.rstrip(os.sep) + os.sep):
            return True
    return False


def is_named(command: str) -> bool:
    """Whether a shell command names a guarded file or folder: "always" never answers it (it still asks)."""
    folded = command.casefold()
    return any(name.casefold() in folded for name in NAMES)
