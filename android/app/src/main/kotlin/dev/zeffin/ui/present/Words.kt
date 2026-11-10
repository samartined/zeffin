// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Addresses
import dev.zeffin.core.Lang

/** Why an address or a pairing QR was refused, in [this] language. */
fun Lang.address(problem: Addresses.Problem): String = when (problem) {
    Addresses.Problem.NO_SCHEME -> t("The scheme is missing: start with https://", "Falta el esquema: empieza por https://")
    Addresses.Problem.NOT_AN_ADDRESS -> t("This is not a valid address", "No es una dirección válida")
    Addresses.Problem.CREDENTIALS -> t("The address cannot carry a user or password", "La dirección no puede llevar usuario ni contraseña")
    Addresses.Problem.QUERY -> t("The address cannot carry ? or #", "La dirección no puede llevar ? ni #")
    Addresses.Problem.OTHER_PATH -> t("Use the server's address, without other paths", "Usa la dirección del servidor, sin otras rutas")
    Addresses.Problem.CLEARTEXT -> t("The server must use https: update it (zf update) and show a new QR code",
        "El servidor tiene que usar https: actualízalo (zf update) y muestra otro QR")
    Addresses.Problem.NOT_PRIVATE -> t("Only wss:// to the server's $net IP", "Sólo wss:// a la IP de $net del servidor")
    Addresses.Problem.NOT_A_PAIRING_QR -> t("This is not the QR code or the text of zf pair", "No es el QR ni el texto de zf pair")
    Addresses.Problem.OLD_QR -> t("This QR code is from an older server. Update the server (zf update) and show a new QR code.",
        "Este QR es de un servidor antiguo. Actualiza el servidor (zf update) y muestra otro QR.")
    Addresses.Problem.UNKNOWN_QR_VERSION -> t("QR code from a server version this app does not know", "QR de una versión del servidor que esta app no conoce")
    Addresses.Problem.QR_WITHOUT_ADDRESS -> t("The QR code has no address", "Al QR le falta la dirección")
    Addresses.Problem.QR_BAD_CODE -> t("The code in the QR code is not valid", "El código del QR no es válido")
    Addresses.Problem.QR_BAD_KEY -> t("The server's key in the QR code is not valid", "La clave del servidor del QR no es válida")
}

/** The message of a failed address or pairing parse. */
fun Lang.address(error: Throwable): String = (error as? Addresses.Invalid)?.let { address(it.problem) } ?: t("Not valid", "No es válido")
