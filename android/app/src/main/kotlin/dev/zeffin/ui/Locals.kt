// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.zeffin.core.Lang

/** The language the screens speak in; provided at the root from the configuration. */
val LocalLang = staticCompositionLocalOf { Lang.EN }
