Zeffin's character: the launcher icon's body (a small body with a screen and a round button) with two round eyes on its screen, drawn from vectors in the icon's 108 dp shapes. The Z wink is only the logo's (the launcher icon, the splash and the notification icon): inside the app the character has no Z. It shows the state at a glance; it never replaces a word, and every state keeps its words beside it. In Compose it is `Character` (`ui/components/Character.kt`); `PresentCharacter` (`ui/present/`) chooses the face.

## Looks

- **On a dark surface** (cards, the chat, the remote): an `accent` body, a `surface` screen and an `accent` face. The button is a hole that shows the surface behind it (`background`).
- **On the orange app bar** (`onAccent`): the icon's own look, an `on-accent` body with the screen in the bar's `accent` and an `on-accent` face. Always still.

## Faces

Both eyes carry the expression, and move together when the face looks somewhere.

| Face | When | Eyes |
|---|---|---|
| Active | Gate `ACTIVE`, connected, no turn running | Round |
| Working | A turn runs and a tool is in use (a `tool` event without its result), or the turn started and nothing came yet | Round, looking toward the button; the tool's scene |
| Writing | The agent's text is arriving | Looking up, three dots below |
| Asking permission | A permission or a plan waits (`turn.waiting`) | Wide open |
| Paused | Gate `PAUSED` | Closed, z's rising beside it |
| Stopped | Gate `STOPPED`, or a turn the owner stopped | Flat lines |
| Finished | The last turn ended well | Smiling |
| Error | A failed turn, App token rejected, the server's key changed, a key the Flipper refused | Crosses |
| Connecting | The relay connects or retries, or the app connects to the server | Looking up, an antenna with a blinking light |
| Offline | No relay, no server, Tailscale not connected, not paired, a pairing to redo | Empty; the body at 45 % |

One scope per place: Home's app bar shows the shared device (the pairing, the connection, the relay and the gate); Home's card and the chat show one conversation's turn. A conversation's bar puts the device's trouble first (offline, error, connecting), then what the agent does there, then the gate.

## Placement and sizes

Heights from the spacing tokens; the width is 17/13 of the height (`CharacterSize`).

| Where | Height | Moves |
|---|---|---|
| Home's app bar, before the title (8 dp to it); a conversation's bar, after Back | 24 dp (`space-6`) | No |
| The chat's tail, 14 dp margins, 12 dp to its line | 32 dp (`space-8`) | Yes |
| Home's card, in its head (12 dp padding, 12 dp to the title) | 48 dp (`space-12`) | Yes |
| The remote's state space, Home's screen without an image, the empty states; 12 dp (8 dp above a badge) to the text | 64 dp (2 × `space-8`) | Yes |

The bottom bar's icons are faces at 24 dp, filled for the active destination and outlined for the others: Home is the screen with two eyes (they look up while connecting and are empty with no relay), Conversations a speech bubble with two eyes, Settings the screen with two short lines. Labels, colors and the pill stay as they are.

## Tool scenes

While a turn runs, the chat's tail shows the character acting out the last tool without a result, and a line in words (`body`, `ink-muted`) with what came from the agent or the Flipper in `mono`, quoted. Tool names come without their `mcp__flipper__` prefix.

| Scene | Tools | Line |
|---|---|---|
| Looks at the screen | `flipper_screen`, `flipper_wait_screen` | Looking at the screen · Waiting for the screen to change |
| Presses its button | `flipper_press`, `flipper_watch` | Pressing DOWN, DOWN, OK · Pressing and watching the screen |
| Opens a folder | `flipper_list`, `flipper_stat`, `flipper_read`, `flipper_write`, `flipper_mkdir`, `flipper_delete`, `flipper_rename` | Opening the folder "/ext/infrared" · Reading "TV.ir" |
| A loading bar | `flipper_app_start`, `flipper_app_load_file`, `flipper_app_button`, `flipper_app_exit` | Opening "Infrared" · Closing the app |
| Thinks | any other: `flipper_app` (it reads the catalog), `flipper_status`, `flipper_device_info`, `flipper_firmware`, `flipper_power_info`, `flipper_rpc`, `flipper_events`, `flipper_note` (it saves a note on the server), the agent's own tools | Using `flipper_status` · before the first tool: The agent is working |

When the turn ends well the tail says "The agent finished", bounces with smiling eyes, and goes 3 s later. A failed turn keeps its own line in the chat.

## Animations

They play only while their state lasts and the face is on screen (the screen on, the app in the foreground, the face in view).

| Face | Repeat | Phases (Compose easing) |
|---|---|---|
| Active: a blink | Loop, 3.5 s | open 3290 ms; close 70 (`FastOutLinearInEasing`); closed 70; open 70 (`LinearOutSlowInEasing`) |
| Working: looking around | Loop, 4 s | right 1300; to the left 700 (`FastOutSlowInEasing`); left 1300; back 700 (`FastOutSlowInEasing`) |
| Writing: typing dots | Loop, 1.2 s | each dot up 180 and down 180 (`EaseInOutSine`), 240 apart; rest |
| Asking permission: a hop | Loop, 1.4 s | rest 980; crouch 140 (`FastOutSlowInEasing`); up 140 (`LinearOutSlowInEasing`); land 140 (`FastOutLinearInEasing`) |
| Paused: z's | Loop, 2.4 s | each z fades in 300 (linear), rises over the loop (`LinearOutSlowInEasing`), fades out in its last 600; the second 1200 later |
| Finished: a bounce | Once, 1.12 s | up 320 (`FastOutSlowInEasing`); down 320 (`FastOutLinearInEasing`); a small hop 480 |
| Error: a head shake | Once, 600 ms | left, right, left, right, middle (`EaseInOutSine`) |
| Connecting: the antenna | Loop, 1.8 s | light on 900, off 900; the eyes scan left and right (sine) |
| Scenes | Loop | the screen 1.6 s, the button 1 s (in 120 ms), the folder 1.2 s, the bar 2 s, the thoughts 2.4 s |

Stopped and offline never move. A face that plays once plays again for each new event (a new turn's end).

**Transitions:** what moves slides (the face 200 ms `FastOutSlowInEasing`, the antenna 200 ms `FastOutLinearInEasing`, the dimming 300 ms linear), and what changes shape cross-fades in 150 ms (`FastOutSlowInEasing`).

## Motion and accessibility

- **No motion:** with Android's "Remove animations" (animator duration scale 0) every face draws its still frame, and every change is a cut. Screenshot tests draw the same still frame (`LocalStillFrames`). The still frame is the mockups' frame 0: the round eyes, the dots level, both z's, the antenna lit, the eyes wide at rest.
- **TalkBack:** the character is decoration where a word says the state (every place above). Where it stands alone, `description` gives it the state in words, such as "The agent is paused".

## Notifications and the splash

- Every notification's small icon (`ic_relay` in the relay module) is the logo in one color, with its Z: the launcher icon's shapes at a third of their size. The permission's notification uses the same one; its text asks.
- The splash is the system's: the launcher icon on the black window. Nothing of the app's own.
