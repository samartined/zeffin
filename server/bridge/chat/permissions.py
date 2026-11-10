# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Answers to Claude Code's PermissionRequest hook (code.claude.com/docs/en/hooks).

The hook is an http hook to the loopback listener; the server holds the request until the owner
answers in the app or the deadline passes. "Always" uses the rules Claude Code itself suggests
(addRules with behavior allow): they apply for the rest of the turn (destination session) and the
server passes them with --allowedTools on later turns.
"""
from __future__ import annotations

import re

from ..wire import valid_rule
from . import guarded

ANSWERS = ("once", "always", "deny")
MESSAGES = {
    "deny": "The user denied this action.",
    "timeout": "The user did not answer in time.",
    "interrupted": "The user interrupted the turn.",
    "stopping": "The server is stopping.",
    "mismatch": "This request does not belong to the running turn.",
}


def suggested_rules(suggestions: object) -> list[str]:
    rules: list[str] = []
    for s in suggestions if isinstance(suggestions, list) else []:
        if not isinstance(s, dict) or s.get("type") != "addRules" or s.get("behavior") != "allow":
            continue
        for r in s.get("rules") or []:
            if not isinstance(r, dict) or not isinstance(r.get("toolName"), str):
                continue
            content = r.get("ruleContent")
            rule = f"{r['toolName']}({content})" if isinstance(content, str) and content else r["toolName"]
            if valid_rule(rule) and rule not in rules:
                rules.append(rule)
    return rules


_OPERATORS = re.compile(r"&&|\|\||[;|&\n*()`]")


def fallback_rules(tool: object, tool_input: object) -> list[str]:
    """When Claude Code suggests no rule for a Bash command, an exact-match rule for that command text.
    code.claude.com/docs/en/permissions: a rule with no `*` matches one exact command, and a compound
    command needs a rule per subcommand, so commands with shell operators, wildcards or parentheses get
    none. Never for other tools: file writes get once or deny, as in Claude Code."""
    if tool != "Bash" or not isinstance(tool_input, dict) or not isinstance(tool_input.get("command"), str):
        return []
    command = tool_input["command"].strip()
    if not command or _OPERATORS.search(command):
        return []
    rule = f"Bash({command})"
    return [rule] if valid_rule(rule) else []


# Claude Code's file-writing tools and the input field that names their file (code.claude.com/docs/en/tools-reference).
FILE_TOOLS = {"Write": "file_path", "Edit": "file_path", "MultiEdit": "file_path", "NotebookEdit": "notebook_path"}


def guarded_request(tool: object, tool_input: object, agent_dir: str) -> bool:
    """A request that touches the agents' own configuration or instructions (guarded.py): a file tool on a guarded
    path, or one without a clear path, or a Bash command that names a guarded file. "Always" is never offered for it,
    and the turn's ask rules (command.ask_rules) make Claude Code ask for it in every mode."""
    data = tool_input if isinstance(tool_input, dict) else {}
    if tool == "Bash":
        return isinstance(data.get("command"), str) and guarded.is_named(data["command"])
    if tool not in FILE_TOOLS:
        return False
    path = data.get(FILE_TOOLS[tool])
    return not isinstance(path, str) or not path or guarded.is_guarded(path, agent_dir)


def rule_parts(rule: str) -> dict:
    name, _, rest = rule.partition("(")
    return {"toolName": name, "ruleContent": rest[:-1]} if rest else {"toolName": name}


def decision(answer: str, rules: list[str]) -> dict:
    """The hook's JSON answer for once, always, deny, timeout, interrupted, stopping or mismatch."""
    if answer in ("once", "always"):
        body: dict = {"behavior": "allow"}
        if answer == "always" and rules:
            body["updatedPermissions"] = [{"type": "addRules", "rules": [rule_parts(r) for r in rules],
                                           "behavior": "allow", "destination": "session"}]
    else:
        body = {"behavior": "deny", "message": MESSAGES.get(answer, MESSAGES["deny"])}
        if answer in ("interrupted", "stopping"):
            body["interrupt"] = True
    return {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": body}}
