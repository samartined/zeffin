// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Addresses
import dev.zeffin.core.AppSettings
import dev.zeffin.core.Lang
import dev.zeffin.core.PairingRecord
import dev.zeffin.core.CoreGraph
import dev.zeffin.core.maskToken
import dev.zeffin.link.AppLinkState
import dev.zeffin.link.AuditItem
import dev.zeffin.link.Conn
import dev.zeffin.link.Frame
import dev.zeffin.link.PairExchange
import dev.zeffin.link.PhoneEvent
import dev.zeffin.link.ServerStatus
import dev.zeffin.relay.Detail
import dev.zeffin.relay.FlipperFailure
import dev.zeffin.relay.FlipperSide
import dev.zeffin.relay.LogEntry
import dev.zeffin.relay.LogLevel
import dev.zeffin.relay.NetError
import dev.zeffin.relay.Problem
import dev.zeffin.relay.RelayState
import dev.zeffin.relay.RelayStrings
import dev.zeffin.relay.ServerSide
import dev.zeffin.relay.Stage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What Android lets the relay do; shown as warnings in Relay. */
data class Warnings(
    val bluetoothPermission: Boolean = true,
    val notifications: Boolean = true,
    val batteryOptimized: Boolean = false,
    val backgroundRestricted: Boolean = false,
)

/** The private network's name, for the words: from the boundary, never written in them. */
internal val net get() = CoreGraph.privateAddresses.name

/** /app is down because the private network's tunnel does not reach the server: nothing was sent. */
val AppLinkState.noTunnel get() = conn != Conn.CONNECTED && conn != Conn.REJECTED && error is NetError.NoTunnel

/** What the server's key says about the pairing: nothing is sent until the owner pairs again. */
enum class KeyTrouble {
    /** The server showed another key than the pairing's. */
    CHANGED,
    /** The pairing is from before the app checked the server's key. */
    PAIR_AGAIN,
}

fun keyTrouble(link: AppLinkState, pairing: PairingRecord?, relay: RelayState? = null): KeyTrouble? = when {
    pairing == null -> null
    pairing.needsPairing || link.conn == Conn.NEEDS_PAIRING || relay?.problem == Problem.NEEDS_PAIRING -> KeyTrouble.PAIR_AGAIN
    link.conn == Conn.KEY_CHANGED || relay?.problem == Problem.KEY_CHANGED -> KeyTrouble.CHANGED
    else -> null
}

/** wss://host:port, as the relay's rows show the server. */
private fun socketHost(base: okhttp3.HttpUrl) = (if (base.isHttps) "wss://" else "ws://") + Addresses.hostPort(base)

/**
 * Turns the relay's and the server's state into what each screen shows, in [l]. Pure, for tests
 * and previews.
 */
class Present(private val l: Lang) {
    private val character = PresentCharacter(l)
    private val r = RelayStrings.of(l)
    private fun t(en: String, es: String) = l.t(en, es)
    private fun ago(ms: Long) = Fmt.ago(l, ms)

    // ---------------------------------------------------------------- shared

    fun nav(relay: RelayState, link: AppLinkState): NavUi {
        val serverSays = link.conn == Conn.CONNECTED && link.status?.connected == true
        return when {
            serverSays || relay.stage == Stage.RUNNING -> NavUi(t("Connected", "Conectado"), false)
            relay.stage in setOf(Stage.CHECKING_SERVER, Stage.CONNECTING_FLIPPER, Stage.OPENING_SESSION, Stage.RETRYING) ->
                NavUi(t("Connecting…", "Conectando…"), true, Face.CONNECTING)
            else -> NavUi(t("No relay", "Sin relé"), true)
        }
    }

    private fun gateWord(gate: String) = when (gate) {
        "paused" -> t("paused", "pausado")
        "stopped" -> t("stopped", "parado")
        else -> t("active", "activo")
    }

    private fun byWhom(by: String?) = when (by) {
        "app" -> t("from the app", "desde la app")
        "view" -> t("from the web view", "desde la vista web")
        else -> null
    }

    private fun at(time: String) = t(" at $time", " a las $time")

    // ---------------------------------------------------------------- Home

    /**
     * How old the shown screen is. A live one is timed from when its frame reached the app: the server may send its
     * status only when something changes, so the status's age can be seconds old. A stale or missing frame keeps the
     * server's figure, which knows when the Flipper last sent one.
     */
    fun frameAgeMs(frame: Frame?, status: ServerStatus?, stale: Boolean, nowMs: Long): Long {
        val received = frame?.receivedAt
        return if (received != null && !stale) (nowMs - received).coerceAtLeast(0) else ((status?.screenAgeS ?: 0.0) * 1000).toLong()
    }

    /** The row that opens the remote while Home is offline. */
    val useTheRemote: String get() = t("Use the remote", "Usar el mando")

