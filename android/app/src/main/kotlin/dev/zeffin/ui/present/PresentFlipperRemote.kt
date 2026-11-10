// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.Conn
import dev.zeffin.relay.FlipperKey
import dev.zeffin.relay.KeyRefusal
import dev.zeffin.relay.KeysOff
import dev.zeffin.relay.Problem
import dev.zeffin.relay.RelayState
import dev.zeffin.relay.RemoteKeys
import dev.zeffin.relay.ServerSide
import dev.zeffin.relay.Stage

/**
 * What the remote did to the agent's gate since it opened. The route keeps it and sends
 * what [next] asks; the presenter words it. Opening the remote pauses an active agent once, with `via: "remote"`; an
 * agent already paused is left as it is; the remote never resumes by itself.
 */
data class RemoteGate(
    /** The first look at the gate is done: a pause was sent, or the agent was already paused or stopped. */
    val started: Boolean = false,
    /** When the remote last sent a pause; null when it sent none. */
    val sentAt: Long? = null,
    /** The gate read paused after the remote's pause, by the remote: leaving offers Resume. */
    val pausedByRemote: Boolean = false,
    /** The gate read paused while the remote was open, then active again: someone resumed it elsewhere. */
    val resumedElsewhere: Boolean = false,
    private val sawPaused: Boolean = false,
) {
    /** The state after a new look at the link, and whether to send a pause now. */
    fun next(link: AppLinkState, nowMs: Long): Pair<RemoteGate, Boolean> {
        val status = link.status
        val gate = status?.gate.takeIf { link.conn == Conn.CONNECTED }
        return when {
            gate == null -> this to false
            !started && gate == "active" -> copy(started = true, sentAt = nowMs) to true
            // A stopped agent has nothing to pause: the first look waits for it to be active or paused, so a pause is
            // still sent if it is resumed while the remote is open (or the "stopped" was kept from before /app's status).
            !started && gate == "stopped" -> this to false
            !started -> copy(started = true, sawPaused = gate == "paused") to false
            gate == "paused" -> copy(sawPaused = true, resumedElsewhere = false,
                pausedByRemote = pausedByRemote || (sentAt != null && status?.gateBy == REMOTE)) to false
            gate == "active" -> copy(resumedElsewhere = sawPaused, pausedByRemote = false) to false
            else -> this to false
        }
    }

    /** Retry or Pause agent: one more pause, now. */
    fun pauseAgain(nowMs: Long): RemoteGate = copy(started = true, sentAt = nowMs, resumedElsewhere = false, sawPaused = false)

    /** Leaving offers Resume only when the remote paused the agent and it is still paused by the remote. */
    fun offersResume(link: AppLinkState): Boolean =
        pausedByRemote && link.conn == Conn.CONNECTED && link.status?.gate == "paused" && link.status.gateBy == REMOTE

    companion object {
        /** The gate's "via" and "gate_by" for the remote (AppProtocol.GateVia). */
        const val REMOTE = "remote"
        /** A pause not seen in status within this is said so, with Retry. */
        const val PAUSE_WAIT_MS = 5_000L
    }
}

/**
 * What the remote's state is made of. [showRefusal]: [refused] is newer than the last key pressed. [paired]: the app has a
 * server's pairing (without one, the remote still works over the relay's link).
 */
data class RemoteInputs(
    val relay: RelayState,
    val link: AppLinkState,
    val gate: RemoteGate,
    val keys: RemoteKeys,
    val refused: KeyRefusal?,
    val showRefusal: Boolean,
    val hasFrame: Boolean,
    val nowMs: Long,
    val paired: Boolean = true,
)

/**
 * The agent's state as the remote shows it. [NO_SESSION]: the relay has no server session, so it holds the Flipper for the
 * remote alone and the agent cannot act; the keys work once the relay reads the stream. [RELAY_OFF]: nothing holds it.
 */
enum class RemoteAgent { RELAY_OFF, NO_SESSION, CANNOT_PAUSE, PAUSING, NOT_PAUSED, RESUMED, PAUSED_BY_REMOTE, PAUSED }

/** One line under the screen; [code] is device data (a status name), shown in mono after the text. */
data class RemoteLine(val tone: Tone, val text: String, val code: String? = null)

enum class RemoteAction { RETRY, PAUSE, START_RELAY }

data class FlipperRemoteUi(
    val title: String,
    val subtitle: String,
    val agent: RemoteAgent,
    val screenMode: ScreenMode,
    /** At most two: the agent's state, then the keys' (waiting, a refused key). */
    val lines: List<RemoteLine>,
    val action: RemoteAction?,
    val actionLabel: String?,
    val keysOn: Boolean,
    /** The character above the lines (and in the bar): asleep while the agent is paused, looking for a signal while
     *  the remote waits, empty without a session, shaking its head at a refused key. */
    val face: Face = Face.ACTIVE,
)

