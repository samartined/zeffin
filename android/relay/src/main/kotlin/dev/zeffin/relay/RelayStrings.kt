// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.Lang
import dev.zeffin.core.NoTunnel
import dev.zeffin.core.CoreGraph
import java.util.Locale

/**
 * Everything the relay says, in one language: the state for the screens and the notification,
 * the failures, and the log lines. [En] also words the close reasons sent to the server.
 */
interface RelayStrings {
    val lang: Lang

    // ---- state
    fun stage(stage: Stage): String
    fun problem(problem: Problem, detail: Detail?): String
    val notificationTitle: String
    val stopAction: String
    val channelName: String
    val channelDescription: String
    fun running(name: String, duration: String): String
    fun retryIn(duration: String): String
    /** The notification while the private network's tunnel is down. */
    val waitingForNetwork: String
    /** Why nothing was sent: the cause, for the details and the log. */
    fun noTunnel(why: NoTunnel.Why): String

    // ---- what went wrong
    fun net(error: NetError): String
    fun gatt(status: Int): String
    fun op(op: GattOp): String
    fun flipper(failure: FlipperFailure): String
    fun sessionEnd(end: SessionEnd): String
    fun level(level: LogLevel): String

    // ---- log lines
    val logStartedByUser: String
    val logStarted: String
    val logStopped: String
    val logServerOutdated: String
    fun logServer(error: NetError): String
    val logOperatorStopped: String
    fun logOtherRelay(app: String?): String
    val logBusy409: String
    val logStopped423: String
    fun logRefused(code: Int): String
    val logSocketOpen: String
    val logRunning: String
    fun logTraffic(fromFlipper: String, toFlipper: String): String
    fun logSessionEnded(after: String, end: SessionEnd): String
    val logFlipperWaiting: String
    val logFlipperConnecting: String
    fun logFlipperConnected(name: String, mtu: Int): String
    val logFlipperLocked: String
    val logFlipperUnlocked: String
    fun logFlipper(failure: FlipperFailure): String
    val logTokenRejected: String
    fun logRetry(attempt: Int, wait: String): String
    val logLinkShared: String
    val logMtuSkipped: String
    fun logMtu(mtu: Int): String
    val logMtuNoAnswer: String
    fun logMtuFailed(status: Int): String
    fun logMtuChanged(mtu: Int): String
    val logFastConnection: String
    val logFastConnectionRefused: String
    /** One RPC frame: its size, how long BLE took to write it, and when the Flipper's first bytes came back. */
    fun logRpcTiming(bytes: Int, writeMs: Long, replies: List<Pair<Long, Int>>, more: Boolean, largeChunks: Int, largeBytes: Int): String
    val logNotifications: String
    val logBackgroundStartRefused: String
    // the remote's keys and its reading of the stream
    val logRemoteSettleLate: String
    fun logRemoteStopped(why: ReaderStop): String
    fun logKeyRefused(key: FlipperKey, long: Boolean, status: String): String
    fun logKeyLate(key: FlipperKey): String
    fun logKeysDropped(count: Int): String
    fun logKeyNotSent(key: FlipperKey): String
    /** The remote opened without a server session: the relay holds the Flipper for it and does not dial /phone. */
    fun logRemoteHold(name: String): String
    /** The remote closed: the relay stopped its own stream and goes on with its cycle. */
    val logRemoteReleased: String

    // ---- numbers
    /** "12 s", "4 min", "1 h 5 min". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }

    /** One decimal with the language's separator. */
    fun decimal(value: Double): String