    /**
     * Home. [frame] is the one drawn ([ScreenSource]); one the relay read on its own link ([Frame.fromRelay]) is not dimmed by
     * the server's stale flag or the offline state, and its age is its own.
     */
    fun homeUi(relay: RelayState, link: AppLinkState, frame: Frame?, settings: AppSettings, pairing: PairingRecord?, nowMs: Long): HomeUi {
        val fromRelay = frame?.fromRelay == true
        val status = link.status
        val title = status?.relay?.device ?: relay.deviceName ?: settings.flipper?.name ?: "Flipper"
        val gate = status?.gate ?: "active"
        val trouble = keyTrouble(link, pairing, relay)
        val offline = when {
            pairing?.appToken == null -> Offline.NOT_PAIRED
            trouble != null -> Offline.PAIR_AGAIN
            link.conn != Conn.CONNECTED || status == null -> Offline.NO_SERVER
            !status.connected && gate != "stopped" -> Offline.NO_RELAY
            else -> null
        }
        val transport = status?.transport ?: "BLE"
        val stale = !fromRelay && offline == null && status != null && status.connected && (frame?.stale == true || status.screenStale)
        val ageMs = frameAgeMs(frame, status, stale, nowMs)
        val subtitle = when (offline) {
            Offline.NOT_PAIRED -> t("Not paired with the server", "Sin emparejar con el servidor")
            Offline.PAIR_AGAIN -> if (trouble == KeyTrouble.CHANGED) keyChangedTitle() else t("Pair again with the server", "Vuelve a emparejar con el servidor")
            Offline.NO_SERVER -> when {
                link.conn == Conn.REJECTED -> t("App token rejected: pair again", "App token rechazado: vuelve a emparejar")
                link.noTunnel -> noTunnelTitle()
                else -> t("No connection with the server", "Sin conexión con el servidor")
            }
            Offline.NO_RELAY -> t("No relay · the agent cannot reach the Flipper", "Sin relé · el agente no tiene acceso al Flipper")
            null -> when {
                gate == "stopped" -> t("Agent stopped · the relay waits for you to resume it", "Agente parado · el relé espera a que lo reanudes")
                stale -> t("Connected over $transport · no frames since ${ago(ageMs)}", "Conectado por $transport · sin fotogramas desde ${ago(ageMs)}")
                else -> t("Connected over $transport · agent ${gateWord(gate)}", "Conectado por $transport · agente ${gateWord(gate)}") +
                    (frame?.let { " · #${it.seq}" } ?: "") + (if (gate == "active" && frame != null) " · ${ago(ageMs)}" else "")
            }
        }
        val mode = when {
            fromRelay && frame != null -> ScreenMode.LIVE
            offline != null || frame == null || (status != null && !status.connected) -> ScreenMode.OFF
            stale -> ScreenMode.STALE
            else -> ScreenMode.LIVE
        }
        val badge = when (mode) {
            ScreenMode.OFF -> t("No connection", "Sin conexión")
            ScreenMode.STALE -> t("Screen from ${ago(ageMs)}", "Pantalla de ${ago(ageMs)}")
            ScreenMode.LIVE -> null
        }
        // The operator's own actions are not repeated: the line below says the gate's current state.
        val feed = link.audit.filterNot { it.isOperator }.map(::feedItem).toMutableList()
        val changedAt = status?.gateChangedAt?.let { at(Fmt.hmSeconds(it)) } ?: ""
        val who = byWhom(status?.gateBy)?.let { " $it" } ?: ""
        when {
            offline == Offline.PAIR_AGAIN -> feed += keyLine(trouble!!)
            offline == Offline.NO_SERVER && link.noTunnel -> feed += noTunnelLine()
            offline == Offline.NO_RELAY -> feed += FeedItem(line = Tone.WARNING to (
                (relay.stoppedAt?.let { t("The relay stopped at ${Fmt.hm(it)}. ", "El relé se paró a las ${Fmt.hm(it)}. ") }
                    ?: t("The relay is not connected. ", "El relé no está conectado. ")) +
                    t("The agent cannot touch the Flipper until it is back.", "El agente no puede tocar el Flipper hasta que vuelva.")))
            gate == "paused" && offline == null -> {
                val subject = if (status?.gateBy == "app") t("You paused the agent", "Pausaste el agente") else t("Agent paused", "Agente pausado") + who
                feed += FeedItem(line = Tone.WARNING to (subject + changedAt + t(". The agent cannot touch the Flipper.", ". El agente no puede tocar el Flipper.")))
            }
            gate == "stopped" && offline == null -> feed += FeedItem(line = Tone.WARNING to
                (t("Agent stopped", "Agente parado") + who + changedAt +
                    t(". The relay waits for you to resume it.", ". El relé espera a que lo reanudes.")))
            stale -> feed += FeedItem(line = Tone.WARNING to t(
                "The Flipper has sent no image since ${Fmt.hms(nowMs - ageMs)}. The agent waits before pressing anything.",
                "El Flipper no envía imagen desde las ${Fmt.hms(nowMs - ageMs)}. El agente espera antes de pulsar nada."))
        }
        return HomeUi(title, subtitle, mode, badge, paused = gate != "active", gateEnabled = link.conn == Conn.CONNECTED,
            offline = offline, feed = feed.takeLast(60), nav = nav(relay, link),
            stripTitle = if (offline == null && gate != "stopped" && !stale) "$title · " + t("agent ${gateWord(gate)}", "agente ${gateWord(gate)}") else title,
            stripSub = if (offline == null && gate != "stopped" && !stale && frame != null)
                "#${frame.seq}" + (if (gate == "active") " · ${ago(ageMs)}" else "") else subtitle,
            useRemote = useTheRemote.takeIf { offline != null && settings.flipper != null },
            face = character.device(relay, link, pairing))
    }

    fun feedItem(a: AuditItem): FeedItem {
        val time = Fmt.hmSeconds(a.ts)
        if (a.isOperator) {
            val verb = when (a.action) {
                "pause" -> t("Agent paused", "Agente pausado")
                "resume" -> t("Agent resumed", "Agente reanudado")
                "stop" -> t("Agent stopped", "Agente parado")
                else -> t("Operator gate", "Puerta del operador")
            }
            return FeedItem(line = (if (a.action == "resume") Tone.SUCCESS else Tone.WARNING) to
                (verb + (byWhom(a.by)?.let { " $it" } ?: "") + " · $time"))
        }
        val result = a.result
        val refused = result?.startsWith("refused") == true
        val title = a.tool + if (refused) t(" · refused", " · rechazada") else if (a.failed) " · error" else ""
        val meta = when {
            result == null || result == "ok" -> listOfNotNull(a.ms?.let { Fmt.seconds(l, it) },
                a.seqAfter?.takeIf { it > 0 }?.let { t("frame $it", "fotograma $it") }, time).joinToString(" · ")
            refused -> t("Paused by the operator. Not sent · $time", "Pausado por el operador. No se envió · $time")
            result.startsWith("rpc_error") -> t("The Flipper answered ${result.substringAfter(": ")} · $time",
                "El Flipper respondió ${result.substringAfter(": ")} · $time")
            result == "timeout" -> t("No answer from the Flipper · $time", "Sin respuesta del Flipper · $time")
            result == "link_error" -> t("No connection with the Flipper · $time", "Sin conexión con el Flipper · $time")
            else -> result.removePrefix("error: ") + " · $time"
        }
        return FeedItem(action = ActionUi(title, a.args, meta, a.failed))
    }

    // ---------------------------------------------------------------- Settings

