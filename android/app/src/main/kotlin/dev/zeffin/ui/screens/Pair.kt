// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import dev.zeffin.ui.LocalLang
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.FbButton
import dev.zeffin.ui.components.Divider
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.InfoRow
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.present.BannerUi
import dev.zeffin.ui.present.Fix
import dev.zeffin.ui.present.PairActions
import dev.zeffin.ui.present.PairStepUi
import dev.zeffin.ui.present.PasteUi
import dev.zeffin.ui.present.ReplaceUi
import dev.zeffin.ui.present.Tone

/**
 * Pairing with the server: the camera reads the QR code of the web view's Pair the app or of `zf pair`, or [paste] holds
 * the same payload pasted as text. The app checks the server's key, sends the code, and shows the number the server
 * shows too ([step]) until the owner confirms there. [camera] draws the preview (CameraX), or the reason it cannot.
 */
@Composable
fun PairScreen(paste: PasteUi?, banner: BannerUi?, step: PairStepUi?, actions: PairActions, camera: @Composable () -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Pair with the server", "Emparejar con el servidor"),
            if (paste == null) l.t("Scan the QR code of the web view or zf pair", "Escanea el QR de la vista web o de zf pair") else pasteTitle(),
            onBack = actions.onBack)
        PageBody(Modifier.weight(1f)) {
            banner?.let { BannerView(it, actions.onFix) }
            when {
                step is PairStepUi.Waiting -> Waiting(step, actions.onCancel)
                paste == null -> QrCard(step as? PairStepUi.Checking, actions.onPasteMode, camera)
                else -> PasteCard(paste, step, actions.onPaste)
            }
        }
        if (paste != null && step !is PairStepUi.Waiting) {
            FbButton(l.t("Pair", "Emparejar"), Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                enabled = paste.ready && step == null, onClick = actions.onPair)
        }
    }
}

@Composable
private fun pasteTitle() = LocalLang.current.t("Paste the pairing text", "Pegar el texto de emparejamiento")

/** The camera (or the key being checked), what to do, and the way to paste the text instead. */
@Composable
private fun QrCard(checking: PairStepUi.Checking?, onPasteMode: () -> Unit, camera: @Composable () -> Unit) {
    val l = LocalLang.current
    FbCard(title = l.t("QR code", "Código QR"), state = checking?.let { Tone.ACTION to l.t("Checking…", "Comprobando…") }) {
        item(divider = false) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceSunken),
                    contentAlignment = Alignment.Center) { if (checking != null) Checking(checking.host) else camera() }
                Txt(l.t("On a computer, open the web view and choose Pair the app, or run zf pair on the server, and point " +
                    "the camera at the QR code. The app checks the server's key, and you confirm on the server.",
                    "En un ordenador, abre la vista web y elige «Pair the app», o ejecuta zf pair en el servidor, y apunta " +
                    "la cámara al QR. La app comprueba la clave del servidor, y tú confirmas en el servidor."),
                    FbType.body, color = FbColors.inkMuted)
            }
        }
        item { ActionRow(FbIcons.copy, pasteTitle(), onClick = onPasteMode) }
    }
    Note(l.t("The QR code holds the server's key and a code that changes every 30 seconds, never the tokens. " +
        "Keep it to yourself: no photos, no screenshots. zf pair in the server's terminal is the most reliable place to show it.",
        "El QR lleva la clave del servidor y un código que cambia cada 30 segundos, nunca los tokens. " +
        "Guárdalo para ti: sin fotos ni capturas. zf pair en la terminal del servidor es el sitio más fiable para mostrarlo."))
}

/** The pasted text: its server and key (never its code), or where to copy it from, or why it is not valid. */
@Composable
private fun PasteCard(paste: PasteUi, step: PairStepUi?, onPaste: () -> Unit) {
    val l = LocalLang.current
    FbCard(title = l.t("Pairing text", "Texto de emparejamiento"), state = when {
        step is PairStepUi.Checking -> Tone.ACTION to l.t("Checking…", "Comprobando…")
        paste.error != null -> Tone.DANGER to l.t("Not valid", "No es válido")
        else -> null
    }) {
        if (paste.server != null && paste.fingerprint != null) {
            item(divider = false) { InfoRow(l.t("Server", "Servidor"), paste.server, mono = true) }
            item { InfoRow(l.t("Server key", "Clave del servidor"), paste.fingerprint, mono = true) }
            item { InfoRow(l.t("Code", "Código"), l.t("hidden", "oculto")) }
            item { ActionRow(FbIcons.copy, l.t("Paste again", "Pegar de nuevo"), enabled = step == null, onClick = onPaste) }
        } else {
            item(divider = false) {
                Txt(paste.error ?: l.t("In the web view: Pair the app, then Copy under the QR code. In a terminal: the line zf pair " +
                    "prints under the QR code.", "En la vista web: «Pair the app» y después «Copy» bajo el QR. En una terminal: la línea " +
                    "que zf pair escribe bajo el QR."), FbType.body, Modifier.padding(12.dp),
                    color = if (paste.error != null) FbColors.danger else FbColors.inkMuted)
            }
            item { ActionRow(FbIcons.copy, l.t("Paste", "Pegar"), onClick = onPaste) }
        }
    }
    Note(l.t("The text is as private as the QR code: other apps, such as the keyboard, can read the clipboard. " +
        "Its code works once and for about 35 seconds, and nothing is paired until you confirm on the server.",
        "El texto es tan privado como el QR: otras apps, como el teclado, pueden leer el portapapeles. " +
        "Su código vale una vez y unos 35 segundos, y no se empareja nada hasta que confirmes en el servidor."))
}

