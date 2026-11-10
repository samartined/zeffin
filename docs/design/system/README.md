Zeffin is the Android app and the server a person uses to drive their Flipper Zero through Claude Code: they see the device screen live, read what the agent does and can stop it at any time. This design system exists so the app is sober, readable with one hand and honest about the real state of the relay, the Flipper and the agent.

## Principles

- **The real state rules.** Every screen says which stage the relay is in (server, Flipper, relay) and whether the agent is active, paused or stopped. It is never guessed: if there is no new frame, the Flipper screen is marked as stale.
- **One hand, one glance.** The pause controls are always within thumb reach and at least 48px tall (`space-12`). Main text never goes below `body`.
- **Orange for the brand, blue for actions.** App bar and Flipper screen in orange, buttons and links in blue, black background and graphite cards.
- **Device data as data.** What comes from the Flipper (file names, screen text, RPC replies) is shown in `mono` inside a `surface-sunken` block, apart from the app's own voice.

## Content and tone

- Short sentences. Buttons use imperative verbs: "Search devices", "Pause agent", "Test server". The app is bilingual (English and Spanish); both languages follow these rules.
- States are written as words, not only as color: "Connected over BLE", "Agent paused", "Relay stopped".
- Errors give the cause and the next step in one sentence: "Token rejected: copy the Phone token again".
- No emoji, no exclamation marks, no capitals for emphasis. Technical names are written as they are: `Phone token`, `BLE`, `MTU`.
- Flipper data is quoted, not paraphrased: it goes in `mono`, in quotes when inside a sentence.

## Color

- **Dark theme only:** pure black `surface` background; cards, bottom bar and fields in `surface-raised` (#1C1C1C), with no border or shadow; `line` dividers inside cards.
- **Orange brand, blue action.** `accent` (#FF8200) is the app bar of every screen and the frame of the Flipper screen. Actions are blue: primary pill button in `action`, action rows with icon and text in `action`, and the Connected state. Destructive actions are an action row in `danger` ("Forget Flipper", "Stop relay").
- Text in `ink` (white); secondary in `ink-muted`; timestamps and hints in `ink-faint`.
- States: `success` running, `warning` paused and connecting, `danger` stopped and error, `action` connected and Claude turn in progress. Written as colored text and always with the word. The `-soft` colors are only for the user bubble (`info-soft`) and the failed tool card (`danger-soft`).
- On an orange, blue or red fill the text is black (`on-accent`, `on-action`, `on-danger`): white on that blue does not reach 3:1.
- Focus: solid 2px ring in `focus`, 2px offset, on every control.
- Gray text values are lightened where needed to meet WCAG contrast; each token's `usage` gives its ratio.

## Screen structure

- **Orange app bar** on every screen: `accent` fill, 56px tall, title in black `title`, back arrow and action icons in black at 24px. It only holds actions for that screen (search, new conversation, clear): never a shortcut to Settings, which is already in the bottom bar. On Home it shows the Flipper name and its state in black `caption`.
- **Tab switch** (`TabSwitch`) only on the orange app bar, to switch views inside a screen.
- **All content goes in cards**: `surface-raised` with `radius-md`, 14px from the edges and 14px apart, with the title inside (`heading`, 12px padding). No loose fields or lists on the black background. The groups of a list (Today, Yesterday) have their heading outside the card, in `heading`.
- **Inside a card**, four kinds of rows separated by `line`: information (`InfoCard`: gray label on the left, value on the right), setting (`SettingRow`: title, description, value or switch), action (`ActionRow`: icon and text in blue, or red if destructive) and underlined text field.
- **State is colored text** (`StateText`) inside a row or next to a card title. No chips, status pills or tinted cards.
- **Bottom sheets** (`Sheet`) to pick options or act on an item without leaving the screen: list filters, conversation actions (long press). `surface-raised` background, radius 16 on top, gray handle, title in `heading` and settings or action rows.
- **Panel** (`Panel`) for the conversation settings: it unfolds from the app bar that opens it, radius 16 on the bottom corners, and scrolls when it does not fit. Back closes a sheet or a panel, never the screen under it.
- **Favorites and pinned:** a yellow star (`warning`) next to the title marks a favorite; pinned items always go on top in their own "Pinned" group, with a gray pin.
- **Buttons** are filled pills only: one primary per screen (`primary`), and `warning`/`success` for Pause and Resume. No outlined buttons and no floating buttons.

## Navigation

- Fixed `surface-raised` bottom bar with three destinations: Home, Conversations and Settings. Label in 10px bold under a 24px icon; inactive ones in `ink-muted`; the active one with a rounded `line` background behind icon and label.
- The first destination doubles as the connection indicator: its label says "Connected" (in `action`), "Connecting…" or "No relay". It is state only: the relay controls live in Settings, Relay, and the agent controls on Home.

## Typography

- Roboto for everything and Roboto Mono (weight 500) for technical data: URLs, addresses, masked tokens, tool names and logs. The app bundles both fonts.
- Scale: `display` 24 bold, `title` 20 bold (app bar), `heading` 16 bold (cards, rows, buttons), `body` 14, `body-strong` 14 medium, `label` 12 medium, `caption` 12.
- Nothing below 12px except the bottom bar label (10px bold).

## Spacing and shapes

- Screen side margin and gap between cards: 14px. Card and row padding: 12px. A 4px grid for everything else.
- Pill buttons (`radius-pill`), at least 48px tall, `heading` text. Cards, fields and bubbles use `radius-md`. Small labels and thumbnails use `radius-sm`.
- No borders on cards and no shadows except `shadow-raised` on the chat field when it floats.

## The Flipper screen

- Outer 3px `screen-bezel` frame with `radius-lg`, a 6px gap, and inside it the orange `screen-paper` LCD with a 12px radius and 8px padding; `screen-pixel` pixels in black.
- Drawn from the 128x64 framebuffer at an integer scale, without smoothing.
- When offline the LCD turns `line` and the inner frame goes dark; if the frame is stale it dims to 40% and a black label with `warning` text says "Stale screen" with its age.

## Icons

- Material-style icons, 24px, in the color of the text they go with. In the bottom bar, the filled variant for the active destination and the outlined one for the others. The stroke icons in the mockups use stroke width 2 to match that weight.
- Never an icon alone where a word fits, except in the app bar (back, search, new, clear) with its accessible label.

## The character

- Zeffin's character is the launcher icon's body with two round eyes on its screen; the Z wink is only the logo's (the launcher icon, the splash and the notification icon). One face per state (active, working, writing, asking permission, paused, stopped, finished, error, connecting, offline), drawn from vectors at 24, 32, 48 or 64 px tall. On dark surfaces it is orange with a black screen; on the orange app bar it takes the icon's own black look and stays still.
- It never replaces a word: every state keeps its words beside it, and TalkBack reads those. While the agent works it acts out the tool in use, with a line in words.
- Its short animations play only while their state lasts and the face is on screen; with Android's "Remove animations" each draws one still frame. The details are in `components/Character.md`.

## Interface states

- **Relay:** stopped, connecting to the server, connecting to the Flipper, active, retrying with a countdown, error. Shown in the Home app bar and, with its stage, in the State card of Settings, Relay.
- **Agent:** active (`success`), paused (`warning`), relay stopped (`danger`); turn in progress (`action`), with the send button turned into an interrupt square.
- **Screen:** live, stale, offline.
- **Device selection:** the device in use says "In use" in `action`; the others show the "Use" button. With none chosen, the Device row of Settings, Relay says "Not chosen" in `danger`.
