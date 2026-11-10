// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

data class RelayServerUi(
    val url: String,
    val urlError: String?,
    val token: String,
    val tokenHint: String,
    val tokenError: String?,
    val testing: Boolean,
    val result: BannerUi?,
)

/** What the pairing screen's buttons do. */
data class PairActions(
    val onBack: () -> Unit = {},
    val onPasteMode: () -> Unit = {},
    val onFix: (Fix) -> Unit = {},
    val onPaste: () -> Unit = {},
    val onPair: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/** Where pairing is, while it runs: the server's key being checked, or the request waiting for the owner on the server. */
sealed interface PairStepUi {
    data class Checking(val host: String) : PairStepUi
    /** [number] as both screens show it ("482 913"); [phone] is this phone's own private address, when it is known. */
    data class Waiting(val number: String, val server: String, val fingerprint: String, val phone: String?, val expiresIn: String) : PairStepUi
}

/** "Paste the pairing text": what the pasted text holds (never its code), or why it is not valid. */
data class PasteUi(val server: String? = null, val fingerprint: String? = null, val error: String? = null) {
    val ready get() = server != null && error == null
}

/** Before a working pairing is replaced: the server paired now and the new one (host:port), its key and its host. */
data class ReplaceUi(val now: String, val next: String, val fingerprint: String, val host: String)