    /** "980 B", "1.4 KB" / "1,4 KB", "37 KB", "1.2 MB". */
    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 10 * 1024 -> "${decimal(n / 1024.0)} KB"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> "${decimal(n / (1024.0 * 1024))} MB"
    }

    /** The notification line. */
    fun notification(s: RelayState, now: Long): String {
        val name = s.deviceName ?: "Flipper"
        val problem = s.problem?.let { problem(it, s.detail) }
        if (s.problem == Problem.NO_TUNNEL) return waitingForNetwork
        return when (s.stage) {
            Stage.RUNNING -> running(name, duration(now - (s.sessionStartedAt ?: now)))
            Stage.REMOTE -> "${stage(s.stage)} · $name"
            Stage.RETRYING -> retryIn(duration(((s.nextRetryAt ?: now) - now).coerceAtLeast(0))) + (problem?.let { " · $it" } ?: "")
            else -> stage(s.stage) + (problem?.let { " · $it" } ?: "")
        }
    }

    /** Milliseconds until [notification]'s text changes by the clock alone (a session's uptime, the retry
     *  countdown), as [duration] words it; null when only a new state changes it. */
    fun untilTextChanges(s: RelayState, now: Long): Long? = when (s.stage) {
        Stage.RUNNING -> s.sessionStartedAt?.let { (now - it).coerceAtLeast(0) }?.let { up ->
            val unit = if (up < 60_000) 1_000L else 60_000L
            unit - up % unit
        }
        Stage.RETRYING -> s.nextRetryAt?.let { it - now }?.takeIf { it > 0 }?.let { left ->
            val unit = if (left < 60_000) 1_000L else 60_000L
            left % unit + 1  // the countdown shows whole units, rounded down
        }
        else -> null
    }

    fun detail(d: Detail): String = when (d) {
        is Detail.Http -> "HTTP ${d.code}"
        is Detail.OtherApp -> d.version
        is Detail.Net -> net(d.error)
        is Detail.Flipper -> flipper(d.failure)
        is Detail.Session -> sessionEnd(d.end)
    }

    companion object {
        fun of(lang: Lang): RelayStrings = if (lang == Lang.ES) Es else En
    }

    object En : RelayStrings {
        override val lang = Lang.EN

        override fun stage(stage: Stage) = when (stage) {
            Stage.OFF -> "Stopped"
            Stage.RUNNING -> "Running"
            Stage.REMOTE -> "Remote in use"
            Stage.CHECKING_SERVER, Stage.CONNECTING_FLIPPER, Stage.OPENING_SESSION -> "Connecting…"
            Stage.WAITING_FLIPPER -> "Waiting for the Flipper"
            Stage.FLIPPER_LOCKED -> "Flipper locked"
            Stage.WAITING_SERVER -> "Waiting for the server"
            Stage.RETRYING -> "Retrying"
            Stage.ERROR -> "Error"
        }

        override fun problem(problem: Problem, detail: Detail?) = when (problem) {
            Problem.NOT_CONFIGURED -> "The server or the Phone token is missing"
            Problem.NOT_CHOSEN -> "Choose the Flipper in Device"
            Problem.TOKEN_REJECTED -> "The server does not accept the Phone token"
            Problem.SERVER_OUTDATED -> "The server is too old: update it with zf update"
            Problem.SERVER_UNREACHABLE -> "The server does not answer"
            Problem.NO_TUNNEL -> "$NET is not connected on this phone"
            Problem.KEY_CHANGED -> "The server's key changed"
            Problem.NEEDS_PAIRING -> "Pair again"
            Problem.OPERATOR_STOPPED -> "The agent was stopped from the web view or the app"
            Problem.OTHER_RELAY -> if (detail is Detail.OtherApp) "Another relay is connected (app ${detail.version})"
                else "Another relay is connected (probably the older relay app)"
            Problem.OWN_STALE -> "The server is still closing the previous session"
            Problem.BLUETOOTH_OFF -> "The phone's Bluetooth is off"
            Problem.NO_PERMISSION -> "The nearby devices permission is missing"
            Problem.NOT_BONDED -> "The Flipper is not paired with this phone"
            Problem.BOND_LOST -> "The Flipper no longer recognizes this phone"
            Problem.FLIPPER_UNREACHABLE -> "The Flipper does not answer: bring it closer and turn on its Bluetooth"
            Problem.NOT_A_FLIPPER -> "The chosen device is not a Flipper"
            Problem.FLIPPER_DROPPED -> "The connection with the Flipper dropped"
            Problem.SESSION_ENDED -> "The session with the server ended"
        }

        override val notificationTitle = "Zeffin relay"
        override val stopAction = "Stop"
        override val channelName = "Relay"
        override val channelDescription = "State of the relay between the Flipper and the server"
        override fun running(name: String, duration: String) = "Running · $name · $duration"
        override fun retryIn(duration: String) = "Retry in $duration"
        override val waitingForNetwork = "Waiting for $NET"
        override fun noTunnel(why: NoTunnel.Why) = when (why) {
            NoTunnel.Why.NO_VPN -> "no VPN is connected; nothing was sent"
            NoTunnel.Why.NOT_COVERED -> "no VPN route reaches the server; nothing was sent"
            NoTunnel.Why.NOT_PRIVATE -> "the address is not in $NET; nothing was sent"
        }

        override fun net(error: NetError) = when (error) {
            NetError.Closed -> "connection closed"
            NetError.Refused -> "the server does not accept connections (is it running?)"
            NetError.Timeout -> "the server does not answer (timed out)"
            NetError.UnknownHost -> "the server cannot be found"
            NetError.NoRoute -> "no route to the server (is $NET on?)"
            is NetError.NoTunnel -> noTunnel(error.why)
            NetError.Tls -> "TLS failure with the server"
            NetError.KeyChanged -> "key does not match the pairing; nothing was sent"
            NetError.NoKey -> "no key for this server in the pairing; nothing was sent"
            NetError.Eof -> "the server closed the connection"
            is NetError.Http -> "the server answered HTTP ${error.code}"
            NetError.BadResponse -> "invalid answer from the server"
            is NetError.Other -> error.raw
        }

        override fun gatt(status: Int) = when (status) {
            0 -> "no error"
            5, 15 -> "missing authentication or encryption (status $status)"
            8 -> "the Flipper stopped answering: out of range or off (status 8)"
            19 -> "closed by the Flipper (status 19)"
            22 -> "closed by the phone (status 22)"
            62 -> "the connection could not be established (status 62)"
            133 -> "generic Android error (status 133)"
            137 -> "authentication rejected (status 137)"
            TIMEOUT_STATUS -> "timed out"
            else -> "GATT status $status"
        }

        override fun op(op: GattOp) = when (op) {
            GattOp.CONNECT -> "the connection"
            GattOp.CHARACTERISTIC -> "the characteristic"
            GattOp.MTU -> "the MTU"
            GattOp.SERVICES -> "the service discovery"
            GattOp.SUBSCRIBE -> "the subscription"
            GattOp.SUBSCRIBE_RX -> "the RX subscription"
            GattOp.SUBSCRIBE_FLOW -> "the flow control subscription"
            GattOp.READ -> "the read"
            GattOp.READ_CREDIT -> "reading the credit"
            GattOp.WRITE -> "the write"
            GattOp.OPERATION -> "the operation"
        }

        override fun flipper(failure: FlipperFailure) = when (failure) {
            FlipperFailure.NoBluetooth -> "this phone has no Bluetooth"
            FlipperFailure.BluetoothOff -> "the phone's Bluetooth is off"
            FlipperFailure.NoPermission -> "the nearby devices permission is missing"
            FlipperFailure.NotBonded -> "the Flipper is not paired with this phone"
            FlipperFailure.BondIncomplete -> "pairing did not finish: confirm the code on the phone and on the Flipper"
            is FlipperFailure.BondLost -> "the Flipper no longer recognizes this phone" + (failure.status?.let { " (status $it)" } ?: "")
            FlipperFailure.NotResponding -> "the Flipper does not answer"
            is FlipperFailure.ConnectFailed -> gatt(failure.status)
            FlipperFailure.MtuDropped -> "the Flipper dropped the link while negotiating the MTU; the next attempt will not negotiate it"
            FlipperFailure.ServicesTimeout -> "the Flipper did not answer the service discovery"
            FlipperFailure.NotAFlipper -> "the device does not offer the Flipper's serial service: is it the right one?"
            FlipperFailure.BadFlowValue -> "invalid flow control value"
            FlipperFailure.Overflow -> "too much data from the Flipper waiting to be sent"
            FlipperFailure.Stalled -> "the Flipper stopped accepting data"
            FlipperFailure.RpcClosed -> "the Flipper closed its RPC session"
            is FlipperFailure.Disconnected -> "disconnected: ${gatt(failure.status)}"
            is FlipperFailure.OpDropped -> "disconnected during ${op(failure.op)}: ${gatt(failure.status)}"
            is FlipperFailure.OpFailed -> "${op(failure.op)} failed: ${gatt(failure.status)}"
            is FlipperFailure.WriteFailed -> "the write failed: ${gatt(failure.status)}"
            FlipperFailure.ClosedByRelay -> "closed by the relay"
        }

        override fun sessionEnd(end: SessionEnd) = when (end) {
            is SessionEnd.ServerClosed -> if (end.reason.isBlank()) "the server closed the session (${end.serverCode})"
                else "the server closed the session: ${end.reason}"
            is SessionEnd.NetworkLost -> "lost the connection with the server: ${net(end.error)}"
            SessionEnd.ServerText -> "the server sent text"
            is SessionEnd.TooBig -> "frame of ${end.size} bytes (maximum 65536)"
            SessionEnd.QueueFull -> "too many frames pending"
            SessionEnd.ServerNotReading -> "the server does not read what the Flipper sends"
            is SessionEnd.FlipperLost -> "Flipper: ${flipper(end.error.failure)}"
            SessionEnd.Locked -> "the Flipper locked"
            SessionEnd.Stopped -> "relay stopped"
        }

        override fun level(level: LogLevel) = when (level) {
            LogLevel.OK -> "ok"
            LogLevel.INFO -> "info"
            LogLevel.WARN -> "warning"
            LogLevel.ERROR -> "error"
        }

        override val logStartedByUser = "Relay started by the user"
        override val logStarted = "Relay started"
        override val logStopped = "Relay stopped"
        override val logServerOutdated = "Server too old (no /phone/check): it needs an update"
        override fun logServer(error: NetError) = "Server: ${net(error)}"
        override val logOperatorStopped = "Server: the operator stopped the agent"
        override fun logOtherRelay(app: String?) = "Server: another relay is already connected" + (app?.let { " (app $it)" } ?: "")
        override val logBusy409 = "Server: busy with another relay (409)"
        override val logStopped423 = "Server: the operator stopped the agent (423)"
        override fun logRefused(code: Int) = "Server: refused the session (HTTP $code)"
        override val logSocketOpen = "Server: WebSocket open (101)"
        override val logRunning = "Relay running · ready sent"
        override fun logTraffic(fromFlipper: String, toFlipper: String) = "Traffic: $fromFlipper from the Flipper, $toFlipper to the Flipper"
        override fun logSessionEnded(after: String, end: SessionEnd) = "Session ended after $after: ${sessionEnd(end)}"
        override val logFlipperWaiting = "Flipper: waiting for it to appear"
        override val logFlipperConnecting = "Flipper: connecting GATT"
        override fun logFlipperConnected(name: String, mtu: Int) = "Flipper: $name connected · MTU $mtu"
        override val logFlipperLocked = "Flipper locked: unlock it to continue"
        override val logFlipperUnlocked = "Flipper unlocked"
        override fun logFlipper(failure: FlipperFailure) = "Flipper: ${flipper(failure)}"
        override val logTokenRejected = "Server: Phone token rejected (401)"
        override fun logRetry(attempt: Int, wait: String) = "Retry $attempt in $wait"
        override val logLinkShared = "Flipper: the Bluetooth link was still open; using it"
        override val logMtuSkipped = "Flipper: not negotiating the MTU (the last attempt dropped there)"
        override fun logMtu(mtu: Int) = "Flipper: MTU negotiated $mtu"
        override val logMtuNoAnswer = "Flipper: no answer to the MTU request, using 23 (slow link)"
        override fun logMtuFailed(status: Int) = "Flipper: MTU request failed (GATT status $status), using 23 (slow link)"
        override fun logMtuChanged(mtu: Int) = "Flipper: MTU changed to $mtu"
        override val logFastConnection = "Flipper: short connection interval requested (fast replies)"
        override val logFastConnectionRefused = "Flipper: Android refused the short connection interval (replies at the default pace)"
        override fun logRpcTiming(bytes: Int, writeMs: Long, replies: List<Pair<Long, Int>>, more: Boolean, largeChunks: Int, largeBytes: Int) =
            "RPC: $bytes B written to BLE in $writeMs ms · from the Flipper: " +
                (if (replies.isEmpty() && largeChunks == 0) "nothing within 2.5 s"
                 else replies.joinToString(", ") { (ms, size) -> "+$ms ms $size B" } + (if (more) ", …" else "") +
                     (if (largeChunks > 0) "${if (replies.isEmpty()) "" else " · "}$largeChunks larger chunks, $largeBytes B (screen, files)" else ""))
        override val logNotifications = "Flipper: RX notifications on"
        override val logBackgroundStartRefused = "Android did not let the relay start in the background: open it from the app"
        override val logRemoteSettleLate = "Remote: no reply to the stream's stop within 5 s; reading the Flipper's stream from the next chunk"
        override fun logRemoteStopped(why: ReaderStop) = when (why) {
            ReaderStop.FLIPPER_STREAM -> "Remote: the Flipper's stream could not be read; keys off until the next session"
            ReaderStop.SERVER_FRAME -> "Remote: a frame from the server could not be read; keys off until the next session"
        }
        override fun logKeyRefused(key: FlipperKey, long: Boolean, status: String) =
            "Remote: the Flipper refused ${if (long) "a long " else ""}$key: $status"
        override fun logKeyLate(key: FlipperKey) = "Remote: no answer to $key within 1 s; keys wait for the Flipper"
        override fun logKeysDropped(count: Int) = if (count == 1) "Remote: 1 key not sent" else "Remote: $count keys not sent"
        override fun logKeyNotSent(key: FlipperKey) = "Remote: $key not sent: its message did not read back right"
        override fun logRemoteHold(name: String) =
            "Remote: the relay holds $name without a server session; it does not connect to the server until the remote closes"
        override val logRemoteReleased = "Remote closed: the relay stopped its own screen stream and goes on with its cycle"

        override fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    }

    object Es : RelayStrings {
        override val lang = Lang.ES

        override fun stage(stage: Stage) = when (stage) {
            Stage.OFF -> "Parado"
            Stage.RUNNING -> "En marcha"
            Stage.REMOTE -> "Mando en uso"
            Stage.CHECKING_SERVER, Stage.CONNECTING_FLIPPER, Stage.OPENING_SESSION -> "Conectando…"
            Stage.WAITING_FLIPPER -> "Esperando al Flipper"
            Stage.FLIPPER_LOCKED -> "Flipper bloqueado"
            Stage.WAITING_SERVER -> "Esperando al servidor"
            Stage.RETRYING -> "Reintentando"
            Stage.ERROR -> "Error"
        }

        override fun problem(problem: Problem, detail: Detail?) = when (problem) {
            Problem.NOT_CONFIGURED -> "Falta el servidor o el Phone token"
            Problem.NOT_CHOSEN -> "Elige el Flipper en Dispositivo"
            Problem.TOKEN_REJECTED -> "El servidor no acepta el Phone token"
            Problem.SERVER_OUTDATED -> "El servidor es demasiado antiguo: actualízalo con zf update"
            Problem.SERVER_UNREACHABLE -> "El servidor no responde"
            Problem.NO_TUNNEL -> "$NET no está conectado en este móvil"
            Problem.KEY_CHANGED -> "La clave del servidor cambió"
            Problem.NEEDS_PAIRING -> "Vuelve a emparejar"
            Problem.OPERATOR_STOPPED -> "El agente está parado desde la vista web o la app"
            Problem.OTHER_RELAY -> if (detail is Detail.OtherApp) "Otro relé está conectado (app ${detail.version})"
                else "Otro relé está conectado (probablemente la app de relé anterior)"
            Problem.OWN_STALE -> "El servidor aún cierra la sesión anterior"
            Problem.BLUETOOTH_OFF -> "El Bluetooth del móvil está apagado"
            Problem.NO_PERMISSION -> "Falta el permiso de dispositivos cercanos"
            Problem.NOT_BONDED -> "El Flipper no está emparejado con este móvil"
            Problem.BOND_LOST -> "El Flipper ya no reconoce este móvil"
            Problem.FLIPPER_UNREACHABLE -> "El Flipper no responde: acércalo y enciende su Bluetooth"
            Problem.NOT_A_FLIPPER -> "El dispositivo elegido no es un Flipper"
            Problem.FLIPPER_DROPPED -> "Se cortó la conexión con el Flipper"
            Problem.SESSION_ENDED -> "Terminó la sesión con el servidor"
        }

        override val notificationTitle = "Relé del Flipper"
        override val stopAction = "Parar"
        override val channelName = "Relé"
        override val channelDescription = "Estado del relé entre el Flipper y el servidor"
        override fun running(name: String, duration: String) = "En marcha · $name · $duration"
        override fun retryIn(duration: String) = "Reintento en $duration"
        override val waitingForNetwork = "Esperando a $NET"
        override fun noTunnel(why: NoTunnel.Why) = when (why) {
            NoTunnel.Why.NO_VPN -> "no hay ninguna VPN conectada; no se envió nada"
            NoTunnel.Why.NOT_COVERED -> "ninguna ruta de VPN llega al servidor; no se envió nada"
            NoTunnel.Why.NOT_PRIVATE -> "la dirección no es de $NET; no se envió nada"
        }

        override fun net(error: NetError) = when (error) {
            NetError.Closed -> "conexión cerrada"
            NetError.Refused -> "el servidor no acepta conexiones (¿está en marcha?)"
            NetError.Timeout -> "el servidor no responde (tiempo agotado)"
            NetError.UnknownHost -> "no se encuentra el servidor"
            NetError.NoRoute -> "no hay ruta al servidor (¿$NET activo?)"
            is NetError.NoTunnel -> noTunnel(error.why)
            NetError.Tls -> "fallo TLS con el servidor"
            NetError.KeyChanged -> "la clave no coincide con el emparejamiento; no se envió nada"
            NetError.NoKey -> "el emparejamiento no tiene clave para este servidor; no se envió nada"
            NetError.Eof -> "el servidor cerró la conexión"
            is NetError.Http -> "el servidor respondió HTTP ${error.code}"
            NetError.BadResponse -> "respuesta del servidor no válida"
            is NetError.Other -> error.raw
        }

        override fun gatt(status: Int) = when (status) {
            0 -> "sin error"
            5, 15 -> "falta de autenticación o cifrado (estado $status)"
            8 -> "el Flipper dejó de responder: fuera de alcance o apagado (estado 8)"
            19 -> "lo cerró el Flipper (estado 19)"
            22 -> "lo cerró el móvil (estado 22)"
            62 -> "no se pudo establecer la conexión (estado 62)"
            133 -> "error genérico de Android (estado 133)"
            137 -> "autenticación rechazada (estado 137)"
            TIMEOUT_STATUS -> "tiempo agotado"
            else -> "estado GATT $status"
        }

        override fun op(op: GattOp) = when (op) {
            GattOp.CONNECT -> "la conexión"
            GattOp.CHARACTERISTIC -> "la característica"
            GattOp.MTU -> "el MTU"
            GattOp.SERVICES -> "el descubrimiento de servicios"
            GattOp.SUBSCRIBE -> "la suscripción"
            GattOp.SUBSCRIBE_RX -> "la suscripción a RX"
            GattOp.SUBSCRIBE_FLOW -> "la suscripción al control de flujo"
            GattOp.READ -> "la lectura"
            GattOp.READ_CREDIT -> "la lectura del crédito"
            GattOp.WRITE -> "la escritura"
            GattOp.OPERATION -> "la operación"
        }

        override fun flipper(failure: FlipperFailure) = when (failure) {
            FlipperFailure.NoBluetooth -> "este móvil no tiene Bluetooth"
            FlipperFailure.BluetoothOff -> "el Bluetooth del móvil está apagado"
            FlipperFailure.NoPermission -> "falta el permiso de dispositivos cercanos"
            FlipperFailure.NotBonded -> "el Flipper no está emparejado con este móvil"
            FlipperFailure.BondIncomplete -> "el emparejamiento no terminó: confirma el código en el móvil y en el Flipper"
            is FlipperFailure.BondLost -> "el Flipper ya no reconoce este móvil" + (failure.status?.let { " (estado $it)" } ?: "")
            FlipperFailure.NotResponding -> "el Flipper no responde"
            is FlipperFailure.ConnectFailed -> gatt(failure.status)
            FlipperFailure.MtuDropped -> "el Flipper cortó al negociar el MTU; el siguiente intento no lo negociará"
            FlipperFailure.ServicesTimeout -> "el Flipper no respondió al descubrir sus servicios"
            FlipperFailure.NotAFlipper -> "el dispositivo no ofrece el servicio serie del Flipper: ¿es el correcto?"
            FlipperFailure.BadFlowValue -> "valor de control de flujo no válido"
            FlipperFailure.Overflow -> "demasiados datos del Flipper sin enviar"
            FlipperFailure.Stalled -> "el Flipper dejó de aceptar datos"
            FlipperFailure.RpcClosed -> "el Flipper cerró su sesión RPC"
            is FlipperFailure.Disconnected -> "se desconectó: ${gatt(failure.status)}"
            is FlipperFailure.OpDropped -> "se desconectó durante ${op(failure.op)}: ${gatt(failure.status)}"
            is FlipperFailure.OpFailed -> "falló ${op(failure.op)}: ${gatt(failure.status)}"
            is FlipperFailure.WriteFailed -> "falló la escritura: ${gatt(failure.status)}"
            FlipperFailure.ClosedByRelay -> "cerrado por el relé"
        }

        override fun sessionEnd(end: SessionEnd) = when (end) {
            is SessionEnd.ServerClosed -> if (end.reason.isBlank()) "el servidor cerró la sesión (${end.serverCode})"
                else "el servidor cerró la sesión: ${end.reason}"
            is SessionEnd.NetworkLost -> "se perdió la conexión con el servidor: ${net(end.error)}"
            SessionEnd.ServerText -> "el servidor envió texto"
            is SessionEnd.TooBig -> "trama de ${end.size} bytes (máximo 65536)"
            SessionEnd.QueueFull -> "demasiadas tramas pendientes"
            SessionEnd.ServerNotReading -> "el servidor no lee lo que envía el Flipper"
            is SessionEnd.FlipperLost -> "Flipper: ${flipper(end.error.failure)}"
            SessionEnd.Locked -> "el Flipper se bloqueó"
            SessionEnd.Stopped -> "relé parado"
        }

        override fun level(level: LogLevel) = when (level) {
            LogLevel.OK -> "ok"
            LogLevel.INFO -> "info"
            LogLevel.WARN -> "aviso"
            LogLevel.ERROR -> "error"
        }

        override val logStartedByUser = "Relé iniciado por el usuario"
        override val logStarted = "Relé iniciado"
        override val logStopped = "Relé parado"
        override val logServerOutdated = "Servidor demasiado antiguo (sin /phone/check): hay que actualizarlo"
        override fun logServer(error: NetError) = "Servidor: ${net(error)}"
        override val logOperatorStopped = "Servidor: el operador paró el agente"
        override fun logOtherRelay(app: String?) = "Servidor: ya hay otro relé conectado" + (app?.let { " (app $it)" } ?: "")
        override val logBusy409 = "Servidor: ocupado por otro relé (409)"
        override val logStopped423 = "Servidor: el operador paró el agente (423)"
        override fun logRefused(code: Int) = "Servidor: rechazó la sesión (HTTP $code)"
        override val logSocketOpen = "Servidor: WebSocket abierto (101)"
        override val logRunning = "Relé en marcha · ready enviado"
        override fun logTraffic(fromFlipper: String, toFlipper: String) = "Tráfico: $fromFlipper del Flipper, $toFlipper al Flipper"
        override fun logSessionEnded(after: String, end: SessionEnd) = "Sesión terminada tras $after: ${sessionEnd(end)}"
        override val logFlipperWaiting = "Flipper: esperando a que aparezca"
        override val logFlipperConnecting = "Flipper: conectando GATT"
        override fun logFlipperConnected(name: String, mtu: Int) = "Flipper: $name conectado · MTU $mtu"
        override val logFlipperLocked = "Flipper bloqueado: desbloquéalo para seguir"
        override val logFlipperUnlocked = "Flipper desbloqueado"
        override fun logFlipper(failure: FlipperFailure) = "Flipper: ${flipper(failure)}"
        override val logTokenRejected = "Servidor: Phone token rechazado (401)"
        override fun logRetry(attempt: Int, wait: String) = "Reintento $attempt en $wait"
        override val logLinkShared = "Flipper: el enlace Bluetooth seguía abierto; se usa ese"
        override val logMtuSkipped = "Flipper: sin negociar el MTU (el último intento se cortó ahí)"
        override fun logMtu(mtu: Int) = "Flipper: MTU negociado $mtu"
        override val logMtuNoAnswer = "Flipper: sin respuesta a la petición de MTU, se usa 23 (enlace lento)"
        override fun logMtuFailed(status: Int) = "Flipper: la petición de MTU falló (estado GATT $status), se usa 23 (enlace lento)"
        override fun logMtuChanged(mtu: Int) = "Flipper: el MTU cambió a $mtu"
        override val logFastConnection = "Flipper: pedido un intervalo de conexión corto (respuestas rápidas)"
        override val logFastConnectionRefused = "Flipper: Android rechazó el intervalo de conexión corto (respuestas al ritmo normal)"
        override fun logRpcTiming(bytes: Int, writeMs: Long, replies: List<Pair<Long, Int>>, more: Boolean, largeChunks: Int, largeBytes: Int) =
            "RPC: $bytes B escritos por BLE en $writeMs ms · del Flipper: " +
                (if (replies.isEmpty() && largeChunks == 0) "nada en 2,5 s"
                 else replies.joinToString(", ") { (ms, size) -> "+$ms ms $size B" } + (if (more) ", …" else "") +
                     (if (largeChunks > 0) "${if (replies.isEmpty()) "" else " · "}$largeChunks trozos mayores, $largeBytes B (pantalla, ficheros)" else ""))
        override val logNotifications = "Flipper: notificaciones RX activas"
        override val logBackgroundStartRefused = "Android no dejó arrancar el relé en segundo plano: ábrelo desde la app"
        override val logRemoteSettleLate = "Mando: sin respuesta a la parada del flujo en 5 s; se lee el flujo del Flipper desde el siguiente trozo"
        override fun logRemoteStopped(why: ReaderStop) = when (why) {
            ReaderStop.FLIPPER_STREAM -> "Mando: no se pudo leer el flujo del Flipper; teclas apagadas hasta la próxima sesión"
            ReaderStop.SERVER_FRAME -> "Mando: no se pudo leer un trozo del servidor; teclas apagadas hasta la próxima sesión"
        }
        override fun logKeyRefused(key: FlipperKey, long: Boolean, status: String) =
            "Mando: el Flipper rechazó ${if (long) "una pulsación larga de " else ""}$key: $status"
        override fun logKeyLate(key: FlipperKey) = "Mando: sin respuesta a $key en 1 s; las teclas esperan al Flipper"
        override fun logKeysDropped(count: Int) = if (count == 1) "Mando: 1 tecla sin enviar" else "Mando: $count teclas sin enviar"
        override fun logKeyNotSent(key: FlipperKey) = "Mando: $key sin enviar: su mensaje no se leyó bien"
        override fun logRemoteHold(name: String) =
            "Mando: el relé usa $name sin sesión con el servidor; no se conecta al servidor hasta que se cierre el mando"
        override val logRemoteReleased = "Mando cerrado: el relé paró su propio flujo de pantalla y sigue con su ciclo"

        override fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value).replace('.', ',')
    }
}

/** The status the GATT layer reports for an operation that timed out. */
const val TIMEOUT_STATUS = -2

/** The private network's name, for the words: from the boundary, never written here. */
private val NET get() = CoreGraph.privateAddresses.name
