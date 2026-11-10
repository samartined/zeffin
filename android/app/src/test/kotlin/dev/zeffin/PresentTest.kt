// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.Lang
import dev.zeffin.core.CoreGraph
import dev.zeffin.link.Conn
import dev.zeffin.relay.NetError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.zeffin.Fixtures as F
import dev.zeffin.ui.present.Offline
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.ScreenMode
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.present.Warnings

class PresentTest {
    private val es = Present(Lang.ES)
    private val en = Present(Lang.EN)

    @Test fun homeInUse() {
        val ui = es.homeUi(F.relayRunning, F.link, F.frame, F.settings, F.pairing, F.now)
        assertEquals("Demo", ui.title)
        assertEquals("Conectado por BLE · agente activo · #418 · ahora", ui.subtitle)
        assertEquals(ScreenMode.LIVE, ui.screenMode)
        assertNull(ui.offline)
        assertEquals("flipper_press", ui.feed[1].action!!.title)
        assertEquals("1,4 s · fotograma 418 · 14:02", ui.feed[1].action!!.meta)
        assertEquals("Conectado", ui.nav.statusLabel)
        val english = en.homeUi(F.relayRunning, F.link, F.frame, F.settings, F.pairing, F.now)
        assertEquals("Connected over BLE · agent active · #418 · now", english.subtitle)
        assertEquals("1.4 s · frame 418 · 14:02", english.feed[1].action!!.meta)
        assertEquals("Connected", english.nav.statusLabel)
    }

    @Test fun homeWithoutServerOrRelay() {
        val noServer = es.homeUi(F.relayRunning, F.link.copy(conn = Conn.OFFLINE), F.frame, F.settings, F.pairing, F.now)
        assertEquals(Offline.NO_SERVER, noServer.offline)
        assertEquals(ScreenMode.OFF, noServer.screenMode)
        val noRelay = es.homeUi(F.relayStopped, F.link.copy(status = F.status.copy(connected = false)), F.frame, F.settings, F.pairing, F.now)
        assertEquals(Offline.NO_RELAY, noRelay.offline)
        assertTrue(noRelay.feed.last().line!!.second.startsWith("El relé se paró a las 14:17"))
        val english = en.homeUi(F.relayStopped, F.link.copy(status = F.status.copy(connected = false)), F.frame, F.settings, F.pairing, F.now)
        assertTrue(english.feed.last().line!!.second.startsWith("The relay stopped at 14:17"))
    }

    @Test fun withoutTheTunnelEveryScreenSaysNothingWasSent() {
        val net = CoreGraph.privateAddresses.name
        val home = en.homeUi(F.relayNoTunnel, F.linkNoTunnel, F.frame, F.settings, F.pairing, F.now)
        assertEquals(Offline.NO_SERVER, home.offline)
        assertEquals("$net is not connected on this phone", home.subtitle)
        assertEquals(Tone.WARNING to "Nothing was sent to the server. It connects again as soon as $net is back.", home.feed.last().line)
        assertTrue(en.conversationHome(en.homeUi(F.relayNoTunnel, F.linkNoTunnel, F.frame, F.settings, F.pairing, F.now))
            .feed.none { it.line != null })  // the conversation's banner says it there
        assertEquals("$net no está conectado en este móvil",
            es.homeUi(F.relayNoTunnel, F.linkNoTunnel, F.frame, F.settings, F.pairing, F.now).subtitle)
        val relay = en.relayUi(F.relayNoTunnel, F.linkNoTunnel, F.settings, F.pairing, Warnings(), null, F.now)
        assertEquals("$net not connected", relay.server.first)
        val banner = relay.banners.first()
        assertEquals("$net is not connected on this phone", banner.title)
        assertEquals("Nothing was sent to the server. The relay goes on as soon as $net is back.", banner.text)
        assertTrue(banner.detail!!.startsWith("no VPN is connected; nothing was sent · "))
        assertEquals("No se envió nada al servidor. El relé sigue en cuanto $net vuelva.",
            es.relayUi(F.relayNoTunnel, F.linkNoTunnel, F.settings, F.pairing, Warnings(), null, F.now).banners.first().text)
        assertEquals("$net is not connected on this phone", en.serverUi(F.linkNoTunnel, F.settings, F.pairing).banners.single().title)
        // Any other failure keeps its own words.
        assertEquals("No connection with the server",
            en.homeUi(F.relayRunning, F.link.copy(conn = Conn.OFFLINE, error = NetError.Timeout), F.frame, F.settings, F.pairing, F.now).subtitle)
    }