/** The request waits for the owner on the server: the number both screens show, the server, its key, and Cancel. */
@Composable
private fun Waiting(step: PairStepUi.Waiting, onCancel: () -> Unit) {
    val l = LocalLang.current
    FbCard(title = l.t("Confirm on the server", "Confirma en el servidor"), state = Tone.ACTION to l.t("Waiting…", "Esperando…")) {
        item(divider = false) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(step.number, FbType.mono.copy(fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 2.sp), align = TextAlign.Center)
                Txt(l.t("The same number is on the server: confirm it in the web view or in the zf pair terminal.",
                    "El mismo número está en el servidor: confírmalo en la vista web o en la terminal de zf pair."),
                    FbType.body, color = FbColors.inkMuted, align = TextAlign.Center)
            }
        }
        item { InfoRow(l.t("Server", "Servidor"), step.server, mono = true) }
        item { InfoRow(l.t("Server key", "Clave del servidor"), step.fingerprint, mono = true) }
        step.phone?.let { item { InfoRow(l.t("This phone", "Este móvil"), it, mono = true) } }
        item { InfoRow(l.t("Expires in", "Caduca en"), step.expiresIn) }
        item { ActionRow(FbIcons.close, l.t("Cancel", "Cancelar"), onClick = onCancel) }
    }
    Note(l.t("The server key proves this is your server; the number proves this request is yours. If the server shows another number, " +
        "refuse it there: someone else scanned your QR code.",
        "La clave del servidor demuestra que es tu servidor; el número, que esta petición es tuya. Si el servidor muestra otro número, " +
        "recházala allí: otra persona escaneó tu QR."))
}

/**
 * Before a working pairing is replaced by one with another server: the server paired now, the new one and its key, what
 * replacing it does, and the two ways out (Pair with the new one, Keep the current one).
 */
@Composable
fun BoxScope.ReplacePairingSheet(ui: ReplaceUi, onReplace: () -> Unit, onKeep: () -> Unit) {
    val l = LocalLang.current
    FbSheet(l.t("Pair with another server?", "¿Emparejar con otro servidor?"),
        l.t("This phone is paired with another server", "Este móvil está emparejado con otro servidor"), onDismiss = onKeep) {
        InfoRow(l.t("Paired now", "Emparejado ahora"), ui.now, mono = true)
        Divider()
        InfoRow(l.t("In the QR code", "En el QR"), ui.next, mono = true)
        Divider()
        InfoRow(l.t("Key", "Clave"), ui.fingerprint, mono = true)
        Divider()
        Txt(l.t("The app and the relay move to the new server and forget the current one's tokens. Go on only if you showed " +
            "this QR code yourself: the server you pair with sees the Flipper and drives it.",
            "La app y el relé pasan al servidor nuevo y olvidan los tokens del actual. Sigue sólo si el QR lo has mostrado tú: " +
            "el servidor con el que emparejas ve el Flipper y lo maneja."),
            FbType.body, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), color = FbColors.inkMuted)
        Divider()
        ActionRow(FbIcons.sync, l.t("Pair with ${ui.host}", "Emparejar con ${ui.host}"), onClick = onReplace)
        Divider()
        ActionRow(FbIcons.close, l.t("Keep the current pairing", "Conservar el emparejamiento actual"), onClick = onKeep)
    }
}

/** While the app checks the server's key: a spinner and the server it asks. */
@Composable
private fun Checking(host: String) {
    val l = LocalLang.current
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spinner()
        Txt(l.t("Checking the server's key", "Comprobando la clave del servidor"), FbType.bodyStrong, align = TextAlign.Center)
        Txt(l.t("$host · nothing is sent until it matches the QR code", "$host · no se envía nada hasta que coincida con el QR"),
            FbType.body, color = FbColors.inkMuted, align = TextAlign.Center)
    }
}

/** A quarter arc turning on a gray ring (the design has no other spinner); still in screenshots. */
@Composable
private fun Spinner() {
    val angle = if (LocalStillFrames.current) 0f else rememberInfiniteTransition(label = "spinner")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle").value
    Canvas(Modifier.size(36.dp)) {
        val width = 3.dp.toPx()
        val inset = Offset(width / 2, width / 2)
        val box = Size(size.width - width, size.height - width)
        drawArc(FbColors.line, 0f, 360f, false, inset, box, style = Stroke(width))
        drawArc(FbColors.action, angle - 90f, 90f, false, inset, box, style = Stroke(width, cap = StrokeCap.Round))
    }
}