    fun serverLine(link: AppLinkState, pairing: PairingRecord?): StatusLine = when {
        pairing == null -> StatusLine(Tone.WARNING, t("Not paired", "Sin emparejar"), null)
        keyTrouble(link, pairing) == KeyTrouble.PAIR_AGAIN -> StatusLine(Tone.WARNING, pairAgainTitle(), Addresses.hostPort(pairing.base))
        keyTrouble(link, pairing) == KeyTrouble.CHANGED -> StatusLine(Tone.DANGER, keyChangedWord(), Addresses.hostPort(pairing.base))
        link.conn == Conn.CONNECTED -> StatusLine(Tone.ACTION, t("Connected", "Conectado"),
            listOfNotNull(link.serverName, link.serverVersion?.let { "v$it" }).joinToString(" · ").ifEmpty { null })
        link.conn == Conn.REJECTED -> StatusLine(Tone.DANGER, t("App token rejected", "App token rechazado"), null)
        link.conn == Conn.CONNECTING -> StatusLine(Tone.WARNING, t("Connecting…", "Conectando…"), Addresses.hostPort(pairing.base))
        else -> StatusLine(Tone.DANGER, t("No connection", "Sin conexión"), link.error?.let(r::net))
    }

    /** Settings' rows; the Language row says "System" until the route sets the app's own choice (languageLabel). */
    fun settingsUi(relay: RelayState, link: AppLinkState, settings: AppSettings, pairing: PairingRecord?, version: String) = SettingsUi(
        relay = when (relay.problem) {
            Problem.KEY_CHANGED -> StatusLine(Tone.DANGER, keyChangedWord(), pairing?.base?.let(::socketHost))
            Problem.NEEDS_PAIRING -> StatusLine(Tone.WARNING, pairAgainTitle(), relay.deviceName ?: settings.flipper?.name)
            else -> StatusLine(relayTone(relay), r.stage(relay.stage), listOfNotNull("BLE", relay.deviceName ?: settings.flipper?.name).joinToString(" · "))
        },
        server = serverLine(link, pairing),
        version = version,
        nav = nav(relay, link),
        language = languageLabel(null),
    )

    /** The Language row: the app's own choice, or "System" with the language it gives. */
    fun languageLabel(chosen: Lang?) = when (chosen) {
        Lang.EN -> "English"
        Lang.ES -> "Español"
        null -> t("System · English", "Sistema · Español")
    }

    // ---------------------------------------------------------------- Relay

    fun relayUi(relay: RelayState, link: AppLinkState, settings: AppSettings, pairing: PairingRecord?, warnings: Warnings,
             lastLog: LogEntry?, nowMs: Long): RelayUi {
        val frames = link.status?.takeIf { relay.stage == Stage.RUNNING && link.conn == Conn.CONNECTED }?.sessionFrames
        val framesLabel = t("Frames", "Fotogramas")
        val metrics = when (relay.stage) {
            Stage.RUNNING -> listOf(Fmt.duration(nowMs - (relay.sessionStartedAt ?: nowMs)) to t("Running", "En marcha"),
                (frames?.toString() ?: "—") to framesLabel, relay.reconnects.toString() to t("Reconnections", "Reconexiones"))
            Stage.OFF -> listOf((relay.lastSessionMs?.let(Fmt::duration) ?: "—") to t("Last session", "Última sesión"),
                (link.status?.sessionFrames?.toString() ?: "—") to framesLabel,
                (relay.stoppedAt?.let(Fmt::hm) ?: "—") to t("Stopped at", "Parado a las"))
            Stage.ERROR -> null
            else -> listOf(Fmt.duration(nowMs - (relay.waitingSince ?: nowMs)) to t("Waiting", "Esperando"),
                relay.attempt.coerceAtLeast(1).toString() to t("Attempt", "Intento"), "0" to framesLabel)
        }
        val name = relay.deviceName ?: settings.flipper?.name ?: "Flipper"
        val server = serverRow(relay)
        val flipper = flipperRow(relay, name)
        val banners = mutableListOf<BannerUi>()
        problemBanner(relay, nowMs)?.let { banners += it }
        if (relay.stage in setOf(Stage.CONNECTING_FLIPPER, Stage.WAITING_FLIPPER) &&
            nowMs - (relay.waitingSince ?: nowMs) > 15_000) {
            banners += BannerUi(Tone.ACTION, t("If it takes more than 15 s", "Si tarda más de 15 s"), t(
                "Check that the Flipper has Bluetooth on and is paired with this phone. If you renamed it, it has to be paired again.",
                "Comprueba que el Flipper tiene el Bluetooth activado y está emparejado con este móvil. Si cambiaste su nombre, hay que emparejarlo de nuevo."))
        }
        if (!warnings.bluetoothPermission && relay.problem != Problem.NO_PERMISSION) banners += permissionBanner(Tone.WARNING)
        if (relay.running && warnings.backgroundRestricted) {
            banners += BannerUi(Tone.DANGER, t("Android restricts the app in the background", "Android restringe la app en segundo plano"), t(
                "With the restriction the relay stops when the screen turns off. Remove it in the app's settings, Battery.",
                "Con la restricción el relé se para al apagar la pantalla. Quítala en los ajustes de la app, Batería."),
                fixes = listOf(Fix.BATTERY to t("Open the battery settings", "Abrir los ajustes de batería")))
        } else if (relay.running && warnings.batteryOptimized) {
            banners += BannerUi(Tone.WARNING, t("Battery optimized", "Batería optimizada"), t(
                "So that Android does not stop the relay with the screen off, remove Zeffin's battery optimization.",
                "Para que Android no detenga el relé con la pantalla apagada, quita la optimización de batería de Zeffin."),
                fixes = listOf(Fix.BATTERY to t("Remove the optimization", "Quitar la optimización")))
        }
        if (relay.running && !warnings.notifications) {
            banners += BannerUi(Tone.WARNING, t("Notifications off", "Notificaciones desactivadas"), t(
                "The relay keeps working, but you will not see its state or the Stop button in the notification bar.",
                "El relé sigue funcionando, pero no verás su estado ni el botón Parar en la barra de notificaciones."),
                fixes = listOf(Fix.NOTIFICATIONS to t("Allow notifications", "Permitir notificaciones")))
        }
        val base = pairing?.base
        val serverDesc = when {
            base == null || pairing.phoneToken == null -> StatusLine(Tone.WARNING, t("Not set up", "Sin configurar"),
                t("pair with the QR code of zf pair", "empareja con el QR de zf pair"))
            relay.problem == Problem.TOKEN_REJECTED -> StatusLine(Tone.DANGER, t("Token rejected", "Token rechazado"), socketHost(base))
            relay.problem == Problem.NO_TUNNEL -> StatusLine(Tone.WARNING, t("$net not connected", "$net sin conectar"), socketHost(base))
            relay.problem == Problem.KEY_CHANGED -> StatusLine(Tone.DANGER, keyChangedWord(), socketHost(base))
            relay.problem == Problem.NEEDS_PAIRING || pairing.needsPairing -> StatusLine(Tone.WARNING, pairAgainTitle(), socketHost(base))
            else -> StatusLine(Tone.NEUTRAL, Addresses.phoneUrl(base), t("token saved", "token guardado"))
        }
        val device = settings.flipper?.let { "${it.name} · Bluetooth LE · ${it.address}" }
            ?: t("Not chosen: tap to choose the Flipper", "Sin elegir: toca para elegir el Flipper")
        return RelayUi(
            // A pairing without a key waits for the owner to pair again: not an error of the relay's own.
            stateTone = if (relay.problem == Problem.NEEDS_PAIRING) Tone.WARNING else relayTone(relay),
            stateText = if (relay.problem == Problem.NEEDS_PAIRING) t("Waiting", "Esperando") else r.stage(relay.stage),
            on = relay.stage != Stage.OFF && relay.stage != Stage.ERROR,
            metrics = metrics, server = server, flipper = flipper,
            showActions = relay.stage == Stage.ERROR, banners = banners,
            note = if (relay.stage == Stage.OFF) t(
                "With the relay stopped the agent cannot touch the Flipper and the Home screen has no image.",
                "Con el relé parado el agente no puede tocar el Flipper y la pantalla de Inicio se queda sin imagen.") else null,
            serverDesc = serverDesc, deviceDesc = device, autoStart = settings.autoStart,
            lastEvent = lastLog?.takeIf { relay.stage == Stage.RUNNING || relay.stage == Stage.OFF }
                ?.let { "${it.text} · ${ago(nowMs - it.time)}" },
        )
    }