    @Test fun operatorStopShowsResume() {
        val ui = es.homeUi(F.relayStopped, F.link.copy(status = F.status.copy(gate = "stopped", gateBy = "view", connected = false)),
            F.frame, F.settings, F.pairing, F.now)
        assertNull(ui.offline) // not "no relay": the fix is Resume, not the relay settings
        assertTrue(ui.paused)
        assertTrue(ui.feed.last().line!!.second.startsWith("Agente parado desde la vista web"))
    }

    @Test fun relayTokenErrorOffersPairing() {
        val ui = es.relayUi(F.relayError, F.link, F.settings, F.pairing, Warnings(), null, F.now)
        assertFalse(ui.on)
        assertEquals("Error", ui.stateText)
        assertEquals("Token rechazado (401)", ui.server.first)
        assertEquals("No intentado", ui.flipper.first)
        assertTrue(ui.showActions)
        assertTrue(ui.banners.first().text!!.contains("abre Servidor y pega el nuevo"))
        val english = en.relayUi(F.relayError, F.link, F.settings, F.pairing, Warnings(), null, F.now)
        assertEquals("Token rejected (401)", english.server.first)
        assertTrue(english.banners.first().text!!.contains("open Server and paste the new one"))
    }

    // ---- the server's key

    @Test fun aChangedKeyIsTheSameHardErrorEverywhere() {
        val home = en.homeUi(F.relayKeyChanged, F.linkKeyChanged, F.frame, F.settings, F.pairing, F.now)
        assertEquals(Offline.PAIR_AGAIN, home.offline)
        assertEquals("The server's key changed", home.subtitle)
        assertEquals(Tone.DANGER to en.keyChangedText(), home.feed.last().line)
        assertTrue(en.keyChangedText().startsWith("Nothing was sent: the app does not connect to this server until you pair again."))
        assertTrue(en.keyChangedText().endsWith("pair only with a QR code from zf pair."))
        assertTrue(en.conversationHome(home).feed.none { it.line != null })  // the conversation says it in its banner

        val server = en.serverUi(F.linkKeyChanged, F.settings, F.pairing)
        assertEquals(Tone.DANGER to "Key changed", server.state)
        assertEquals(en.keyChangedBanner(), server.banners.first())
        assertEquals("27BA 42BE 3FD7 D25B 86F5", server.rows.first { it.label == "Server key" }.value)
        assertEquals("27BA 42BE 3FD7 D25B 86F5", server.fingerprint)

        val settings = en.settingsUi(F.relayKeyChanged, F.linkKeyChanged, F.settings, F.pairing, "0.8.0")
        assertEquals("Key changed", settings.server.state)
        assertEquals("Key changed", settings.relay.state)
        assertEquals("wss://100.98.12.7:9434", settings.relay.rest)

        val relay = en.relayUi(F.relayKeyChanged, F.link, F.settings, F.pairing, Warnings(), null, F.now)
        assertEquals("Key changed", relay.server.first)
        assertEquals("The server's key changed", relay.banners.first().title)
        assertEquals(en.keyChangedText(), relay.banners.first().text)
        assertEquals(dev.zeffin.ui.present.Fix.PAIR_AGAIN, relay.banners.first().fixes.single().first)

        val es = es.serverUi(F.linkKeyChanged, F.settings, F.pairing)
        assertEquals("La clave del servidor cambió", es.banners.first().title)
        assertTrue(es.banners.first().text!!.startsWith("No se envió nada: la app no se conecta a este servidor hasta que vuelvas a emparejar."))
    }

    @Test fun aPairingWithoutAKeyAsksToPairAgain() {
        val home = en.homeUi(F.relayPairAgain, F.linkPairAgain, F.frame, F.settings, F.pairingWithoutKey, F.now)
        assertEquals(Offline.PAIR_AGAIN, home.offline)
        assertEquals("Pair again with the server", home.subtitle)
        val server = es.serverUi(F.link.copy(conn = Conn.OFFLINE), F.settings, F.pairingWithoutKey)  // whatever the link says
        assertEquals(Tone.WARNING to "Vuelve a emparejar", server.state)
        assertEquals("ninguna guardada", server.rows.first { it.label == "Clave del servidor" }.value)
        assertNull(server.fingerprint)
        assertEquals("Pair again", en.settingsUi(F.relayPairAgain, F.linkPairAgain, F.settings, F.pairingWithoutKey, "0.8.0").server.state)
        val relay = en.relayUi(F.relayPairAgain, F.link, F.settings, F.pairingWithoutKey, Warnings(), null, F.now)
        assertEquals(Tone.WARNING to "Waiting", relay.stateTone to relay.stateText)
        assertEquals("Esperando", es.relayUi(F.relayPairAgain, F.link, F.settings, F.pairingWithoutKey, Warnings(), null, F.now).stateText)
        assertEquals("Error", en.relayUi(F.relayKeyChanged, F.link, F.settings, F.pairing, Warnings(), null, F.now).stateText)
    }

