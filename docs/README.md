# Zeffin documentation

## User's guide

How to set up Zeffin and use it, one topic per page: [guide/](guide/README.md).

1. [What Zeffin is and what it needs](guide/what-is-zeffin.md)
2. [Install the server](guide/install-the-server.md)
3. [Pair the app](guide/pair-the-app.md)
4. [Use the agent](guide/use-the-agent.md)
5. [The Flipper tools](guide/flipper-tools.md)
6. [The remote](guide/the-remote.md)
7. [Pause and Stop](guide/pause-and-stop.md)
8. [Security](guide/security.md)
9. [Updating](guide/updating.md)
10. [The `zf` command](guide/zf-command.md)
11. [Troubleshooting](guide/troubleshooting.md)
12. [FAQ](guide/faq.md)

## Reference

For whoever builds, reviews or changes Zeffin.

| Document | What it holds |
|---|---|
| [architecture.md](architecture.md) | The server's layers and the app's packages, what may import what, and the tests that hold the rules |
| [security.md](security.md) | What is trusted, the listeners, tokens, pairing, the agent, the Flipper, what is kept on disk, keys, and every finding |
| [android-app.md](android-app.md) | The app: building, signing, releases, test builds, installing, the private network and notification channels |
| [protocol/app/](protocol/app/README.md) | The `/app` protocol between the server and the app: JSON Schemas and examples |
| [protocol/pairing/](protocol/pairing/README.md) | Pairing: the QR code, the server's key and the confirmation |
| [protocol/rpc/](protocol/rpc/README.md) | The Flipper's RPC link shared by the server and the relay: who sends what, and the `command_id` ranges |
| [design/](design/README.md) | The app's approved design: tokens, components and the captures of each screen |

At the repository's root: [CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and the app's [THIRD_PARTY_NOTICES.md](../android/licenses/THIRD_PARTY_NOTICES.md).

## Release notes

[release-notes/](release-notes/): each version's "What's new", from which the release notes are generated.
