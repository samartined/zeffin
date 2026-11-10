# Zeffin app design

The approved design of the Android app. A screen is done when it looks like its capture here in all its states.

## What is here

- `system/`: the design system.
  - `README.md`: the rules (principles, tone, color, screen structure, navigation, type, the Flipper screen, states).
  - `tokens.json`: colors, type, spacing, radii and shadow. `Theme.kt` in the app follows it, and `ThemeTokensTest` checks that every color matches.
  - `tokens.css`: the same tokens as CSS custom properties.
  - `components/`: one `.md` per component with its behavior, plus `bundle.css`, `bundle.js` and `index.d.ts`, the web reference implementation. In Compose each component is a composable with the same name.
- `mockups/`: Python tooling to draw screens not built yet as standalone HTML and render them. Today it holds the notifications (`notifications.py`), which Android draws and the app cannot screenshot, the launcher icon's rounds (`icon/`), and the README's images (`brand.py`).
- `captures/`: one PNG per screen and state, 390x844 (landscape 844x390).
- `images/`: the README's main image and screens, and the repository's social preview, drawn by `mockups/brand.py` ("The README's images" below).

In short: dark theme only, black background with graphite cards, an orange app bar and Flipper screen, blue for actions, state written as colored words, Roboto and Roboto Mono.

## How the captures are made

There are two sources, and every capture has an English name.

**Screens the app has** are the app's own screenshots. The screenshot tests draw every screen and state in English and Spanish; the English ones are copied here:

```bash
cd android && ./gradlew :app:testDebugUnitTest          # writes app/build/outputs/roborazzi/en and es
cd .. && android/scripts/update-design-captures.sh      # copies the English ones to docs/design/captures/
```

The *Tests* workflow compares them: `android/scripts/compare-design-captures.py` fails when an English screenshot listed in `android/scripts/design-captures.txt` differs by a pixel from its capture here, and marks the changed pixels in `android/app/build/outputs/design-compare/`. A change to a screen is approved by the maintainer first; then `update-design-captures.sh` copies the new screenshots here, in the same pull request.

**Screens not built yet** are drawn as mockups first:

```bash
python3 -I docs/design/mockups/render.py
```

`render.py` collects the `boards()` of every other script in `mockups/`, writes one HTML page per artboard to a temporary folder and screenshots each one with headless Chromium at its size (390x844, or 844x390 in landscape). Set `CHROMIUM` to the browser binary if it is not found. The pages are self-contained: they inline `system/tokens.css` and `system/components/bundle.css`, embed the Roboto fonts from `android/app/src/main/res/font/` and use a frame of the server's Flipper simulator (`android/app/src/test/resources/screen/orientation-0.png`) as the Flipper screen.

- `parts.py`: the page wrapper, icons and shared pieces (app bar, bottom bar, cards, rows, sheets).
- A screen's mockup script is deleted once the screen is built: its captures take over.

The mockups are in English. When a mocked-up screen is built, it gets its Spanish texts too (the screenshot tests check both languages); then delete its artboard and add its name to `android/scripts/design-captures.txt`.

## The README's images

```bash
python3 -I docs/design/mockups/render.py --images      # writes docs/design/images/*.png
```

`mockups/brand.py` draws them with `images()` (never `boards()`, so they never land in `captures/`), from the app's own sources: the logo is the launcher icon (`ic_launcher_foreground.xml` on the brand orange of `values/colors.xml`, with its Z), and the screens are the captures here, with the character's two round eyes. Render them again, and commit them, when the icon or one of those captures changes.

| Image | Size | Where |
|---|---|---|
| `zeffin-main.png` | 1600x800 | The README's top: the logo, the name, the tagline and two screens |
| `zeffin-screens.png` | 1640x860 | The README's "Screens": Home, a conversation, the remote and the agents |
| `zeffin-social-preview.png` | 1280x640 | The repository's social preview: the owner uploads it in the repository's Settings, General, Social preview |
| `zeffin-demo.gif` | 1000x760 | The README's demo, under the introduction: the agent drives the Flipper from a conversation |

The demo animation is made the same way, from the captures and the server's simulator, with nothing recorded:

```bash
.venv/bin/python -I docs/design/mockups/demo.py         # writes docs/design/images/zeffin-demo.gif
```

`mockups/demo.py` needs the server's venv (Pillow and `bridge.sim`) and ffmpeg, no browser. The phone shows the captures unchanged except for the Flipper's screen in its own slots (the header's live thumbnail and the `flipper_press` row's), which the simulator draws as the big screen beside it. Make it again, and commit it, when one of its captures changes.