    /** The private network is not connected on this phone: the same words in Home, the conversation and Server. */
    fun noTunnelTitle() = t("$net is not connected on this phone", "$net no está conectado en este móvil")
    fun noTunnelText() = t("Nothing was sent to the server. It connects again as soon as $net is back.",
        "No se envió nada al servidor. Se vuelve a conectar en cuanto $net vuelva.")

    private fun noTunnelLine() = FeedItem(line = Tone.WARNING to noTunnelText())

    /** Home as the conversation shows it: Home's lines, but not the tunnel's or the key's, which the conversation's banner says (PresentChat). */
    fun conversationHome(home: HomeUi) = home.copy(feed = home.feed - noTunnelLine() - KeyTrouble.entries.map(::keyLine).toSet())

    // ---------------------------------------------------------------- The server's key

    fun keyChangedTitle() = t("The server's key changed", "La clave del servidor cambió")
    private fun keyChangedWord() = t("Key changed", "Clave cambiada")
    fun keyChangedText() = t(
        "Nothing was sent: the app does not connect to this server until you pair again. If you did not run zf key new on the server, " +
            "another device may be answering at that address: pair only with a QR code from zf pair.",
        "No se envió nada: la app no se conecta a este servidor hasta que vuelvas a emparejar. Si no ejecutaste zf key new en el servidor, " +
            "puede que otro dispositivo esté respondiendo en esa dirección: empareja solo con un QR de zf pair.")
    fun pairAgainTitle() = t("Pair again", "Vuelve a emparejar")
    fun pairAgainText() = t("The app now checks the server's key. Show a new QR code with zf pair and scan it; nothing is sent until then.",
        "La app ahora comprueba la clave del servidor. Muestra un QR nuevo con zf pair y escanéalo; hasta entonces no se envía nada.")
    private fun pairFix() = listOf(Fix.PAIR_AGAIN to t("Pair with QR code", "Emparejar con el QR"))
    fun keyChangedBanner() = BannerUi(Tone.DANGER, keyChangedTitle(), keyChangedText(), fixes = pairFix())
    fun pairAgainBanner() = BannerUi(Tone.WARNING, pairAgainTitle(), pairAgainText(), fixes = pairFix())
    fun keyBanner(trouble: KeyTrouble) = if (trouble == KeyTrouble.CHANGED) keyChangedBanner() else pairAgainBanner()

    private fun keyLine(trouble: KeyTrouble) = FeedItem(line = when (trouble) {
        KeyTrouble.CHANGED -> Tone.DANGER to keyChangedText()
        KeyTrouble.PAIR_AGAIN -> Tone.WARNING to t("The app now checks the server's key. Pair again with a new QR code from zf pair.",
            "La app ahora comprueba la clave del servidor. Vuelve a emparejar con un QR nuevo de zf pair.")
    })

    // ---------------------------------------------------------------- Pairing

    /** "482 913": the number the phone and the server both show. */
    fun confirmNumber(number: String) = number.chunked(3).joinToString(" ")