/** The remote's words, in both languages, and how its state reads. No Compose here. */
class PresentFlipperRemote(private val l: Lang) {
    private fun t(en: String, es: String) = l.t(en, es)
    private val hold = HoldWords(l)

    val title: String get() = t("Remote", "Mando")
    val homeButton: String get() = t("Remote", "Mando")
    val hint: String get() = t("Tap for a short press. Hold for a long press.", "Toca para una pulsación corta. Mantén para una larga.")

    fun keyName(key: FlipperKey): String = when (key) {
        FlipperKey.UP -> t("Up", "Arriba")
        FlipperKey.DOWN -> t("Down", "Abajo")
        FlipperKey.LEFT -> t("Left", "Izquierda")
        FlipperKey.RIGHT -> t("Right", "Derecha")
        FlipperKey.OK -> "OK"
        FlipperKey.BACK -> t("Back", "Atrás")
    }

    // The sheet when leaving after the remote paused the agent
    val leaveTitle: String get() = t("Resume the agent?", "¿Reanudar el agente?")
    val leaveText: String get() = t("You paused it to use the remote. It stays paused until you resume it.",
        "Lo pausaste para usar el mando. Sigue pausado hasta que lo reanudes.")
    val leaveResume: String get() = t("Resume agent", "Reanudar agente")
    val leaveKeep: String get() = t("Leave it paused", "Dejarlo pausado")

    /** The remote's state, from what the relay, the link and the remote's own gate say. */
    fun ui(i: RemoteInputs): FlipperRemoteUi {
        val agent = agentOf(i)
        val paused = agent == RemoteAgent.PAUSED_BY_REMOTE || agent == RemoteAgent.PAUSED
        val alone = agent == RemoteAgent.NO_SESSION
        val off = (i.keys as? RemoteKeys.Off)?.why
        val first = if (paused && off == KeysOff.SERVER_COMMAND_OPEN) RemoteLine(Tone.WARNING,
            t("Pausing: finishing the agent's transfer. The keys come back when it ends.",
                "Pausando: termina la transferencia del agente. Las teclas vuelven cuando acabe."))
            else agentLine(agent, i)
        val second = when {
            paused -> keysLine(off, i)
            alone -> holdLine(off, i)
            else -> null
        }
        val action = actionOf(agent)
        return FlipperRemoteUi(
            title = title,
            subtitle = subtitle(i, agent),
            agent = agent,
            screenMode = if (i.hasFrame && agent != RemoteAgent.RELAY_OFF) ScreenMode.LIVE else ScreenMode.OFF,
            lines = listOfNotNull(first, second),
            action = action,
            actionLabel = action?.let(hold::actionLabel),
            keysOn = (paused || alone) && i.keys == RemoteKeys.Ready,
            face = faceOf(agent, off, listOfNotNull(first, second)),
        )
    }

    private fun actionOf(agent: RemoteAgent): RemoteAction? = when (agent) {
        RemoteAgent.CANNOT_PAUSE, RemoteAgent.NOT_PAUSED -> RemoteAction.RETRY
        RemoteAgent.RESUMED -> RemoteAction.PAUSE
        RemoteAgent.RELAY_OFF -> RemoteAction.START_RELAY
        else -> null
    }

    /** The keys' line while the relay holds the Flipper for the remote alone, or gets it. */
    private fun holdLine(off: KeysOff?, i: RemoteInputs): RemoteLine? = when (off) {
        KeysOff.NO_SESSION -> hold.gettingLine(i)
        KeysOff.READER_STOPPED -> RemoteLine(Tone.DANGER,
            t("The relay could not read the Flipper's stream: leave the remote and open it again.",
                "El relé no pudo leer el flujo del Flipper: sal del mando y vuelve a abrirlo."))
        else -> keysLine(off, i)
    }

