# FAQ

**Does Zeffin cost anything?**
Zeffin itself, no: it is free software (AGPL-3.0-or-later). The agent runs with what you set up: Claude Code with your Claude subscription or another login, or OpenCode on free providers with no payment method, or on a paid one. A model that bills per use says **Paid** in the app ([Use the agent](use-the-agent.md#what-each-agent-uses)).

**Which agent should I use?**
Claude Code, if you have a Claude subscription: Anthropic's terms for your account apply. OpenCode on its free providers costs nothing and needs no subscription, but free providers keep what they receive to improve their models, Flipper data included ([Use the agent](use-the-agent.md#what-free-providers-keep)). Each conversation keeps its own agent, so you can use both.

**Can I use OpenCode with a paid provider, or Claude Code with an API key?**
Yes. Sign the provider in with `opencode auth login` on the server, or sign Claude Code in with that login; their models are offered, and those that bill per use say **Paid**. The bill is between you and that provider ([Use the agent](use-the-agent.md#what-each-agent-uses)).

**Why a server? Could the agent run on the phone?**
The agent runs where its tools and your login are: a Linux machine that stays on. The phone only holds the Flipper's Bluetooth link and forwards it.

**Why Tailscale?**
Zeffin needs a private network between the phone, your browsers and the server, without opening a port to the internet. The server listens only on its Tailscale address and refuses any other peer; the app sends nothing outside Tailscale's tunnel ([Security](security.md#the-private-network)).

**Can the agent do something I did not ask for?**
Yes. While it is active, nothing technically stops it from pressing keys, starting apps or making the Flipper transmit. Its instructions ask it not to transmit unless you ask, but only **Pause** makes it read-only ([Pause and Stop](pause-and-stop.md)).

**Does the agent ask before it touches the Flipper?**
No: the Flipper tools never ask, in any permission mode. It asks before other things the conversation's mode does not allow, such as a shell command on the server ([Use the agent](use-the-agent.md#permissions)).

**Can I drive the Flipper myself?**
Yes: **Remote** on Home. Opening it pauses the agent while you drive ([The remote](the-remote.md)).

**Can I use the Flipper's official app at the same time?**
Not while Zeffin's relay is on: the two would share the Bluetooth link and mix their sessions. Turn the relay off first.

**Can several phones or people use one server?**
The server takes one relay at a time: a second phone's relay is refused while the first is connected. The conversations live on the server, so `zf chat` and the app see the same ones. Every client authenticates with your tokens: Zeffin is for one owner.

**Does it work without a Flipper?**
For trying it, yes: the simulator imitates one ([Install the server](install-the-server.md#try-it-without-a-flipper)).

**Which firmware does it support?**
The official firmware and forks that keep its RPC over Bluetooth. The server detects the firmware and gives the agent its profile and app catalog; profiles ship for `official`, `momentum` and `unleashed` ([The Flipper tools](flipper-tools.md#the-firmware-and-its-apps)).

**What happens to what the Flipper shows the agent?**
It goes to the agent as untrusted data, and from there to the model's provider under its own terms: Anthropic's for Claude Code (code.claude.com/docs/en/data-usage), OpenCode's provider's for OpenCode (a free one keeps it to improve its models). Locally, the server keeps the audit log, the last screen a tool gave the agent and your conversations ([Security](security.md#what-is-logged)).

**Why is the app not on Google Play?**
It is published as a signed APK on the repository's *Releases* page. Every release is signed with the same key, whose certificate's SHA-256 is printed in each release's notes ([android-app.md](../android-app.md#check-the-apk-optional)).

**Which languages does the app speak?**
English and Spanish. It follows the phone's language; Settings, Language · Idioma changes it.

**Is Zeffin affiliated with Flipper Devices or Anthropic?**
No. Flipper Zero is a trademark of Flipper Devices Inc.; Claude and Claude Code are trademarks of Anthropic.