    /** Why pairing stopped: every outcome of docs/protocol/pairing, and the network's. */
    fun pairFailure(e: Throwable): BannerUi = when (e) {
        PairExchange.Failure.Refused -> BannerUi(Tone.DANGER, t("Pairing refused on the server", "Emparejamiento rechazado en el servidor"),
            t("Nothing was saved.", "No se guardó nada."))
        PairExchange.Failure.Expired -> BannerUi(Tone.DANGER, t("The confirmation expired", "La confirmación caducó"),
            t("Show a new QR code and scan it again.", "Muestra otro QR y escanéalo de nuevo."))
        PairExchange.Failure.CodeUsed -> BannerUi(Tone.DANGER, t("This code was already used by another phone", "Este código ya lo usó otro teléfono"), t(
            "Refuse that request on the server: nothing was sent. If you already confirmed it, run zf rotate-tokens. " +
                "Then pair again where nobody can see the screen.",
            "Rechaza esa petición en el servidor: no se envió nada. Si ya la confirmaste, ejecuta zf rotate-tokens. " +
                "Después vuelve a emparejar donde nadie vea la pantalla."))
        PairExchange.Failure.BadCode -> BannerUi(Tone.DANGER, t("This code no longer works", "Este código ya no vale"),
            t("It is not a code the server is showing now. Show a new QR code and scan it.",
                "No es un código que el servidor esté mostrando ahora. Muestra otro QR y escanéalo."))
        PairExchange.Failure.CodeExpired -> BannerUi(Tone.DANGER, t("This code expired", "Este código caducó"),
            t("Codes change every 30 seconds. Show a new QR code and scan it.", "Los códigos cambian cada 30 segundos. Muestra otro QR y escanéalo."))
        PairExchange.Failure.KeyMismatch -> BannerUi(Tone.DANGER,
            t("The server's key does not match the QR code", "La clave del servidor no coincide con el código QR"),
            t("Nothing was sent. Show a new QR code with zf pair. If it happens again, something that is not your server answers at this address.",
                "No se envió nada. Muestra un QR nuevo con zf pair. Si vuelve a pasar, lo que responde en esa dirección no es tu servidor."))
        PairExchange.Failure.NotFinished -> BannerUi(Tone.DANGER, t("The pairing did not finish", "El emparejamiento no terminó"),
            t("The connection dropped before the app got the tokens. Start again on the server.",
                "La conexión se cortó antes de que la app recibiera los tokens. Empieza de nuevo en el servidor."))
        is PairExchange.Failure.Net -> when (e.error) {
            is NetError.NoTunnel -> pairNoTunnel()
            NetError.Tls -> BannerUi(Tone.DANGER, t("Could not open a secure connection to the server", "No se pudo abrir una conexión segura con el servidor"),
                t("Nothing was sent.", "No se envió nada."), r.net(e.error), listOf(Fix.RETRY to t("Try again", "Reintentar")))
            else -> BannerUi(Tone.DANGER, t("Could not reach the server", "No se pudo contactar con el servidor"), r.net(e.error),
                fixes = listOf(Fix.RETRY to t("Try again", "Reintentar")))
        }
        else -> BannerUi(Tone.DANGER, t("Could not reach the server", "No se pudo contactar con el servidor"), null,
            fixes = listOf(Fix.RETRY to t("Try again", "Reintentar")))
    }

    /** A QR code or a pasted text that is not a pairing this app can use. */
    fun invalidQr(e: Throwable): BannerUi =
        if ((e as? Addresses.Invalid)?.problem == Addresses.Problem.OLD_QR) BannerUi(Tone.DANGER,
            t("This QR code is from an older server", "Este QR es de un servidor antiguo"),
            t("Update the server (zf update) and show a new QR code.", "Actualiza el servidor (zf update) y muestra otro QR."))
        else BannerUi(Tone.DANGER, t("Invalid QR code", "QR no válido"), l.address(e))

    /** Why a pasted text is not a pairing this app can use: an older server's says "text", not "QR code". */
    fun pasteError(e: Throwable): String =
        if ((e as? Addresses.Invalid)?.problem == Addresses.Problem.OLD_QR) t(
            "This text is from an older server. Update the server (zf update) and copy the new text.",
            "Este texto es de un servidor antiguo. Actualiza el servidor (zf update) y copia el texto nuevo.")
        else l.address(e)

    fun emptyClipboard() = t("The clipboard is empty: copy the pairing text first.", "El portapapeles está vacío: copia antes el texto de emparejamiento.")

    /** Seconds left as m:ss. */
    fun countdown(seconds: Int) = "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

    /** Pairing without the tunnel: the code was not traded, so it still works. */
    fun pairNoTunnel() = BannerUi(Tone.WARNING, noTunnelTitle(), t("Nothing was sent: the code is still unused. Turn on $net and try again.",
        "No se envió nada: el código sigue sin usar. Activa $net y reintenta."), fixes = listOf(Fix.RETRY to t("Try again", "Reintentar")))

    private fun permissionBanner(tone: Tone) = BannerUi(tone,
        t("The nearby devices permission is missing", "Falta el permiso de dispositivos cercanos"),
        t("Without it the relay cannot connect to the Flipper.", "Sin él el relé no puede conectar con el Flipper."),
        fixes = listOf(Fix.GRANT_BLUETOOTH to t("Grant the permission", "Dar el permiso")))

    private fun serverRow(s: RelayState): Pair<String, Tone?> {
        val name = s.server?.name ?: t("server", "servidor")
        val notConnected = if (s.stage == Stage.OFF) t("Not connected", "Sin conectar") else t("Not tried", "No intentado")
        if (s.problem == Problem.NEEDS_PAIRING) return pairAgainTitle() to Tone.WARNING
        return when (s.serverSide) {
            ServerSide.NOT_TRIED -> notConnected to null
            ServerSide.CHECKING -> t("Checking…", "Comprobando…") to Tone.WARNING
            ServerSide.READY, ServerSide.IN_SESSION -> t("$name · ready", "$name · listo") to Tone.SUCCESS
            ServerSide.REJECTED -> t("Token rejected (401)", "Token rechazado (401)") to Tone.DANGER
            ServerSide.OUTDATED -> t("$name · needs an update", "$name · hay que actualizarlo") to Tone.DANGER
            ServerSide.UNREACHABLE -> t("No answer", "Sin respuesta") to Tone.DANGER
            ServerSide.NO_TUNNEL -> t("$net not connected", "$net sin conectar") to Tone.WARNING
            ServerSide.KEY_CHANGED -> keyChangedWord() to Tone.DANGER
            ServerSide.STOPPED -> t("$name · agent stopped", "$name · agente parado") to Tone.WARNING
            ServerSide.BUSY -> t("$name · another relay connected", "$name · otro relé conectado") to Tone.WARNING
        }
    }

    private fun flipperRow(s: RelayState, name: String): Pair<String, Tone?> {
        val notConnected = t("Not connected", "Sin conectar")
        return when (s.flipperSide) {
            FlipperSide.NOT_TRIED -> (if (s.stage == Stage.OFF) notConnected else t("Not tried", "No intentado")) to null
            FlipperSide.CONNECTING -> t("Connecting GATT", "Conectando GATT") to Tone.WARNING
            FlipperSide.SECURING -> t("Encrypting the connection", "Cifrando la conexión") to Tone.WARNING
            FlipperSide.MTU -> t("Negotiating the MTU", "Negociando MTU") to Tone.WARNING
            FlipperSide.SERVICES -> t("Looking for its services", "Buscando sus servicios") to Tone.WARNING
            FlipperSide.SUBSCRIBING -> t("Turning on notifications", "Activando notificaciones") to Tone.WARNING
            FlipperSide.WAITING -> t("Waiting for it to appear", "Esperando a que aparezca") to Tone.WARNING
            FlipperSide.LOCKED -> t("$name · locked", "$name · bloqueado") to Tone.WARNING
            FlipperSide.CONNECTED -> (if (s.stage == Stage.OFF) notConnected else "$name · BLE · MTU ${s.mtu ?: "?"}") to
                (if (s.stage == Stage.OFF) null else Tone.SUCCESS)
            FlipperSide.FAILED -> t("No connection", "Sin conexión") to Tone.DANGER
        }
    }

