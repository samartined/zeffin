// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zeffin.AppGraph
import dev.zeffin.core.Addresses
import dev.zeffin.core.PairingQr
import dev.zeffin.core.PairingRecord
import dev.zeffin.link.PairExchange
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.QrCamera
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.present.BannerUi
import dev.zeffin.ui.present.Fix
import dev.zeffin.ui.present.PairStepUi
import dev.zeffin.ui.present.PasteUi
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.ReplaceUi
import dev.zeffin.ui.present.address
import dev.zeffin.ui.present.PairActions
import dev.zeffin.ui.screens.PairScreen
import dev.zeffin.ui.screens.ReplacePairingSheet
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pairing with the server, by its QR code or the same payload pasted as text (docs/protocol/pairing/README.md): the
 * server's key is checked before the code is sent, then the request waits until the owner confirms the same number on
 * the server, and only then are the tokens saved. No link or intent starts it: only the camera and the clipboard.
 */
@Composable
fun PairRoute(graph: AppGraph, nav: AppNavigator, startPaste: Boolean) {
    val context = LocalContext.current
    val l = LocalLang.current
    val present = remember(l) { Present(l) }
    val scope = rememberCoroutineScope()
    val saved by graph.pairing.collectAsState()
    val form = rememberSaveable(saver = PairForm.Saver) { PairForm(startPaste) }
    var cameraAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    LaunchedEffect(form.paste) { if (!form.paste && !cameraAllowed) cameraPermission.launch(Manifest.permission.CAMERA) }
    val now = rememberNow()

    /** Checks the key and sends the code, then waits for the owner's answer on the server; saves only a confirmed pairing. */
    fun pair(qr: PairingQr, text: String) {
        form.banner = null
        form.checking = qr.base.host
        form.job = scope.launch {
            val w = graph.pairExchange.start(qr).getOrElse { form.failed(present.pairFailure(it), text); return@launch }
            form.deadline = System.currentTimeMillis() + w.expiresInS * 1000L
            form.waiting = qr to w
            val result = graph.pairExchange.wait(qr, w) { left -> form.deadline = System.currentTimeMillis() + left * 1000L }
            form.waiting = null
            result.onSuccess {
                graph.pairingSaver.save(PairingRecord.of(it))
                nav.back()
            }.onFailure { form.failed(present.pairFailure(it), text) }
        }
    }

    /** A QR code or a pasted text taken: paired, or first asked about when it would replace a working pairing with another server. */
    fun take(qr: PairingQr, text: String) {
        if (saved?.asksBeforeReplacing(qr.base) == true) form.asking = qr to text else pair(qr, text)
    }

    /** The owner gave up, or left the screen: the server is told, and that pairing ends there too. */
    fun cancel() {
        form.job?.cancel()
        form.waiting?.let { (qr, w) -> graph.cancelPairing(qr, w) }
        form.waiting = null
        form.checking = null
    }
    DisposableEffect(Unit) { onDispose { cancel() } }

    Box(Modifier.fillMaxSize()) {
        PairScreen(if (form.paste) form.pasteUi else null, form.banner, form.stepUi(present, graph.ownAddress(), now), PairActions(
            onBack = { if (!form.backToCamera(startPaste)) nav.back() },
            onPasteMode = { form.paste = true },
            onFix = form::fix,
            onPaste = { form.pasted(context.clipboardText(), present) },
            onPair = { form.pastedQr?.let { take(it.first, it.second) } },
            onCancel = {
                cancel()
                form.banner = null
            },
        )) { PairCamera(cameraAllowed) { text -> form.scanned(text, present, ::take) } }
        // A QR code for another server than the one paired: asked first (the sheet over the screen).
        AskBeforeReplacing(form, saved) { qr, text -> pair(qr, text) }
    }
}

/**
 * The pairing screen's state. Only the mode survives as saved state: a code, a pasted text or a ticket never does. The
 * activity is not recreated on rotation, so a request that waits survives it.
 */