    @Test fun everyPairingOutcomeHasItsWords() {
        val outcomes = listOf(dev.zeffin.link.PairExchange.Failure.Refused to "Pairing refused on the server",
            dev.zeffin.link.PairExchange.Failure.Expired to "The confirmation expired",
            dev.zeffin.link.PairExchange.Failure.CodeUsed to "This code was already used by another phone",
            dev.zeffin.link.PairExchange.Failure.BadCode to "This code no longer works",
            dev.zeffin.link.PairExchange.Failure.CodeExpired to "This code expired",
            dev.zeffin.link.PairExchange.Failure.KeyMismatch to "The server's key does not match the QR code",
            dev.zeffin.link.PairExchange.Failure.NotFinished to "The pairing did not finish",
            dev.zeffin.link.PairExchange.Failure.Net(NetError.Tls) to "Could not open a secure connection to the server")
        for ((failure, title) in outcomes) assertEquals(title, en.pairFailure(failure).title)
        assertEquals("Refuse that request on the server: nothing was sent. If you already confirmed it, run zf rotate-tokens. " +
            "Then pair again where nobody can see the screen.", en.pairFailure(dev.zeffin.link.PairExchange.Failure.CodeUsed).text)
        assertEquals("Rechaza esa petición en el servidor: no se envió nada. Si ya la confirmaste, ejecuta zf rotate-tokens. " +
            "Después vuelve a emparejar donde nadie vea la pantalla.", es.pairFailure(dev.zeffin.link.PairExchange.Failure.CodeUsed).text)
        assertEquals("No se envió nada. Muestra un QR nuevo con zf pair. Si vuelve a pasar, lo que responde en esa dirección no es tu servidor.",
            es.pairFailure(dev.zeffin.link.PairExchange.Failure.KeyMismatch).text)
        assertEquals("This QR code is from an older server",
            en.invalidQr(dev.zeffin.core.Addresses.Invalid(dev.zeffin.core.Addresses.Problem.OLD_QR)).title)
        val old = dev.zeffin.core.Addresses.Invalid(dev.zeffin.core.Addresses.Problem.OLD_QR)
        assertEquals("This text is from an older server. Update the server (zf update) and copy the new text.", en.pasteError(old))
        assertEquals("Este texto es de un servidor antiguo. Actualiza el servidor (zf update) y copia el texto nuevo.", es.pasteError(old))
        assertEquals("482 913", en.confirmNumber("482913"))
        assertEquals("1:52", en.countdown(112))
        assertEquals("0:00", en.countdown(-3))
    }

    @Test fun bondLostShowsTheGattStatus() {
        assertEquals("GATT estado 5", es.relayUi(F.relayBondLost, F.link, F.settings, F.pairing, Warnings(), null, F.now).banners.first().detail)
        assertEquals("GATT status 5", en.relayUi(F.relayBondLost, F.link, F.settings, F.pairing, Warnings(), null, F.now).banners.first().detail)
    }

    @Test fun relayRunningFigures() {
        val ui = es.relayUi(F.relayRunning, F.link, F.settings, F.pairing, Warnings(), null, F.now)
        assertEquals(listOf("11 min" to "En marcha", "418" to "Fotogramas", "0" to "Reconexiones"), ui.metrics)
        assertEquals("Demo · BLE · MTU 414", ui.flipper.first)
        assertEquals("my-server · listo", ui.server.first)
        assertEquals(listOf("11 min" to "Running", "418" to "Frames", "0" to "Reconnections"),
            en.relayUi(F.relayRunning, F.link, F.settings, F.pairing, Warnings(), null, F.now).metrics)
    }

    @Test fun serverAddressRowIsMonospaced() {
        val rows = en.serverUi(F.link, F.settings, F.pairing).rows
        assertTrue(rows.first().mono && rows.first().label == "Address")
        assertEquals("Dirección", es.serverUi(F.link, F.settings, F.pairing).rows.first().label)
    }

    @Test fun copiedDiagnosticsAreEnglishAndHaveNoTokens() {
        val text = Present.copyText("0.1.0", "14", F.relayRunning, F.link, F.settings, F.pairing, F.log(Lang.EN))
        assertFalse(text.contains(F.pairing.phoneToken!!))
        assertFalse(text.contains(F.pairing.appToken!!))
        assertTrue(text.contains("Relay: Running"))
        assertTrue(text.contains("Relay running · ready sent"))
    }
}