    private fun problemBanner(s: RelayState, nowMs: Long): BannerUi? {
        val time = Fmt.hms(nowMs)
        val detail = s.detail?.let(r::detail)
        return when (s.problem) {
            null, Problem.SESSION_ENDED, Problem.OWN_STALE, Problem.FLIPPER_UNREACHABLE, Problem.FLIPPER_DROPPED -> null
            Problem.NOT_CONFIGURED -> BannerUi(Tone.WARNING, t("The server is not set up", "Falta configurar el servidor"), t(
                "Pair the app with the QR code of zf pair, or paste the address and the Phone token.",
                "Empareja la app con el QR de zf pair o pega la dirección y el Phone token."),
                fixes = listOf(Fix.PAIR_AGAIN to t("Pair with the server", "Emparejar con el servidor")))
            Problem.NOT_CHOSEN -> BannerUi(Tone.WARNING, t("Choose the Flipper", "Elige el Flipper"), t(
                "Tap Device and choose your Flipper from the paired devices.", "Toca Dispositivo y elige tu Flipper de la lista de emparejados."),
                fixes = listOf(Fix.DEVICE to t("Choose the Flipper", "Elegir el Flipper")))
            Problem.TOKEN_REJECTED -> BannerUi(Tone.DANGER, r.problem(Problem.TOKEN_REJECTED, null), t(
                "If you rotated the tokens on the server, open Server and paste the new one. It is not retried until you change it.",
                "Si rotaste los tokens en el servidor, abre Servidor y pega el nuevo. No se reintenta hasta que lo cambies."), "HTTP 401 · $time")
            Problem.SERVER_OUTDATED -> BannerUi(Tone.DANGER, t("The server needs an update", "Hay que actualizar el servidor"), t(
                "The server is too old for this app. Update it on the server with zf update.",
                "El servidor es demasiado antiguo para esta app. Actualízalo en el servidor con zf update."))
            Problem.SERVER_UNREACHABLE -> BannerUi(Tone.WARNING, r.problem(Problem.SERVER_UNREACHABLE, null), t(
                "Check that $net is on in the phone and that zeffin is running on the server.",
                "Comprueba que $net está activo en el móvil y que zeffin está en marcha en el servidor."), detail)
            Problem.NO_TUNNEL -> BannerUi(Tone.WARNING, r.problem(Problem.NO_TUNNEL, null), t(
                "Nothing was sent to the server. The relay goes on as soon as $net is back.",
                "No se envió nada al servidor. El relé sigue en cuanto $net vuelva."), detail?.let { "$it · $time" })
            Problem.KEY_CHANGED -> keyChangedBanner().copy(detail = detail?.let { "$it · $time" })
            Problem.NEEDS_PAIRING -> BannerUi(Tone.WARNING, pairAgainTitle(), t(
                "The app now checks the server's key. Scan a new QR code from zf pair; the relay waits until then.",
                "La app ahora comprueba la clave del servidor. Escanea un QR nuevo de zf pair; el relé espera hasta entonces."), fixes = pairFix())
            Problem.OPERATOR_STOPPED -> BannerUi(Tone.WARNING, t("The agent is stopped", "El agente está parado"), t(
                "It was stopped from the web view or from the app. The relay comes back as soon as you resume it.",
                "Se paró desde la vista web o desde la app. El relé vuelve en cuanto lo reanudes."))
            Problem.OTHER_RELAY -> BannerUi(Tone.WARNING, t("Another relay is connected to the server", "Otro relé está conectado al servidor"),
                (s.detail as? Detail.OtherApp)?.let {
                    t("Another app (version ${it.version}) is acting as the relay: stop it.", "Otra app (versión ${it.version}) está haciendo de relé: párala.")
                } ?: t("Probably the older relay app: stop it to use this relay.", "Probablemente la app de relé anterior: párala para usar este relé."))
            Problem.BLUETOOTH_OFF -> BannerUi(Tone.DANGER, t("Bluetooth is off", "El Bluetooth está apagado"),
                t("The relay goes on as soon as you turn it on.", "El relé sigue en cuanto lo enciendas."),
                fixes = listOf(Fix.ENABLE_BLUETOOTH to t("Turn on Bluetooth", "Encender el Bluetooth")))
            Problem.NO_PERMISSION -> permissionBanner(Tone.DANGER)
            Problem.NOT_BONDED -> BannerUi(Tone.DANGER, r.problem(Problem.NOT_BONDED, null), t(
                "Pair it in Android's Bluetooth settings and choose it again in Device.",
                "Emparéjalo en los ajustes Bluetooth de Android y vuelve a elegirlo en Dispositivo."),
                fixes = listOf(Fix.BLUETOOTH_SETTINGS to t("Open the Bluetooth settings", "Abrir los ajustes Bluetooth")))
            Problem.BOND_LOST -> BannerUi(Tone.DANGER, r.problem(Problem.BOND_LOST, null), t(
                "This happens if you renamed it or deleted its pairings. Forget it in the Bluetooth settings and pair it again.",
                "Pasa si cambiaste su nombre o borraste sus emparejamientos. Olvídalo en los ajustes Bluetooth y vuelve a emparejarlo."),
                ((s.detail as? Detail.Flipper)?.failure as? FlipperFailure.BondLost)?.status?.let { t("GATT status $it", "GATT estado $it") },
                listOf(Fix.BLUETOOTH_SETTINGS to t("Open the Bluetooth settings", "Abrir los ajustes Bluetooth")))
            Problem.NOT_A_FLIPPER -> BannerUi(Tone.DANGER, t("That device is not a Flipper", "Ese dispositivo no es un Flipper"), t(
                "It does not offer the Flipper's serial service. Choose another one in Device.",
                "No ofrece el servicio serie del Flipper. Elige otro en Dispositivo."), detail,
                listOf(Fix.DEVICE to t("Choose another", "Elegir otro")))
        }
    }