    private fun agentOf(i: RemoteInputs): RemoteAgent {
        // AppLink keeps the last status while /app reconnects: the agent's state reads it only while /app is connected.
        val status = i.link.status?.takeIf { i.link.conn == Conn.CONNECTED }
        val session = i.relay.stage == Stage.RUNNING && i.keys != RemoteKeys.Off(KeysOff.NO_SESSION) && status?.gate != "stopped"
        return when {
            i.relay.stage == Stage.OFF -> RemoteAgent.RELAY_OFF
            !session -> RemoteAgent.NO_SESSION
            status == null -> RemoteAgent.CANNOT_PAUSE
            status.gate == "paused" -> if (i.gate.pausedByRemote) RemoteAgent.PAUSED_BY_REMOTE else RemoteAgent.PAUSED
            i.gate.resumedElsewhere -> RemoteAgent.RESUMED
            i.gate.sentAt != null && i.nowMs - i.gate.sentAt >= RemoteGate.PAUSE_WAIT_MS -> RemoteAgent.NOT_PAUSED
            else -> RemoteAgent.PAUSING
        }
    }

    /** The keys' line while the agent is paused: why they wait, or the last refused key. */
    private fun keysLine(off: KeysOff?, i: RemoteInputs): RemoteLine? = when (off) {
        KeysOff.WAITING_FLIPPER -> RemoteLine(Tone.NEUTRAL,
            if (i.link.status?.busy == true) t("Waiting for the Flipper: the agent's last call is still running.",
                "Esperando al Flipper: la última llamada del agente sigue en curso.")
            else t("Waiting for the Flipper to answer.", "Esperando a que el Flipper responda."))
        KeysOff.SYNCING -> RemoteLine(Tone.NEUTRAL, t("Waiting for the Flipper's screen.", "Esperando la pantalla del Flipper."))
        KeysOff.READER_STOPPED -> RemoteLine(Tone.DANGER,
            t("The relay could not read the Flipper's stream: the keys are off until it reconnects.",
                "El relé no pudo leer el flujo del Flipper: las teclas se apagan hasta que se reconecte."))
        null -> i.refused?.takeIf { i.showRefusal }?.let(::refusedLine)
        KeysOff.SERVER_COMMAND_OPEN, KeysOff.NO_SESSION -> null
    }

    /** "Demo · connected over BLE · agent paused", "Demo · connected over BLE · no server session", or "Demo · relay off". */
    private fun subtitle(i: RemoteInputs, agent: RemoteAgent): String {
        val status = i.link.status
        val device = i.relay.deviceName ?: status?.relay?.device ?: "Flipper"
        val alone = when {
            agent == RemoteAgent.RELAY_OFF -> t("relay off", "relé apagado")
            agent != RemoteAgent.NO_SESSION -> null
            i.relay.stage == Stage.REMOTE -> t("connected over BLE", "conectado por BLE") + " · " + t("no server session", "sin servidor")  // whole on one line
            else -> t("relay not connected to the server", "relé sin conexión con el servidor")
        }
        if (alone != null) return "$device · $alone"
        val agent = when (status?.gate) {
            null -> null
            "paused" -> t("agent paused", "agente pausado")
            "stopped" -> t("agent stopped", "agente parado")
            else -> t("agent active", "agente activo")
        }
        return listOfNotNull(device, t("connected over BLE", "conectado por BLE"), agent).joinToString(" · ")
    }

    private fun agentLine(agent: RemoteAgent, i: RemoteInputs): RemoteLine = when (agent) {
        RemoteAgent.RELAY_OFF -> RemoteLine(Tone.NEUTRAL,
            t("The relay is off: start it to use the remote.", "El relé está apagado: enciéndelo para usar el mando."))
        RemoteAgent.NO_SESSION -> RemoteLine(Tone.NEUTRAL, t("The agent cannot act now: ", "El agente no puede actuar ahora: ") + hold.whyNoSession(i) + ".")
        RemoteAgent.CANNOT_PAUSE -> RemoteLine(Tone.DANGER,
            t("The agent cannot be paused from here: the app is not connected to the server.",
                "No se puede pausar el agente desde aquí: la app no está conectada al servidor."))
        RemoteAgent.PAUSING -> RemoteLine(Tone.WARNING, t("Pausing the agent…", "Pausando el agente…"))
        RemoteAgent.NOT_PAUSED -> RemoteLine(Tone.DANGER,
            t("The agent did not pause. The keys stay off while it is active.",
                "El agente no se pausó. Las teclas siguen apagadas mientras esté activo."))
        RemoteAgent.RESUMED -> RemoteLine(Tone.WARNING,
            t("The agent was resumed. The keys stay off while it is active.",
                "Se reanudó el agente. Las teclas siguen apagadas mientras esté activo."))
        RemoteAgent.PAUSED_BY_REMOTE -> RemoteLine(Tone.WARNING,
            t("Paused by the remote. The agent cannot touch the Flipper.", "Pausado por el mando. El agente no puede tocar el Flipper."))
        RemoteAgent.PAUSED -> RemoteLine(Tone.WARNING,
            t("The agent is paused: it cannot touch the Flipper.", "El agente está pausado: no puede tocar el Flipper."))
    }

