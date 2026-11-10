// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zeffin.AppGraph
import dev.zeffin.core.Addresses
import dev.zeffin.core.Lang
import dev.zeffin.core.PairingRecord
import dev.zeffin.core.isPlausibleToken
import dev.zeffin.core.maskToken
import dev.zeffin.relay.CheckResult
import dev.zeffin.relay.RelayStrings
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.BannerUi
import dev.zeffin.ui.present.Fmt
import dev.zeffin.ui.present.RelayServerUi
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.present.address
import dev.zeffin.ui.screens.RelayServerScreen
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/**
 * The relay's Phone token for the paired server, tried before it is saved. Another server needs its pairing (its QR
 * code holds its key), so another address is refused here.
 */
@Composable
fun RelayServerRoute(graph: AppGraph, nav: AppNavigator) {
    val scope = rememberCoroutineScope()
    val saved by graph.pairing.collectAsState()
    val form = rememberSaveable(saver = RelayServerForm.Saver) { RelayServerForm(graph.pairing.value?.base?.let(Addresses::phoneUrl) ?: "") }
    val l = LocalLang.current
    fun save(base: HttpUrl, token: String) = scope.launch {
        graph.pairingSaver.save(PairingRecord.forRelay(graph.pairing.value, base, token))
        nav.back()
    }
    Box(Modifier.fillMaxSize()) {
        RelayServerScreen(
            RelayServerUi(form.url, form.urlError, form.token, form.hint(l, saved), form.tokenError, form.testing, form.result),
            onBack = nav::back,
            onUrl = { form.url = it; form.urlError = null },
            onToken = { form.token = it.trim(); form.tokenError = null },
            onTest = {
                val v = form.validate(l, saved) ?: return@RelayServerScreen
                form.testing = true
                form.result = null
                scope.launch {
                    val start = System.nanoTime()
                    val r = graph.relay.checkServer(v.first, v.second)
                    form.result = checkBanner(r, (System.nanoTime() - start) / 1_000_000, l)
                    form.testing = false
                }
            },
            onSave = { form.validate(l, saved)?.let { (base, token) -> save(base, token) } },
        )
    }
}

/** The form's state. Only the address survives a rotation: a token never becomes saved state. */
private class RelayServerForm(url: String) {
    var url by mutableStateOf(url)
    var token by mutableStateOf("")
    var urlError by mutableStateOf<String?>(null)
    var tokenError by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)
    var result by mutableStateOf<BannerUi?>(null)

    /** The saved Phone token while the address is the same server's (or does not parse yet), else a word on what to type. */
    fun hint(l: Lang, saved: PairingRecord?): String {
        val base = runCatching { Addresses.parseBase(url) }.getOrNull()
        if (base != null && saved?.phoneToken != null && saved.isOtherServer(base)) {
            return l.t("Another server: pair with it with its QR code", "Otro servidor: empareja con él con su QR")
        }
        return saved?.phoneToken?.let { l.t("Saved: ${maskToken(it)}. Leave it empty to keep it.", "Guardado: ${maskToken(it)}. Déjalo vacío para conservarlo.") }
            ?: l.t("It comes from zf token phone on the server", "Sale de «zf token phone» en el servidor")
    }

    /** The address and the token to use (the saved one when the field is empty, only for the same server), or null with the errors shown. */
    fun validate(l: Lang, saved: PairingRecord?): Pair<HttpUrl, String>? {
        val base = runCatching { Addresses.parseBase(url) }.onFailure { urlError = l.address(it) }.getOrNull()
            ?.takeIf { b ->
                urlError = when {
                    saved?.key == null -> l.t("Pair with the server first: its QR code holds its key", "Empareja antes con el servidor: su QR lleva su clave")
                    saved.isOtherServer(b) -> l.t("Another server: pair with it with its QR code", "Otro servidor: empareja con él con su QR")
                    else -> null
                }
                urlError == null
            }
        val t = token.trim().ifEmpty { base?.let { saved?.tokensFor(it)?.phoneToken } }
        tokenError = when {
            t == null && base != null && saved?.isOtherServer(base) == true ->
                l.t("Another server: enter its Phone token", "Otro servidor: escribe su Phone token")
            t == null -> l.t("The Phone token is missing", "Falta el Phone token")
            !isPlausibleToken(t) -> l.t("It does not look like a zeffin token (32 characters or more)", "No parece un token de zeffin (32 caracteres o más)")
            else -> null
        }
        return if (base != null && tokenError == null && t != null) base to t else null
    }

    companion object {
        val Saver = listSaver<RelayServerForm, String>(save = { listOf(it.url) }, restore = { RelayServerForm(it[0]) })
    }
}

/** What trying the server found, after [ms]. */
private fun checkBanner(r: CheckResult, ms: Long, l: Lang): BannerUi = when (r) {
    is CheckResult.Ok -> BannerUi(Tone.SUCCESS, l.t("Server OK", "Servidor correcto"), null,
        l.t("${r.server.name ?: "server"} · ready in ${Fmt.seconds(l, ms)}", "${r.server.name ?: "servidor"} · listo en ${Fmt.seconds(l, ms)}") +
            (r.relay?.let { l.t(" · relay connected", " · relé conectado") } ?: ""))
    CheckResult.Rejected -> BannerUi(Tone.DANGER, l.t("The server does not accept this Phone token", "El servidor no acepta este Phone token"), null, "HTTP 401")
    CheckResult.Outdated -> BannerUi(Tone.DANGER, l.t("The server answers, but it is too old: zf update", "El servidor responde, pero es demasiado antiguo: zf update"), null, "HTTP 404")
    is CheckResult.Unreachable -> BannerUi(Tone.DANGER, l.t("The server could not be tested", "No se pudo probar el servidor"),
        RelayStrings.of(l).net(r.error))
}
