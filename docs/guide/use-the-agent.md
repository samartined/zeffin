# Use the agent

You talk to the agent from the app's conversations, or from a terminal on the server with `zf chat`. The agent runs on your server, in its own folder, and drives the Flipper with the `flipper_*` tools ([The Flipper tools](flipper-tools.md)).

Zeffin has two agents, and each conversation keeps the one it was created with:

| | Claude Code | OpenCode |
|---|---|---|
| **Account** | Your Claude login: a Claude subscription, or another login Claude Code takes, such as an API key | None for OpenCode Zen's free models; a free NVIDIA account for NVIDIA NIM's free tier; or any other provider you sign in |
| **Models** | Every model your Claude Code lists; one that bills per use says **Paid** | Every model of the providers it has; one with a price says **Paid** |
| **Permission modes** | Manual, Accept edits, Plan, Auto | Manual, Accept edits, Plan |
| **Remote Control** | Yes, with a subscription | No |
| **What it costs** | Your subscription, or what your login bills | Nothing on the free providers, which **keep what they receive to improve their models** ([below](#what-free-providers-keep)); a paid provider bills its own account |

Both run in the same agent folder, `~/projects/zeffin-agent`, and see the same `flipper_*` tools. Set up one or both.

## Set up Claude Code

**On the server:**

1. Install Claude Code for your user, as code.claude.com describes (code.claude.com/docs/en/setup).
2. Sign in: run `claude`, type `/login`, and choose your Claude account (a subscription), or another login Claude Code offers (code.claude.com/docs/en/authentication).
3. Prepare it for Zeffin:

   ```bash
   zf agent setup
   zf chat --new         # try it from the terminal
   ```

`zf agent setup`:
- creates the agent folder, `~/projects/zeffin-agent` next to the clone, with its `CLAUDE.md`: the instructions that tell the agent how to drive the Flipper (a copy of [`CLAUDE.md.example`](../../CLAUDE.md.example); edit it as you like, `zf agent setup` keeps your version);
- finds `claude` and saves its path for the service;
- checks that Claude Code is signed in, and says so when the login is not a subscription (then every turn bills per use);
- marks the folder as trusted for Claude Code, in `~/.claude.json`;
- `--dir DIR` chooses another folder, and `--model opus|sonnet|haiku` the model new conversations start with (default `opus`).

## Set up OpenCode

**On the server:**

1. Install OpenCode for your user, in a version this server runs: from 1.19.5 up to, but not including, 1.20.0 (opencode.ai/docs):

   ```bash
   npm install -g opencode-ai@1.19.5
   ```

2. Choose a provider:
   - **NVIDIA NIM's free tier** (with a free model that reads the Flipper's screen well): create a free account at build.nvidia.com and generate an API key (opencode.ai/docs/providers); no card. Then, on the server, run `opencode auth login`, choose NVIDIA and paste the key there. OpenCode keeps it in `~/.local/share/opencode/auth.json` (opencode.ai/docs/cli). **Type the key only there:** never in a chat, a file of the repository or a command line.
   - **OpenCode Zen's free models:** nothing to do: they need no account. Which ones are free changes over time, and some are free only for a limited time (opencode.ai/docs/zen).
   - **Any other provider OpenCode supports,** free or paid: sign it in the same way, with `opencode auth login` on the server (opencode.ai/docs/providers). Its models are listed too, and a paid one says **Paid**. Zeffin never passes a provider's key in OpenCode's environment, so sign it in there, not with a variable.
3. Prepare it for Zeffin:

   ```bash
   zf agent setup --agent opencode
   ```

`zf agent setup --agent opencode`:
- refuses an OpenCode outside the supported versions, and says which to install;
- writes `AGENTS.md` in the agent folder (the same instructions as `CLAUDE.md`; edit it as you like) and `opencode.json`, the configuration the server needs (it writes it again each time: do not edit it);
- checks OpenCode's configuration merged with your own global one (`~/.config/opencode`), and refuses if the global one turns sharing or auto-update back on;
- says which providers are signed in, lists the free models it can offer and how many paid ones, and the default model for new conversations;
- saves the path of `opencode` and, when the installed service runs this same configuration, reinstalls it so it can start `opencode` (a service of another configuration is left alone).

The agents share one folder: `--dir` moves it for both, with `zf agent setup --dir DIR` first.

`zf status` then lists both agents: Claude Code's version and login, and OpenCode's version, providers and default model.

## What each agent uses

Each agent runs with the login or providers you set up on the server, paid or free: Zeffin does not choose for you. The free options are Claude Code with a subscription (nothing beyond it), and OpenCode on OpenCode Zen's free models or NVIDIA NIM's free tier. A model that bills per use says **Paid** in the app's model list.

### Claude Code

- **Each message is one turn of `claude -p`** in the agent folder, with your Claude Code login. The server never uses `--bare` (it skips the subscription login) and never puts a token on a command line.
- **The login is yours:** with a subscription, a model your plan does not include, such as one that bills usage credits, says **Paid**. With an API key, every model bills per use, and they all say so. In `claude -p`, usage credits are billed without asking (code.claude.com/docs/en/model-config).
- **Keep paid extra usage off** in your Claude account if you want a hard limit.

### OpenCode

- **The providers you signed in:** OpenCode Zen's free models need no account; NVIDIA NIM's free tier and any other provider are signed in with `opencode auth login`. No provider is favoured: the models of the providers you signed in come first, in OpenCode's order, then Zen's free models, and the first one is where new conversations start until you choose another. Each provider shows by its name (OpenCode Zen, NVIDIA NIM, OpenRouter…). Every model they list is offered; one with a price says **Paid** (Zen's models priced at 0, and NIM's from a list in the server, `server/bridge/chat/free_models.json`, are free). A few of Zen's free models that misread the Flipper's screen are left out.
- **The model you chose:** a turn whose session ends up on another model than the conversation's, at its start or during the turn, is stopped at once.
- **A clean environment:** each turn's `opencode` gets an allow-listed environment, with no provider's key and no token. The Flipper's MCP server and its token go to OpenCode through its own pipe, never in the environment or on a command line, where its shell tool could read them.
- **No sharing, no auto-update:** session sharing (which uploads a session to OpenCode's website) and auto-update are off, also in OpenCode's merged configuration, so a global setting cannot turn them on; Zeffin pins the versions it checked.
- **Free without a card:** NVIDIA's free tier and Zen's free models need no payment method.

### Screens per conversation on NVIDIA's free model

NVIDIA's free Nemotron model, on NIM or through OpenCode Zen, takes at most 13 images in one conversation, and every look at the Flipper's screen is one. The agent sends the whole conversation each time, so a 14th screen would make every message after it fail. Zeffin keeps count: once a conversation holds 7 screens, it has OpenCode summarize it before your next message, which drops the old screens (the conversation shows "The conversation was compacted to fit."). If a single message still goes past 13, the turn ends with "Too many screens for this model in one conversation", and Zeffin summarizes the conversation then: send your message again, or "continue". If that summary fails, start a new conversation. The other agents and models have no such limit.

### Notes per app

After the agent has worked out an app that hides its keys (a long press, an odd exit), ask it: "save what you learned about this app". It keeps a short note, and later conversations on your server start from it. The agent may save one by itself, depending on the model: in one test, Claude Code with Haiku did; in four tests, NVIDIA's free model saved a note only when asked. The chat shows a "Using flipper_note" card when one is saved. What a note holds, and `zf notes` to read and delete them: [The Flipper tools](flipper-tools.md#notes-per-app).

### What free providers keep

**Free providers keep what they receive to improve their models.** With OpenCode, that is everything the agent sends: your messages and attachments, and what it reads from the Flipper, such as its screen, file names and contents, and NFC or RFID dumps. OpenCode Zen's page says that, during their free period, some of its free models may keep what they collect and use it to improve the model, while others say their provider keeps nothing (opencode.ai/docs/zen); NVIDIA's free tier has its own terms. Zeffin cannot tell which applies to each message, so treat all of it as kept. A paid provider follows its own terms.

- Do not use OpenCode for what you would not hand to a third party.
- Claude Code, with your subscription, follows Anthropic's own terms for your account (code.claude.com/docs/en/data-usage).

## Conversations

### Choosing the agent

When the server has more than one agent set up, the app lets you choose one per conversation:

- **A new conversation** shows an **Agent** card above the message field: the agent it will use, the last one chosen ("The last one chosen. It stays once you write.").
- **Conversation settings** have an **Agent** row first, with the list of agents: each one's state, version and login. An agent that is not ready is dimmed with the reason, and cannot be chosen; Settings, Connections, Agents says what to run on the server.
- **The agent changes only before the first message.** Choosing another starts the empty conversation again with it. Once you write, the conversation keeps its agent and that agent's own session ("Kept for this conversation. Another agent: start a new one.").
- **A new conversation starts with the agent last chosen,** and with that agent's model, effort and mode: each agent keeps its own.
- **When the agent last chosen is not ready,** a new conversation says so, with **Choose an agent**, and Send waits until you choose one that is.

### The conversations

- **Conversations** (the second tab): All and Favorites, search, **Filter and sort** (group by day, sort by last activity, creation or title, a period, only those with Flipper actions), and **New conversation**. Pinned ones stay on top.
- **Select one or several** with a long press; then a tap adds or removes others. The bar shows how many are selected; **Select all** takes the conversations the list shows (with a search, its matches). The X, Back or unselecting the last one leaves the selection.
- **What you can do with the selection,** in the bottom bar: **Pin** (Unpin when all are pinned), **Favorite** (Unfavorite when all are), **Delete**, and with one selected **Rename** and **Copy ID** (the agent's session id).
- **Deleting asks first,** with each title, its messages and attachments, and the totals. It removes, on the server, the messages, the attachments and each agent's own copy of the session (Claude Code's transcript, or OpenCode's session through `opencode session delete`); it cannot be undone. A conversation where the agent is writing stops its turn first. Those that could not be deleted stay selected, with why, and **Try again**.
- **They are isolated from your own settings:** a Claude Code turn loads only the agent folder's settings (`--setting-sources project,local`) and only the `flipper` MCP server (`--strict-mcp-config`); an OpenCode turn gets the `flipper` MCP server from Zeffin, and the guards above.
- **One turn at a time on the whole server,** whatever the agent: while an agent works in one conversation, another waits, and the app offers to open the busy one or stop it.
- **On disk:** `zeffin-data/state/conversations.json` lists them, with their history beside it. Claude Code keeps its own transcripts under `~/.claude/projects/` and deletes them after `cleanupPeriodDays`, 30 days by default (code.claude.com/docs/en/data-usage); such a conversation can still be read, but not continued.

## The agents in Settings

Settings, Connections, **Agents** shows each agent's state ("Claude Code ready · OpenCode not signed in"; with more than two agents, how many are in each state). It opens the list of the agents set up on the server; a tap opens an agent's page:

- **Version** and **Login:** for Claude Code, your login (a subscription shows as "Subscription"); for OpenCode, its **Providers** signed in (or "None signed in").
- **Remote Control,** only for an agent that has it.
- **For an agent on providers (OpenCode),** the line: "Free providers keep what they receive to improve their models: the Flipper's screen, your messages and files such as NFC or RFID dumps."
- **When it is not ready,** why, and what to run on the server (for example `zf agent setup --agent opencode`, or sign it in and `zf restart`).

Settings, **Agent** keeps the preferences: where new conversations start, the time to answer a permission, and the notifications.

## Model, effort and permission mode

Each conversation has its own, in **Conversation settings** (the panel that unfolds from the conversation's top bar). What it offers comes from the conversation's agent:

| Setting | Claude Code | OpenCode |
|---|---|---|
| **Model** | The models your Claude Code lists, pinned versions included; one that bills per use says **Paid** | The models of its providers: NIM's free ones (not available, with the reason, until NVIDIA is signed in), Zen's free ones, and those of any other provider signed in; one with a price says **Paid** |
| **Effort** | Low, Medium, High, Extra high, Max: the levels the model supports (code.claude.com/docs/en/model-config) | The levels the model offers, if any |
| **Permission mode** | Manual, Accept edits, Plan, Auto (code.claude.com/docs/en/permission-modes) | Manual, Accept edits, Plan |

- **Manual:** asks you before anything that is not a read or a Flipper tool.
- **Accept edits:** edits files and runs common file commands without asking; asks for the rest. With OpenCode, the server answers its edit, delete and move requests itself.
  - In every mode, an edit of the agents' own settings or instructions (`CLAUDE.md`, `AGENTS.md`, `opencode.json`, `.opencode/`, `.claude/`, OpenCode's global settings) always asks you, and "Always" is not offered for it.
- **Plan:** reads and proposes a plan; changes nothing until you approve it or switch mode. With OpenCode, it is its own plan agent.
- **Auto** (Claude Code only): runs everything, with Claude Code's background safety checks; shows what it blocks. When Auto is not available to your account, the turn runs in Manual and says so. OpenCode has no such checks, so it does not offer Auto.

**Which models see the screen:** when an agent's list mixes both kinds, each model says **Takes images** or **Text only**. A text-only model still uses the Flipper's tools, but it works blind: it cannot read the screen. A paid model says **Paid** next to it.

**Where new conversations start:** Settings, **Agent**, **New conversations**: the agent, and its model, effort and permission mode. They are the last ones chosen in a conversation, or set there; each agent keeps its own.

From the terminal, `zf chat --mode default|acceptEdits|plan|auto` sets a new conversation's mode, and `/model`, `/effort` and `/mode` change them in the chat.

## Permissions

- **The Flipper tools never ask,** in any mode. Pause is how you stop the agent from changing the Flipper ([Pause and Stop](pause-and-stop.md)).
- **Anything else the mode does not allow** (a shell command, a file outside the folder…) waits for your answer: a card in the conversation and in Home, with **Allow once**, **Always allow in this conversation** and **Deny**. The card shows the whole command.
- **"Always allow"** saves the rules the card showed, for this conversation only. With OpenCode, the server keeps the rule and answers the same request itself next time.
- **Time to answer** (Settings, Agent, Permissions; 2 minutes unless you change it, from 30 seconds to 9 and a half minutes): then the request is denied and the agent goes on.
- **Plan mode** ends with a plan to approve: **Approve and continue**, or **Keep planning**.

## Attachments

With the clip in the message field: **Take photo**, **Gallery**, **File**, or the **Flipper screen**. From the terminal: `/attach FILE` in `zf chat`.

- **Images** (PNG, JPEG, GIF, WebP) reach the agent as images; other files (PDF, text, `.ir`, `.sub`…) are saved for the agent to read.
- Up to 10 per message, 10 MB each, and 7 MB of images in one message.
- A paste longer than a message's 16,000 characters becomes a text file attached to it.
- They are kept in `zeffin-data/state/attachments/`; deleting the conversation deletes them.

## Notifications

In Settings, Agent, **Notifications**, only while the app is not on screen:

- **When a turn ends:** finished, stopped or failed.
- **When the agent asks for permission:** **Allow once** or **Deny** from the notification. Allow once is offered only for a request the notification shows whole, and asks you to unlock the phone. "Always allow" and plans are answered in the app.
- While the agent works, the notification bar shows "The agent is working".

If Android does not let Zeffin show notifications, the Agent screen says so, with **Allow notifications**. Each channel can be changed in Android's settings ([android-app.md](../android-app.md#notification-channels)).

## Remote Control

Only with Claude Code. When the server's Claude Code offers it, a conversation's settings have a **Remote Control** switch: the same conversation can then also be written from the Claude app or claude.ai/code, with your subscription (code.claude.com/docs/en/remote-control).

- While it is on, the model, effort and mode stay as they are, and the reply shows whole when the turn ends.
- A permission asked on the other device shows as a card without buttons: answer it there.
- Turning it off while the agent works stops the turn, so the app asks first.
- While it is on, Claude Code keeps the conversation's transcript on Anthropic's servers.

From the terminal: `/remote on` and `/remote off` in `zf chat`.

## From the terminal: `zf chat`

`zf chat` talks to the agent through the same channel as the app, so the conversations are the same:

```bash
zf chat               # the latest conversation
zf chat --new         # a new one
zf chat --list        # list them and exit
zf chat 3fa9          # the one whose id starts with 3fa9
```

Enter sends; Ctrl+C stops the agent's turn. Commands: `/model NAME`, `/effort LEVEL`, `/mode MODE`, `/attach FILE` (alone, it lists them; `/attach clear` drops them), `/remote on|off`, `/status`, `/new`, `/list`, `/delete` (this conversation, for good), `/quit`. `zf chat --new` starts a conversation with the default agent.

`zf chat --test` (with `--lang en|es`) starts the chat's manual test, guided step by step by the agent.

## Check how your agent behaves

```bash
zf agent probe                     # Claude Code
zf agent probe --agent opencode    # OpenCode
```

- **Claude Code:** about 17 tiny Haiku turns check how the installed Claude Code behaves where its documentation is not explicit. `--remote` also tries Remote Control.
- **OpenCode:** a few short turns on a free model (`--model` chooses which), against a throwaway server of its own and the simulator, never your running service or your Flipper: it checks the guards, that the model sees the screen, a permission request, resuming and deleting a session. It takes 1 to 3 minutes.

Both print a report without tokens, paths or texts. The options are in [The `zf` command](zf-command.md).
