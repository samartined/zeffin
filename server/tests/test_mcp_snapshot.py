# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The MCP tools the agent sees, as a committed file: a change to a tool shows in the diff.

tests/fixtures/mcp_tools.json holds every tool build_mcp() registers (name, description, input schema), sorted by name.
After changing a tool on purpose, regenerate it and commit it with the change:

    UPDATE_MCP_SNAPSHOT=1 pytest tests/test_mcp_snapshot.py

The test never writes the file unless that variable is set.
"""
import json
import os
from pathlib import Path

from bridge.hub import Hub
from bridge.tools import build_mcp

SNAPSHOT = Path(__file__).parent / "fixtures" / "mcp_tools.json"


async def listed(tmp_path) -> list[dict]:
    tools = await build_mcp(Hub(tmp_path, keepalive_s=0)).list_tools()
    return sorted(({"name": t.name, "description": t.description, "inputSchema": t.input_schema} for t in tools),
                  key=lambda t: t["name"])


async def test_the_tools_the_agent_sees_match_the_snapshot(tmp_path):
    tools = await listed(tmp_path)
    text = json.dumps(tools, indent=2, ensure_ascii=False) + "\n"
    if os.environ.get("UPDATE_MCP_SNAPSHOT"):
        SNAPSHOT.write_text(text)
    assert json.loads(SNAPSHOT.read_text()) == tools, \
        "a tool changed: if on purpose, run UPDATE_MCP_SNAPSHOT=1 pytest tests/test_mcp_snapshot.py and commit the file"
    assert SNAPSHOT.read_text() == text  # the file is what the command writes, so its diffs are small
