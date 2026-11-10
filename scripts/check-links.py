#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Check every relative link and #anchor in the repository's Markdown files.

Run from anywhere, with the standard library only:

    python3 -I scripts/check-links.py            # the whole repository
    python3 -I scripts/check-links.py docs/guide # only the files under these paths

For each `[text](target)` and `<a href="target">` outside code blocks and code spans:
- a web link (`http:`, `https:`, `mailto:`) is skipped: this check never goes to the network;
- a relative path must exist, as a file or a folder, inside the repository;
- an `#anchor`, alone or after a Markdown file, must be a heading of that file (GitHub's slugs) or an explicit
  `<a id=...>` / `<a name=...>`.

It prints one line per broken link (`file:line: target: why`) and exits 1 if there is any, 0 otherwise.
"""
from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", ".gradle", ".venv", "venv", "build", "node_modules", ".idea", "__pycache__", ".pytest_cache",
             ".mypy_cache", ".ruff_cache"}
FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
INLINE_LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]*)>?(?:\s+\"[^\"]*\")?\s*\)")
HREF = re.compile(r"<a\s[^>]*?href=[\"']([^\"']+)[\"']", re.IGNORECASE)
EXPLICIT_ID = re.compile(r"<a\s[^>]*?(?:id|name)=[\"']([^\"']+)[\"']", re.IGNORECASE)
CODE_SPAN = re.compile(r"(`+)(.+?)\1")
WEB = re.compile(r"^[a-z][a-z0-9+.-]*:", re.IGNORECASE)


def markdown_files(paths: list[Path]) -> list[Path]:
    found: list[Path] = []
    for base in paths:
        if base.is_file():
            found.append(base.resolve())
            continue
        for path in sorted(base.rglob("*.md")):
            if not SKIP_DIRS.intersection(path.relative_to(base).parts):
                found.append(path.resolve())
    return found


def prose_lines(text: str):
    """(line number, raw line, line with code spans blanked) for every line outside fenced code blocks."""
    fence: str | None = None
    for number, line in enumerate(text.splitlines(), 1):
        match = FENCE.match(line)
        if match:
            mark = match.group(1)
            if fence is None:
                fence = mark[0] * len(mark)
                continue
            if mark[0] == fence[0] and len(mark) >= len(fence):
                fence = None
                continue
        if fence is None:
            yield number, line, CODE_SPAN.sub(lambda m: " " * len(m.group(0)), line)


def slug(heading: str) -> str:
    """GitHub's anchor for a heading: its rendered text, lower case, without punctuation, spaces as hyphens."""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", heading)   # links and images: their text
    text = re.sub(r"<[^>]+>", "", text)                          # inline HTML
    text = text.replace("`", "").replace("*", "")
    text = re.sub(r"(^|\s)_+|_+(\s|$)", r"\1\2", text)           # _emphasis_, not snake_case
    text = text.strip().lower()
    kept = "".join(c for c in text if c in " -_" or unicodedata.category(c)[0] in "LNM")
    return kept.replace(" ", "-")


def anchors(path: Path, cache: dict[Path, set[str]]) -> set[str]:
    if path not in cache:
        seen: dict[str, int] = {}
        found: set[str] = set()
        text = path.read_text(encoding="utf-8")
        for _, raw, line in prose_lines(text):
            found.update(EXPLICIT_ID.findall(line))
            match = HEADING.match(raw)  # a heading's code spans count in its anchor
            if not match:
                continue
            base = slug(match.group(2))
            count = seen.get(base, 0)
            seen[base] = count + 1
            found.add(base if count == 0 else f"{base}-{count}")
        cache[path] = found
    return cache[path]


def check(path: Path, cache: dict[Path, set[str]]) -> list[str]:
    problems: list[str] = []
    rel = path.relative_to(ROOT)
    for number, _, line in prose_lines(path.read_text(encoding="utf-8")):
        for target in INLINE_LINK.findall(line) + HREF.findall(line):
            if not target or WEB.match(target):
                continue
            where, _, anchor = target.partition("#")
            where = unquote(where)
            if where:
                dest = (ROOT / where.lstrip("/")) if where.startswith("/") else (path.parent / where)
                dest = dest.resolve()
                if not dest.is_relative_to(ROOT):
                    problems.append(f"{rel}:{number}: {target}: outside the repository")
                    continue
                if not dest.exists():
                    problems.append(f"{rel}:{number}: {target}: no such file or folder")
                    continue
            else:
                dest = path
            if anchor:
                if dest.is_dir() or dest.suffix.lower() != ".md":
                    continue  # an anchor into a folder or a source file is GitHub's line link: not checked
                if unquote(anchor).lower() not in anchors(dest, cache):
                    problems.append(f"{rel}:{number}: {target}: no heading #{anchor} in {dest.relative_to(ROOT)}")
    return problems


def main(argv: list[str]) -> int:
    paths = [Path(a).resolve() for a in argv] or [ROOT]
    missing = [str(p) for p in paths if not p.exists()]
    if missing:
        print(f"No such path: {', '.join(missing)}", file=sys.stderr)
        return 2
    files = markdown_files(paths)
    cache: dict[Path, set[str]] = {}
    problems = [problem for path in files for problem in check(path, cache)]
    for problem in problems:
        print(problem)
    print(f"{len(files)} Markdown files, {len(problems)} broken link(s).", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