    // ---------------------------------------------------------------- Server

    fun serverUi(link: AppLinkState, settings: AppSettings, pairing: PairingRecord?): ServerUi {
        val st = link.status
        val trouble = keyTrouble(link, pairing)
        val state = when {
            trouble == KeyTrouble.PAIR_AGAIN -> Tone.WARNING to pairAgainTitle()
            trouble == KeyTrouble.CHANGED -> Tone.DANGER to keyChangedWord()
            else -> connState(link.conn)
        }
        val relayText = when {
            st == null -> "—"
            st.phoneAttached -> t("1 connected", "1 conectado") +
                (st.relay?.app?.let { " · Zeffin $it" } ?: t(" · older relay app", " · app de relé anterior"))
            else -> t("none", "ninguno")
        }
        val rows = listOf(
            RowUi(t("Address", "Dirección"), pairing?.base?.let(Addresses::hostPort) ?: "—", mono = true),
            pairing?.key?.let { RowUi(t("Server key", "Clave del servidor"), it.fingerprint, mono = true) }
                ?: RowUi(t("Server key", "Clave del servidor"), t("none saved", "ninguna guardada")),
            RowUi(t("Version", "Versión"), link.serverVersion?.let { "zeffin $it" } ?: "—"),
            RowUi("App token", pairing?.appToken?.let { maskToken(it) + t(" · saved", " · guardado") } ?: t("Not saved", "Sin guardar")),
            RowUi("App", if (link.conn == Conn.CONNECTED) t("connected", "conectada") + (link.latencyMs?.let { " · $it ms" } ?: "")
                else t("no connection", "sin conexión")),
            RowUi(t("Phone relay", "Relé del móvil"), relayText),
            RowUi(t("Agent", "Agente"), st?.let { gateWord(it.gate) } ?: "—", st?.gate?.let { if (it == "active") Tone.SUCCESS else Tone.WARNING }),
        )
        val banners = mutableListOf<BannerUi>()
        trouble?.let { banners += keyBanner(it) }
        if (link.conn == Conn.REJECTED) banners += BannerUi(Tone.DANGER, t("The server does not accept the App token", "El servidor no acepta el App token"),
            t("If you rotated the tokens, pair again with the QR code of zf pair.", "Si rotaste los tokens, vuelve a emparejar con el QR de zf pair."),
            fixes = listOf(Fix.PAIR_AGAIN to t("Pair again", "Emparejar de nuevo")))
        if (link.noTunnel) banners += BannerUi(Tone.WARNING, noTunnelTitle(), noTunnelText())
        else if (link.conn == Conn.OFFLINE && link.error != null) banners += BannerUi(Tone.WARNING,
            t("No connection with the server", "Sin conexión con el servidor"), r.net(link.error))
        return ServerUi(link.serverName ?: t("Server", "Servidor"), state, rows, settings.orientation, banners, pairing != null,
            pairing?.key?.fingerprint)
    }

    private fun connState(conn: Conn) = when (conn) {
        Conn.CONNECTED -> Tone.ACTION to t("Connected", "Conectado")
        Conn.CONNECTING -> Tone.WARNING to t("Connecting…", "Conectando…")
        Conn.REJECTED -> Tone.DANGER to t("Token rejected", "Token rechazado")
        Conn.NOT_CONFIGURED -> Tone.WARNING to t("Not paired", "Sin emparejar")
        else -> Tone.DANGER to t("No connection", "Sin conexión")
    }

    // ---------------------------------------------------------------- Diagnostics and Log

    fun logLine(e: LogEntry) = LogLineUi(Fmt.hms(e.time), r.level(e.level), tone(e.level), e.text)

