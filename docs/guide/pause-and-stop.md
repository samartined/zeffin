# Pause and Stop

The agent acts on the Flipper without asking: the Flipper tools never wait for a permission ([Use the agent](use-the-agent.md#permissions)). The operator's gate is how you hold it back. It has three states:

| State | What the agent can do | Where you set it |
|---|---|---|
| **Active** | Everything: read, press keys, start apps, write files, any RPC request | **Resume**, in the app's Home or the web view |
| **Paused** | Only read: the screen, the status, files, device and firmware information. Every tool that changes the Flipper's state is refused | **Pause agent**, in Home or the web view; opening the [remote](the-remote.md) |
| **Stopped** | Nothing: paused, and the phone's session is closed and refused until you resume | **Stop relay**, in the web view |

## Pause: the read-only mode

- **Pause agent** in Home (or the web view) pauses at once. A press, a write, an app or an RPC request is refused with "Control paused by the operator"; the agent is told not to retry and to wait for you.
- **It also applies mid-call:** writing tools check the gate again after waiting for the Flipper and between the frames of a long write, so after Pause nothing more is written.
- **Reading still works,** so the agent can look and tell you what it sees, and you keep the conversation going.
- **Pause is the only switch that stops the agent from changing the Flipper.** Nothing else does: not the permission mode, not the agent folder's instructions ([Security](security.md#what-zeffin-does-not-stop)).
- **Resume** gives the agent back its hands.

Home says who paused and when ("You paused the agent", "Agent paused from the web view"), and the agent's refused actions show as "Paused by the operator. Not sent".

## Stop: close the phone's session

**Stop relay** in the web view closes the phone's session and refuses the relay's reconnections until **Resume** (in the web view or in Home). Use it when you want the Flipper's link closed, not only the agent's hands held.

- Home says "Agent stopped · the relay waits for you to resume it".
- The phone's notification also has a **Stop**: that one stops the relay on the phone itself, and the gate does not change.

## Both survive a restart

The gate is kept in `state/gate.json`, so a Pause or a Stop holds after the server restarts. Every change goes to the audit log with who made it: the web view, the app or the remote.

## Stopping a turn is something else

The stop button of a conversation stops the agent's current **turn** (from the terminal, Ctrl+C in `zf chat`). It does not pause the agent: the next message starts a new turn with full access. To keep the Flipper safe, pause.