`docs/design/images/` is in the *Tests* workflow's `paths-ignore`: no test reads it.

## Captures

From the app:

| File | Screen |
|---|---|
| `home-01-main` | Home as the Flipper's panel: large screen, Pause and Resume, the last conversation's card with Open the conversation and New conversation, every agent's actions |
| `home-02-paused` | Agent paused by the operator |
| `home-02b-stopped` | Agent stopped by the operator, waiting to be resumed |
| `home-04-stale` | Stale screen (no recent frames) |
| `home-05-no-relay` | No relay: the agent cannot reach the Flipper |
| `home-05b-no-server` | No connection to the server |
| `home-08-landscape` | Home in landscape |
| `remote-01-portrait` | The remote: the Flipper's screen, the round pad and Back; the agent paused by the remote |
| `remote-02-landscape` | The remote in landscape: the screen on the left, the pad on the right |
| `remote-03-cannot-pause` | The app is not connected to the server: the keys stay off, Retry |
| `remote-04-refused` | A key the Flipper refused, with its status name |
| `remote-05-leave-resume` | Leaving after the remote paused the agent: Resume agent or Leave it paused |
| `remote-06-waiting-flipper` | A key's reply is late: the keys wait for the Flipper |
| `remote-07-finishing-transfer` | Pausing while the agent's transfer ends |
| `remote-08-no-server` | No server session: the screen offline, the keys off |
| `screens-05-settings` | Settings, with Agents under Connections: each agent and its state |
| `settings-language` | Language sheet in Settings |
| `screens-06-relay` | Relay running |
| `screens-07-relay-connecting` | Relay connecting |
| `screens-08-relay-error` | Relay stopped by an error |
| `screens-09-relay-stopped` | Relay stopped |
| `relay-locked` | Relay waiting for a locked Flipper |
| `relay-bond-lost` | Relay after the Bluetooth bond was lost |
| `relay-warnings` | Relay with the battery and notification warnings |
| `screens-10-relay-server` | Relay server: URL and Phone token |
| `screens-11-relay-devices` | Choosing the Flipper among the paired devices |
| `server-orientation` | Screen orientation sheet in Server |
| `screens-13-diagnostics` | Diagnostics summary |
| `screens-14-log` | Full relay log |
| `log-server` | Server tab of the log |
| `about` | About and third-party licenses |
| `pinkey-01-pair-camera` | Pairing by QR code: it holds the server's key and a code that changes every 30 s; Paste the pairing text instead |
| `pinkey-02-pair-checking` | After scanning: the server's key is checked before the code is sent |
| `pinkey-03-pair-waiting` | The request waits for the owner: the same number on the phone and the server, the server, its key and this phone |
| `pinkey-04-pair-refused` | The owner refused on the server |
| `pinkey-05-pair-expired` | Nobody confirmed in time |
| `pinkey-06-pair-code-used` | Another phone already used the code |
| `pinkey-07-pair-code-not-current` | Not a code the server is showing now |
| `pinkey-07b-pair-code-expired` | The code rotated out |
| `pinkey-08-pair-key-mismatch` | The server at that address does not have the QR code's key: nothing was sent |
| `pinkey-09-pair-old-qr` | A QR code from an older server (version 1 or 2) |
| `pinkey-10-pair-tls-failed` | No secure connection: nothing was sent, never a fallback to cleartext |
| `pinkey-11-pair-not-finished` | The connection dropped after the tokens left: start again |
| `pinkey-12-pair-replace` | A QR code for another server asks before replacing a working pairing, with the new server's key |
| `pinkey-12b-paste-empty` | Paste the pairing text, before pasting |
| `pinkey-12c-paste-filled` | The pasted text: its server and key, never its code |
| `pinkey-12d-paste-error` | A pasted text that is not a pairing this app can use |
| `pinkey-13-server` | Server settings, with the server's key |
| `pinkey-14-server-key-changed` | The server's key changed: nothing is sent until the owner pairs again |
| `pinkey-15-server-pair-again` | A pairing from before the key: pair again |
| `pinkey-16-settings-key-changed` | Settings with the key changed |
| `pinkey-17-settings-pair-again` | Settings with a pairing to redo |
| `pinkey-18-home-key-changed` | Home with the key changed |
| `pinkey-19-home-pair-again` | Home with a pairing to redo |
| `pinkey-20-conversation-key-changed` | A conversation with the key changed |
| `pinkey-21-relay-key-changed` | Relay with the key changed: a hard stop, no retries |
| `pinkey-22-relay-pair-again` | Relay with a pairing to redo |
| `pinkey-23-relay-server-other` | Relay server changes only the paired server's Phone token: another server needs its own pairing |
| `conversations-01-list` | Conversations grouped by day, with a pinned one |
| `conversations-02-favorites` | Favorites tab, not grouped |
| `conversations-03-filters` | Filter and sort sheet |
| `conversations-05-delete` | Deleting one conversation asks first and says what goes |
| `settings-agent` | Settings, Agent: where new conversations start (the agent, its model, effort and mode) and the time to answer a permission |
| `settings-agent-02-notifications` | Settings, Agent scrolled to the end: the Notifications card (when a turn ends, when the agent asks for permission) |
| `settings-agent-03-notifications-off` | The same with notifications off in Android: a warning with Allow notifications, the switches dimmed |
| `agents-01-list` | Settings, Agents: every agent on the server with its state, version and login |
| `agents-01b-list-ready` | The same, with both agents ready (the README's screens image) |
| `agents-02-claude` | One agent's page: Claude Code's version, login and Remote Control |
| `agents-03-opencode` | OpenCode's page: its free providers and what they keep; no Remote Control |
| `agents-04-not-signed-in` | An agent that is not signed in: why, and what to run on the server |
| `home-03-permission` | A pending permission answered from Home's card: Tool, Command, the rules Always allow saves, Allow once, Always allow, Deny, Open the conversation |
| `home-03b-permission-long` | The same with a long command on several lines: shown whole in a box that scrolls, and the card scrolls when it does not fit |
| `conversation-01-chat` | A conversation: settings in the app bar, the Flipper screen small, a clean composer |
| `conversation-02-large` | The same with the Flipper screen large (tap to toggle; the app remembers it) |
| `conversation-03-settings` | Conversation settings unfolding from the app bar: model, the effort dial, permission mode and Delete this conversation |
| `conversation-03b-model` | Choosing the model: every model the agent lists, in its order and by its name; one the plan does not include is dimmed with its reason and cannot be chosen |
| `conversation-03c-effort` | Dragging the effort dial to Max |
| `conversation-03d-mode` | Choosing the permission mode |
| `conversation-04-permission` | The agent asks for permission in Manual mode, with the rules Always allow saves |
| `conversation-04b-permission-long` | A long command on several lines: shown whole, selectable, in a box that scrolls, with every rule |
| `conversation-05-auto-lock` | An action blocked by automatic mode |
| `conversation-06-attach` | The Attach sheet: Take photo, Gallery, File and Flipper screen |
| `conversation-07-attachments` | A sent message with a photo, a screen capture and a file, and the next message's attachments, one uploading |
| `conversation-08-typing` | Typing: the small screen hides while the keyboard is open |
| `conversation-08b-typing-long` | A draft of several lines: the field's corners stay clear of the text, the clip and send by the last line |
| `conversation-09-new` | A new conversation, before the first message |
| `conversation-10-landscape` | Landscape: the Flipper screen large on the left, the conversation on the right |
| `conversation-10b-settings-landscape` | Landscape: the settings panel under the bar, scrolling when it does not fit |
| `conversation-11-busy` | The agent busy in another conversation, with Open it and Stop it |
| `conversation-12-markdown` | The agent's Markdown drawn with style: heading, table, quote, separator, task and nested lists, code with Copy, a link |
| `conversation-12b-link` | A tapped link shows its address and asks: Open in the browser or Copy the link |
| `conversation-13-delete` | Deleting the open conversation asks first; a running turn stops before |
| `conversation-14-remote-row` | Conversation settings with the Remote Control row (only when the server offers it; it says who keeps the conversation while it is on) |
| `conversation-15-remote-on` | Remote Control on: its icon in a blue pill in the app bar; what came from another device carries the icon in its meta line; the reply comes whole when the turn ends; no clip |
| `conversation-15b-remote-large` | The same with the Flipper screen large |
| `conversation-16-remote-permission` | A permission waiting for its answer on another device |
| `conversation-17-remote-off` | Turning it off while the agent works asks first |
| `conversation-17b-remote-settings` | The settings while it is on: model, effort and mode stay as they are |
| `conversation-18-remote-ended` | Ended by itself (here a server restart): a notice, and the conversation goes on |
| `conversations-06-remote` | The Conversations list: the icon before the star |
| `home-09-remote` | Home's card: the icon after the title |
| `conversation-19-long-paste` | A paste over the 16,000-character limit becomes the chip `pasted-text.txt` with its character count, and a line says why; the field stays empty |
| `conversation-19b-long-paste-text` | The field held a question: it keeps it, and the paste goes to the chip, here still uploading |
| `conversation-19c-long-paste-sent` | Sent: the message carries the file chip (`TXT` and its size), and the agent reads the file |
| `conversation-19d-long-paste-remote` | Remote Control on, with an older server where no file can go: the text stays in the field, the error states the limit and the count, and Send waits |
| `conversation-19f-long-paste-remote-file` | Remote Control on, with a server that takes attachments there (the agent's `remote.attachments`): the paste becomes the chip as with it off, and the attach button shows |
| `conversation-19e-too-long` | Typed past the limit: the same error without the Remote Control sentence, and Send waits |
| `conversation-20-model-unavailable` | A conversation whose model is no longer available: a warning with the reason and Choose a model, and the composer waits |
| `agent-01-new-settings` | A new conversation's settings: the Agent row first, which can change until the first message |
| `agent-02-list` | The Agent list: each agent with its state, version and login |
| `agent-02b-list-not-signed-in` | An agent that is not signed in: dimmed, with why, and it cannot be chosen |
| `agent-02c-list-not-set-up` | An agent not set up on the server |
| `agent-03-opencode-settings` | A conversation with OpenCode: the Agent row kept, no dial (its models list no levels), no Remote Control |
| `agent-03b-claude-settings` | The same with Claude Code: the dial and the Remote Control row |
| `agent-04-models-images` | OpenCode's models: which take images (they see the Flipper's screen) and which are text only |
| `agent-04c-models-paid` | A paid provider's model says "Paid" next to "Text only" (free only off) |
| `agent-04b-opencode-modes` | The modes OpenCode offers |
| `agent-05-new` | A new conversation: the Agent card, with the last agent chosen |
| `agent-05b-new-opencode` | The same when the last one chosen was OpenCode |
| `agent-05c-new-not-ready` | The last agent chosen is not ready: a warning with Choose an agent, and the composer waits |
| `select-01-one` | Conversations: a long press selects one; the bottom bar acts on it (Pin, Favorite, Rename, Copy ID, Delete) |
| `select-02-several` | Several selected: Pin, Favorite and Delete for all of them |
| `select-03-all` | Select all: every conversation the list shows |
| `select-04-delete` | Deleting several asks first: each title with its messages, the totals, and a running turn stops first |
| `select-05-partly-deleted` | Some could not be deleted: they stay selected, with why and Try again |
| `select-06-no-delete` | A server that cannot delete: no Delete |
| `select-07-search` | Selecting while searching: Select all takes the matches |
| `select-08-favorites` | Selecting in Favorites: Unfavorite takes them out of the tab |
| `character-01-faces` | Zeffin's character: its ten faces on a dark surface, and on the orange bar |
| `character-02-scenes` | The character's tool scenes (the screen, its button, a folder, a loading bar, thinking) and the end of a turn |
| `notify-07-icon` | The notification small icon: the logo in one color (its Z included), in white and in the shade's orange circle |
| `home-10-working` | Home's card while a tool runs: the character presses its button, "The agent is working" |
| `home-11-connecting` | The relay connecting: the screen without an image shows the antenna, the bottom bar's face looks up |
| `conversation-21-working` | The chat's tail while the agent presses keys: the character and "Pressing DOWN, OK" |
| `conversation-22-finished` | Just after a turn ends well: "The agent finished", the eyes smiling |
| `conversation-23-failed` | A failed turn: the character beside the error's words |
| `conversations-07-empty` | Conversations with none: the character above the hint |

Mockups of what Android draws (notifications; `mockups/notifications.py`). They stay as mockups: the app cannot screenshot the notification shade or the lock screen.

| File | Screen |
|---|---|
| `notify-01-working` | The ongoing, silent "The agent is working" while a turn runs with the app off screen |
| `notify-02-finished` | A turn finished: the conversation's title and the start of the agent's reply |
| `notify-03-stopped-error` | A stopped turn and a failed one |
| `notify-04-permission` | A permission request in the shade: Allow once (asks to unlock) and Deny, with the time left |
| `notify-04b-plan` | A plan to approve: no buttons, a tap opens the conversation |
| `notify-05-lock` | The lock screen: no command and no text, only Deny |
| `notify-06-popup-permission` | Phone unlocked, another app open: the permission pops up at the top with its buttons |
| `notify-06c-icon-finished` | A finished turn does not pop up: only the icon in the status bar and the sound |