    fun diagnosticsUi(relay: RelayState, link: AppLinkState, log: List<LogEntry>, nowMs: Long, diagAt: Long?): DiagnosticsUi {
        val st = link.status
        val relayWord = t(
            when (relay.stage) {
                Stage.OFF -> "stopped"; Stage.RUNNING -> "running"; Stage.REMOTE -> "remote in use"; Stage.WAITING_FLIPPER -> "waiting for the Flipper"
                Stage.FLIPPER_LOCKED -> "Flipper locked"; Stage.WAITING_SERVER -> "waiting for the server"
                Stage.RETRYING -> "retrying"; Stage.ERROR -> "error"; else -> "connecting…"
            },
            when (relay.stage) {
                Stage.OFF -> "parado"; Stage.RUNNING -> "en marcha"; Stage.REMOTE -> "mando en uso"; Stage.WAITING_FLIPPER -> "esperando al Flipper"
                Stage.FLIPPER_LOCKED -> "Flipper bloqueado"; Stage.WAITING_SERVER -> "esperando al servidor"
                Stage.RETRYING -> "reintentando"; Stage.ERROR -> "error"; else -> "conectando…"
            })
        val reconnections = if (relay.reconnects == 1) t("1 reconnection", "1 reconexión") else t("${relay.reconnects} reconnections", "${relay.reconnects} reconexiones")
        val state = listOf(
            Triple(t("Server", "Servidor"), if (link.conn == Conn.CONNECTED) listOfNotNull(link.serverName ?: t("connected", "conectado"),
                link.latencyMs?.let { "$it ms" }).joinToString(" · ") else t("no connection", "sin conexión"),
                if (link.conn == Conn.CONNECTED) Tone.SUCCESS else Tone.DANGER),
            Triple("Flipper", st?.relay?.device?.let { "$it · ${st.transport ?: "BLE"} · MTU ${st.relay.mtu ?: "?"}" }
                ?: relay.deviceName?.let { "$it · $relayWord" } ?: t("not connected", "sin conectar"),
                if (st?.connected == true || relay.stage == Stage.RUNNING) Tone.SUCCESS else null),
            Triple(t("Relay", "Relé"), if (relay.stage == Stage.RUNNING) "${Fmt.duration(nowMs - (relay.sessionStartedAt ?: nowMs))} · $reconnections"
                else relayWord, when (relay.stage) {
                    Stage.RUNNING -> Tone.SUCCESS
                    Stage.ERROR -> Tone.DANGER
                    Stage.OFF -> null
                    else -> Tone.WARNING
                }),
            Triple(t("Agent", "Agente"), st?.let { gateWord(it.gate) } ?: "—", st?.gate?.let { if (it == "active") Tone.SUCCESS else Tone.WARNING }),
        )
        val server = link.diag?.let { d ->
            val session = d["session"] as? JsonObject
            val rejected = d["rejected"] as? JsonObject
            val total = rejected?.values?.sumOf { (it as? JsonPrimitive)?.longOrNull ?: 0 } ?: 0
            val last = d["last_rejected"] as? JsonObject
            val lastText = last?.let {
                val reason = (it["reason"] as? JsonPrimitive)?.content
                val code = when (reason) { "bad_token" -> "401"; "busy" -> "409"; "stopped" -> "423"; else -> reason }
                val ts = (it["ts"] as? JsonPrimitive)?.doubleOrNull
                listOfNotNull(code, ts?.let(Fmt::hmSeconds)).joinToString(" · ")
            }
            listOf(
                Triple(t("Frames received", "Fotogramas recibidos"), session?.n("frames")?.toString() ?: "—", null),
                Triple(t("Phone → server", "Móvil → servidor"), session?.n("bytes_in")?.let { Fmt.bytes(l, it) } ?: "—", null),
                Triple(t("Server → phone", "Servidor → móvil"), session?.n("bytes_out")?.let { Fmt.bytes(l, it) } ?: "—", null),
                Triple(t("Agent actions", "Acciones del agente"), d.n("agent_actions")?.toString() ?: "—", null),
                Triple(t("Rejected on /phone", "Rechazos en /phone"), if (total == 0L) "0" else "$total" + (lastText?.let { " · $it" } ?: ""),
                    if (total > 0) Tone.WARNING else null),
            )
        }
        return DiagnosticsUi(
            updated = diagAt?.let { t("Updated ${ago(nowMs - it)}", "Actualizado ${ago(nowMs - it)}") }
                ?: t("No figures from the server", "Sin cifras del servidor"),
            state = state, server = server, recent = log.takeLast(3).reversed().map(::logLine),
        )
    }

    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    fun logUi(tab: Int, log: List<LogEntry>, link: AppLinkState): LogUi {
        val lines = if (tab == 0) log.reversed().map(::logLine) else {
            val events = link.phone.map { p -> p.ts to phoneLine(p) } + link.audit.map { a ->
                a.ts to LogLineUi(Fmt.hmsSeconds(a.ts), r.level(if (a.failed) LogLevel.ERROR else LogLevel.OK),
                    if (a.failed) Tone.DANGER else Tone.SUCCESS,
                    if (a.isOperator) (t("Operator: ", "Operador: ") + "${a.action} ${byWhom(a.by) ?: ""}").trim()
                    else t("Agent: ", "Agente: ") + a.tool + (a.args?.let { " ($it)" } ?: ""))
            }
            events.sortedByDescending { it.first }.map { it.second }
        }
        return LogUi(tab, lines)
    }

    private fun phoneLine(p: PhoneEvent): LogLineUi {
        val reason = p.reason?.let { ": $it" } ?: ""
        val (level, text) = when (p.ev) {
            "ws_open" -> LogLevel.INFO to t("Relay: connection open", "Relé: conexión abierta")
            "ready" -> LogLevel.OK to t("Relay ready", "Relé listo")
            "close" -> LogLevel.WARN to t("Relay disconnected", "Relé desconectado") + reason
            "ws_rejected" -> LogLevel.ERROR to t("Relay rejected", "Relé rechazado") + reason
            "stream" -> LogLevel.INFO to t("Flipper screen", "Pantalla del Flipper")
            "first_frame" -> LogLevel.OK to t("First frame", "Primer fotograma")
            "paired" -> LogLevel.OK to t("App paired with a code", "App emparejada con un código")
            else -> LogLevel.INFO to p.ev
        }
        val detail = p.detail?.let { d ->
            listOfNotNull(
                d.lead,
                d.firstFrameS?.let { t("first frame ${r.decimal(it)} s", "primer fotograma ${r.decimal(it)} s") },
                d.frames?.let { if (it == 1L) t("1 frame", "1 fotograma") else t("$it frames", "$it fotogramas") },
                d.code?.let { t("code $it", "código $it") },
                d.reason,
            ).joinToString(" · ").ifEmpty { null }
        }
        return LogLineUi(Fmt.hmsSeconds(p.ts), r.level(level), tone(level), text + (detail?.let { " · $it" } ?: ""))
    }

    companion object {
        fun relayTone(s: RelayState): Tone = when (s.stage) {
            Stage.RUNNING -> Tone.SUCCESS
            Stage.OFF -> Tone.NEUTRAL
            Stage.ERROR -> Tone.DANGER
            else -> Tone.WARNING
        }

        private fun tone(level: LogLevel) = when (level) {
            LogLevel.OK -> Tone.SUCCESS
            LogLevel.INFO -> Tone.ACTION
            LogLevel.WARN -> Tone.WARNING
            LogLevel.ERROR -> Tone.DANGER
        }

        /** "Copy diagnostics": versions, transport, state and the last 200 events, in English for sharing. Never a token. */
        fun copyText(version: String, android: String, relay: RelayState, link: AppLinkState, settings: AppSettings, pairing: PairingRecord?,
            log: List<LogEntry>): String =
            buildString {
                val r = RelayStrings.En
                appendLine("Zeffin $version · Android $android")
                appendLine("Server: " + (link.serverName ?: "?") + " " + (link.serverVersion ?: "") + " · " +
                    (pairing?.base?.let(Addresses::hostPort) ?: "not set up") + " · app " + link.conn.name.lowercase())
                appendLine("Relay: " + r.stage(relay.stage) + (relay.problem?.let { " · " + r.problem(it, relay.detail) } ?: "") +
                    (relay.detail?.let { " (" + r.detail(it) + ")" } ?: ""))
                appendLine("Flipper: " + (relay.deviceName ?: settings.flipper?.name ?: "?") + " · BLE · MTU " + (relay.mtu ?: "?") +
                    " · reconnections ${relay.reconnects}")
                link.status?.let { appendLine("Server says: relay ${if (it.connected) "connected" else "not connected"}, agent ${it.gate}") }
                appendLine()
                for (e in log.takeLast(200)) appendLine("${Fmt.hms(e.time)} ${r.level(e.level)} ${e.text}")
            }
    }
}
