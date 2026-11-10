# Security policy

## Reporting a vulnerability

Please report a vulnerability privately, never in a public issue, pull request or discussion.

- Use GitHub's private vulnerability reporting: on this repository, open **Security**, then **Report a vulnerability**. Only the maintainer sees the report.
- Say what is affected (the server, the app or the relay), the version (`zf status` on the server; Settings, About in the app), the steps to reproduce it and what an attacker gains.
- Do not include real tokens, keys, IP addresses or personal data: use fake values.

The maintainer answers as soon as they can. A confirmed vulnerability is fixed in a new version first and described in its release notes afterwards, crediting you if you wish.

## Scope

In scope:

- The server (`server/`): its listeners, tokens, pairing, web view, MCP tools, the operator gate and the agent integration (what a turn may run and see).
- The Android app and its relay (`android/`): the pinned server key, the stored tokens, the relay to the Flipper and the pairing.
- The protocols between them (`docs/protocol/`) and the release workflow (`.github/workflows/`).

Out of scope:

- The Flipper Zero firmware, Tailscale, Android itself, the agents (such as Claude Code) and the libraries Zeffin uses: report those to their own projects.
- What the agent does with the permissions its owner gives it, and an attacker who already controls the server's user account or the unlocked phone.

What Zeffin trusts and how each part is protected is described in [docs/security.md](docs/security.md).
