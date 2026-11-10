# Updating

## The server

**On the server:**

```bash
zf update
```

It pulls the latest code of the clone's branch, reinstalls the dependencies with the virtual environment's `pip`, restarts the service and shows `zf status`. With nothing new to pull, it still restarts when the service runs other code than the clone (after a `git checkout`, or a restart still pending).

`zf update` refuses, and changes nothing, when:
- **the clone has local changes:** commit or discard them first (`git -C ~/projects/zeffin status`);
- **the clone's history does not match the remote's** (it was rewritten, or you have local commits). If there is nothing of yours to keep, align it once with `git -C ~/projects/zeffin reset --hard @{u}`, then run `zf update` again.

Your configuration, tokens, key, conversations and logs live in `zeffin-data`, next to the clone. An update keeps them, but may rewrite their format.

### Going back to an older version

An update may rewrite the data folder's files in a newer format, which an older server refuses. To keep the way back open, copy `zeffin-data` before you update (`zf stop` first). To go back: stop the service, check out the older version in the clone, reinstall (`.venv/bin/pip install -r server/requirements.txt -e ./server`), put the copy back as `zeffin-data` and start the service again. Conversations made after the update are lost that way.

## OpenCode

Zeffin runs only the OpenCode versions it was checked with (today from 1.19.5 up to, but not including, 1.20.0), and turns OpenCode's auto-update off. Update OpenCode only when a Zeffin release says which version to install, then run `zf agent setup --agent opencode` again.

Claude Code updates itself as it usually does (code.claude.com/docs/en/setup); `zf status` shows its version.

## The app

**On the phone:** download the new `zeffin-vX.Y.Z.apk` from the repository's *Releases* page and open it. It installs on top of the old one and keeps its settings and pairing, as long as it is signed with the same key ([android-app.md](../android-app.md#install-and-update-on-the-phone)).

- **Update the server and the app together,** server first: each release's notes say what goes together. From 1.0.0 on they share one version number.
- **When the server is too old for the app,** the app says "The server is too old for this app. Update it on the server with zf update."
- **When the chat needs a newer server,** Conversations says "The chat needs a server update".

## Rotate the tokens

**On the server:**

```bash
zf rotate-tokens                 # all four
zf rotate-tokens --only phone    # just one (repeatable)
zf restart                       # the old ones stop working once the server restarts
```

It does not print the new tokens; it says where each one is used:

| Token | Where to update it |
|---|---|
| `phone` | The relay. Pair the app again (`zf pair`), or type it in Settings, Relay, Server (`zf token phone` prints it) |
| `mcp` | `zf rotate-tokens` updates the `flipper` entry `zf mcp-config` wrote. With `.mcp.json`, export the new `ZEFFIN_MCP_TOKEN`. The app's chats need nothing |
| `view` | Sign in to the web view again with `zf token view` |
| `app` | Pair the app again: `zf pair` |

Rotate when a token may have leaked, or after a pairing you did not make.

## Replace the server's key

```bash
zf key new        # asks first; --yes skips the question
zf restart
zf pair           # the app refuses a server whose key changed
```

By default it also changes the phone and app tokens. `--keep-tokens` keeps them. If `config.json` may have leaked with the old key, also rotate the others: `zf rotate-tokens --only view --only mcp`.

## Move the server to another Tailscale address

```bash
zf set-host 100.98.12.7
zf restart
zf pair
```

It keeps the tokens and the key; the next start makes a certificate for the new address. Pair the app again, since the address is part of the pairing.
