// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

data class DeviceUi(val name: String, val address: String, val looksLikeFlipper: Boolean, val current: Boolean)

data class DeviceListUi(val devices: List<DeviceUi>, val banner: BannerUi?)
