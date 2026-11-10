// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.core.NoTunnel
import dev.zeffin.core.PairingQr
import dev.zeffin.link.PairExchange
import dev.zeffin.relay.NetError
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Pairing from the app's side (docs/protocol/pairing/README.md), against a TLS server with the tests' key. */
class PairExchangeTest {
    private lateinit var server: MockWebServer
    private val phone = "p".repeat(43)
    private val app = "a".repeat(43)
    private val code = "Xq3fV0kq7mP2sYtR9wZb4A"

    @Before fun setUp() { server = MockWebServer().tls(); server.start() }
    @After fun tearDown() = server.close()

    private fun example(name: String) = requireNotNull(javaClass.classLoader!!.getResource("pairing/examples/$name.json")) { name }.readText()
    private fun answer(code: Int, body: String = "{}") = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun qr(key: dev.zeffin.core.ServerAuth.PinnedKey = TestTls.key) = PairingQr(server.base(), code, key)
    private fun exchange(net: FakeNetwork = FakeNetwork()) = PairExchange(testHttp(net, key = null))  // the QR code brings the key
    private fun start(qr: PairingQr = qr()) = runBlocking { exchange().start(qr) }
    private val paired get() = """{"url":"${server.base().toString().trimEnd('/')}","phone":"$phone","app":"$app"}"""

    @Test fun theCodeThenTheConfirmationThenTheTokens() = runBlocking {
        answer(202, example("pair.waiting"))
        answer(202, example("wait.waiting"))
        answer(200, paired)
        val ex = exchange()
        val w = ex.start(qr()).getOrThrow()
        assertEquals("482913", w.number)
        assertEquals(120, w.expiresInS)
        assertFalse(w.toString().contains("RmFrZS10aWNrZXQtMDAwMQ"))
        val left = mutableListOf<Int>()
        val pairing = ex.wait(qr(), w) { left += it }.getOrThrow()
        assertEquals(phone to app, pairing.phoneToken to pairing.appToken)
        assertEquals(TestTls.key, pairing.key)
        assertEquals(server.base(), pairing.base)
        assertEquals(listOf(97), left)
        val first = server.takeRequest()
        assertEquals("POST" to "/app/pair", first.method to first.target)
        assertEquals("""{"code":"$code"}""", first.body!!.utf8())
        assertNull(first.headers["Authorization"])
        repeat(2) {
            val wait = server.takeRequest()
            assertEquals("/app/pair/wait", wait.target)
            assertEquals("""{"pairing":"RmFrZS10aWNrZXQtMDAwMQ"}""", wait.body!!.utf8())
        }
    }

    @Test fun anotherKeyNeverSeesTheCode() {
        answer(202, example("pair.waiting"))
        assertEquals(PairExchange.Failure.KeyMismatch, start(qr(TestTls.other)).exceptionOrNull())
        assertEquals(0, server.requestCount)
    }

    @Test fun theCodesOutcomes() {
        for ((status, failure) in listOf(401 to PairExchange.Failure.BadCode, 409 to PairExchange.Failure.CodeUsed,
            410 to PairExchange.Failure.CodeExpired)) {
            answer(status, """{"error":"x"}""")
            assertEquals("$status", failure, start().exceptionOrNull())
        }
        answer(202, """{"pairing":"RmFrZS10aWNrZXQtMDAwMQ","number":"4829","expires_in":120}""")
        assertEquals(PairExchange.Failure.Net(NetError.BadResponse), start().exceptionOrNull())
    }

    @Test fun theConfirmationsOutcomes() = runBlocking {
        for ((status, failure) in listOf(403 to PairExchange.Failure.Refused, 410 to PairExchange.Failure.Expired,
            404 to PairExchange.Failure.NotFinished, 502 to PairExchange.Failure.Net(NetError.Http(502)))) {
            answer(202, example("pair.waiting"))
            answer(status, """{"error":"x"}""")
            val ex = exchange()
            val w = ex.start(qr()).getOrThrow()
            assertEquals("$status", failure, ex.wait(qr(), w).exceptionOrNull())
        }
    }

    @Test fun theTokensMustBeForTheQrCodesServer() = runBlocking {
        answer(202, example("pair.waiting"))
        answer(200, """{"url":"https://100.98.12.9:9434","phone":"$phone","app":"$app"}""")
        val ex = exchange()
        val w = ex.start(qr()).getOrThrow()
        assertEquals(PairExchange.Failure.Net(NetError.BadResponse), ex.wait(qr(), w).exceptionOrNull())
    }

    @Test fun cancellingSendsTheTicket() = runBlocking {
        answer(202, example("pair.waiting"))
        answer(200, example("cancel.done"))
        val ex = exchange()
        ex.cancel(qr(), ex.start(qr()).getOrThrow())
        server.takeRequest()
        val cancel = server.takeRequest()
        assertEquals("/app/pair/cancel", cancel.target)
        assertEquals("""{"pairing":"RmFrZS10aWNrZXQtMDAwMQ"}""", cancel.body!!.utf8())
    }

    @Test fun withoutTheTunnelTheCodeIsNotSent() {
        answer(202, example("pair.waiting"))
        val r = runBlocking { exchange(FakeNetwork(NoTunnel.Why.NO_VPN)).start(qr()) }
        assertEquals(PairExchange.Failure.Net(NetError.NoTunnel(NoTunnel.Why.NO_VPN)), r.exceptionOrNull())
        assertEquals(0, server.requestCount)
    }

    @Test fun otherAnswersAreNetworkProblems() {
        answer(502, "Bad gateway")
        assertEquals(PairExchange.Failure.Net(NetError.Http(502)), start().exceptionOrNull())
    }
}
