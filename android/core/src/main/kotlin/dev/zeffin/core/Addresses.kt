// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the server is. One base address is stored (https://100.98.12.7:9434); the relay uses
 * wss://…/phone and the app wss://…/app, both derived here.
 *
 * Only https (or wss) to a literal address of the private network (PrivateAddresses): never cleartext (TLS
 * with the pinned key), and never a host name, whose lookup would leave the tunnel. No credentials, query or fragment.
 */
object Addresses {
    /** Why an address or a pairing QR was refused; the app words it in the user's language. */
    enum class Problem {
        NO_SCHEME, NOT_AN_ADDRESS, CREDENTIALS, QUERY, OTHER_PATH, CLEARTEXT, NOT_PRIVATE,
        NOT_A_PAIRING_QR, OLD_QR, UNKNOWN_QR_VERSION, QR_WITHOUT_ADDRESS, QR_BAD_CODE, QR_BAD_KEY,
    }

    class Invalid(val problem: Problem) : IllegalArgumentException(problem.name)

    /** Accepts a base (https) or a server URL (wss, with /phone or /app); returns the base. */
    fun parseBase(input: String): HttpUrl {
        var text = input.trim()
        when {
            text.startsWith("ws://", ignoreCase = true) -> text = "http://" + text.substring(5)
            text.startsWith("wss://", ignoreCase = true) -> text = "https://" + text.substring(6)
            !text.contains("://") -> throw Invalid(Problem.NO_SCHEME)
        }
        val url = text.toHttpUrlOrNull() ?: throw Invalid(Problem.NOT_AN_ADDRESS)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw Invalid(Problem.CREDENTIALS)
        if (url.query != null || url.fragment != null) throw Invalid(Problem.QUERY)
        val path = url.encodedPath.trimEnd('/')
        if (path !in setOf("", "/phone", "/app")) throw Invalid(Problem.OTHER_PATH)
        if (!url.isHttps) throw Invalid(Problem.CLEARTEXT)
        if (!CoreGraph.privateAddresses.isPrivate(url.host)) throw Invalid(Problem.NOT_PRIVATE)
        return url.newBuilder().encodedPath("/").build()
    }

    /** ws(s)://host:port/<path> from a base. OkHttp writes IPv6 brackets correctly. */
    fun socketUrl(base: HttpUrl, path: String): String {
        val url = base.newBuilder().encodedPath(path).build().toString()
        return if (base.isHttps) "wss://" + url.removePrefix("https://") else "ws://" + url.removePrefix("http://")
    }

    fun phoneUrl(base: HttpUrl) = socketUrl(base, "/phone")
    fun appUrl(base: HttpUrl) = socketUrl(base, "/app")
    fun checkUrl(base: HttpUrl): HttpUrl = base.newBuilder().encodedPath("/phone/check").build()
    fun viewUrl(base: HttpUrl): HttpUrl = base.newBuilder().encodedPath("/view").build()

    /** The same server: scheme, host and port (HttpUrl has already lowercased the host and filled in the port). */
    fun sameServer(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    /** "100.98.12.7:9434" for showing. */
    fun hostPort(base: HttpUrl): String {
        val host = if (base.host.contains(':')) "[${base.host}]" else base.host
        return "$host:${base.port}"
    }

    /** The base as typed in settings, without the trailing slash. */
    fun display(base: HttpUrl) = base.toString().trimEnd('/')
}