private class PairForm(paste: Boolean) {
    var paste by mutableStateOf(paste)
    var banner by mutableStateOf<BannerUi?>(null)
    var checking by mutableStateOf<String?>(null)  // the server's host while its key is checked and the code sent
    var waiting by mutableStateOf<Pair<PairingQr, PairExchange.Waiting>?>(null)  // the request, until the owner answers
    var deadline by mutableLongStateOf(0L)
    var job: Job? = null
    var refused by mutableStateOf<String?>(null)  // the QR that just failed: not tried again by itself
    var asking by mutableStateOf<Pair<PairingQr, String>?>(null)  // a QR for another server, until the owner answers
    var pastedQr by mutableStateOf<Pair<PairingQr, String>?>(null)
    var pasteUi by mutableStateOf(PasteUi())

    val busy get() = checking != null || waiting != null

    /** Where pairing is now, with the seconds left counted from [now]. */
    fun stepUi(present: Present, phone: String?, now: Long): PairStepUi? = waiting?.let { (qr, w) ->
        PairStepUi.Waiting(present.confirmNumber(w.number), Addresses.hostPort(qr.base), qr.key.fingerprint, phone,
            present.countdown(((deadline - now) / 1000).toInt()))
    } ?: checking?.let { PairStepUi.Checking(it) }

    /** Back from the pasting mode to the camera, when the screen opened on the camera and nothing runs; false otherwise. */
    fun backToCamera(startPaste: Boolean): Boolean = (paste && !startPaste && !busy).also { if (it) paste = false }

    /** The banner's Try again: the QR that failed may be read again. */
    fun fix(fix: Fix) {
        if (fix == Fix.RETRY) {
            refused = null
            banner = null
        }
    }

    fun failed(why: BannerUi, text: String) {
        checking = null
        waiting = null
        refused = text
        banner = why
    }

    /** A QR code the camera read: true when it was taken (to be paired or asked about) or refused. */
    fun scanned(text: String, present: Present, take: (PairingQr, String) -> Unit): Boolean {
        if (text == refused || busy || asking != null) return false
        val qr = runCatching { PairingQr.parse(text) }.getOrElse {
            refused = text
            banner = present.invalidQr(it)
            null
        }
        qr?.let { take(it, text) }
        return qr != null
    }

    /** The clipboard's text: what it holds (never its code), or why it is not a pairing text. */
    fun pasted(text: String, present: Present) {
        banner = null
        val qr = runCatching { PairingQr.parse(text) }
        pastedQr = qr.getOrNull()?.let { it to text }
        pasteUi = qr.fold({ PasteUi(Addresses.hostPort(it.base), it.key.fingerprint) },
            { PasteUi(error = if (text.isBlank()) present.emptyClipboard() else present.pasteError(it)) })
    }

    companion object {
        val Saver = listSaver<PairForm, Boolean>(save = { listOf(it.paste) }, restore = { PairForm(it[0]) })
    }
}

/** The clipboard's first item as text, or nothing. */
private fun Context.clipboardText(): String = getSystemService(ClipboardManager::class.java).primaryClip
    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

/** The camera's preview reading QR codes, or why it cannot. */
@Composable
private fun PairCamera(allowed: Boolean, onText: (String) -> Boolean) {
    val l = LocalLang.current
    if (allowed) QrCamera(onText)
    else Txt(l.t("No camera permission: paste the pairing text instead, or allow the camera in the app's settings.",
        "Sin permiso de cámara: pega el texto de emparejamiento, o permite la cámara en los ajustes de la app."),
        FbType.body, color = FbColors.inkMuted)
}

/** The sheet while a QR code for another server waits for an answer: [go] on, or keep the pairing (that QR is not read again at once). */
@Composable
private fun BoxScope.AskBeforeReplacing(form: PairForm, saved: PairingRecord?, go: (PairingQr, String) -> Unit) {
    val (qr, text) = form.asking ?: return
    val now = saved ?: return
    ReplacePairingSheet(ReplaceUi(Addresses.hostPort(now.base), Addresses.hostPort(qr.base), qr.key.fingerprint, qr.base.host),
        onReplace = { form.asking = null; go(qr, text) },
        onKeep = { form.asking = null; form.refused = text })
}
