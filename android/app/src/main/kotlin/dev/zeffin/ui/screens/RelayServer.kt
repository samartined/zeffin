// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import dev.zeffin.ui.LocalLang
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.components.ActionRow
import dev.zeffin.ui.components.FbButton
import dev.zeffin.ui.components.FbCard
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.PageBody
import dev.zeffin.ui.components.TopBar
import dev.zeffin.ui.components.UnderlineTextField
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.present.RelayServerUi
import dev.zeffin.ui.present.address
import dev.zeffin.core.Addresses

@Composable
fun RelayServerScreen(
    ui: RelayServerUi,
    onBack: () -> Unit,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val l = LocalLang.current
    Column(Modifier.fillMaxSize().background(FbColors.surface)) {
        TopBar(l.t("Relay server", "Servidor del relé"), onBack = onBack)
        PageBody(Modifier.weight(1f)) {
            FbCard(title = l.t("Server", "Servidor")) {
                item(divider = false) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        UnderlineTextField(l.t("Server URL", "URL del servidor"), ui.url, onUrl,
                            hint = l.address(Addresses.Problem.NOT_PRIVATE), error = ui.urlError)
                        UnderlineTextField("Phone token", ui.token, onToken, hint = ui.tokenHint, error = ui.tokenError, secret = true)
                    }
                }
                item { ActionRow(FbIcons.sync, if (ui.testing) l.t("Testing…", "Probando…") else l.t("Test server", "Probar servidor"), enabled = !ui.testing, onClick = onTest) }
            }
            ui.result?.let { BannerView(it) {} }
            Note(l.t("Only the paired server's Phone token changes here. To use another server, pair with its QR code. Changes apply when " +
                "you save; if the relay is running, it restarts.",
                "Aquí sólo cambia el Phone token del servidor emparejado. Para usar otro servidor, empareja con su QR. Los cambios se " +
                "aplican al guardar; si el relé está en marcha, se reinicia."))
        }
        FbButton(l.t("Save", "Guardar"), Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), onClick = onSave)
    }
}