    /** The key and its status: a fixed protocol name (or its number), never text from the device. */
    private fun refusedLine(r: KeyRefusal): RemoteLine {
        val key = keyName(r.key)
        val text = if (r.long) t("Long $key refused by the Flipper:", "El Flipper rechazó $key largo:")
            else t("$key refused by the Flipper:", "El Flipper rechazó $key:")
        return RemoteLine(Tone.DANGER, text, r.statusName ?: r.status.toString())
    }
}

/** The remote's words without a server session (A2) and its action labels, apart so the presenter stays small. */
private class HoldWords(private val l: Lang) {
    private fun t(en: String, es: String) = l.t(en, es)

    fun actionLabel(a: RemoteAction): String = when (a) {
        RemoteAction.RETRY -> t("Retry", "Reintentar")
        RemoteAction.PAUSE -> t("Pause agent", "Pausar")  // as Home's button
        RemoteAction.START_RELAY -> t("Start the relay", "Encender el relé")
    }

    /** Why there is no server session, from what the relay last saw of the server and what `/app` says. */
    fun whyNoSession(i: RemoteInputs): String {
        val status = i.link.status?.takeIf { i.link.conn == Conn.CONNECTED }
        return when {
            i.relay.serverSide == ServerSide.STOPPED || status?.gate == "stopped" -> t("it is stopped", "está parado")
            !i.paired -> t("the app is not paired with a server", "la app no está emparejada con un servidor")
            i.relay.serverSide == ServerSide.REJECTED || i.relay.serverSide == ServerSide.KEY_CHANGED ->
                t("pair again with the server", "vuelve a emparejar con el servidor")
            i.relay.serverSide == ServerSide.NO_TUNNEL -> t("$net is not connected on this phone", "$net no está conectado en este móvil")
            i.relay.serverSide == ServerSide.OUTDATED -> t("the server needs an update (zf update)", "hay que actualizar el servidor (zf update)")
            i.relay.serverSide == ServerSide.BUSY -> t("another relay serves the server", "otro relé sirve al servidor")
            status != null -> t("the relay is not connected to the server", "el relé no está conectado al servidor")
            else -> t("no connection with the server", "sin conexión con el servidor")
        }
    }

    /** The relay does not hold the Flipper yet: it connects, waits for an unlock, or cannot get it. */
    fun gettingLine(i: RemoteInputs): RemoteLine = when {
        i.relay.stage == Stage.FLIPPER_LOCKED -> RemoteLine(Tone.WARNING,
            t("The Flipper is locked: unlock it to use the remote.", "El Flipper está bloqueado: desbloquéalo para usar el mando."))
        i.relay.problem == Problem.NOT_CHOSEN -> RemoteLine(Tone.WARNING,
            t("Choose the Flipper in Settings to use the remote.", "Elige el Flipper en Ajustes para usar el mando."))
        i.relay.problem in FLIPPER_PROBLEMS -> RemoteLine(Tone.DANGER,
            t("The relay cannot reach the Flipper: see the relay's screen.", "El relé no llega al Flipper: mira la pantalla del relé."))
        else -> RemoteLine(Tone.NEUTRAL, t("Connecting to the Flipper…", "Conectando con el Flipper…"))
    }

    private companion object {
        /** The relay cannot get the Flipper: the remote says so and points to the relay's screen. */
        val FLIPPER_PROBLEMS = setOf(Problem.BLUETOOTH_OFF, Problem.NO_PERMISSION, Problem.NOT_BONDED, Problem.BOND_LOST,
            Problem.FLIPPER_UNREACHABLE, Problem.NOT_A_FLIPPER, Problem.FLIPPER_DROPPED)
    }
}

/** The character in the state space: a refused key, no session, waiting, asleep, or the agent active. */
private fun faceOf(agent: RemoteAgent, off: KeysOff?, lines: List<RemoteLine>): Face {
    val paused = agent == RemoteAgent.PAUSED_BY_REMOTE || agent == RemoteAgent.PAUSED
    return when {
        agent == RemoteAgent.RELAY_OFF || agent == RemoteAgent.CANNOT_PAUSE -> Face.OFFLINE
        lines.any { it.tone == Tone.DANGER } -> Face.ERROR
        agent == RemoteAgent.NO_SESSION -> Face.OFFLINE
        agent == RemoteAgent.PAUSING || (paused && (off == KeysOff.WAITING_FLIPPER || off == KeysOff.SYNCING)) -> Face.CONNECTING
        paused -> Face.PAUSED
        else -> Face.ACTIVE
    }
}
