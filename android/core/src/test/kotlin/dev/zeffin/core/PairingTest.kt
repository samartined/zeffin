// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing QR code (version 3 only) and the pinned key, against docs/protocol/pairing's examples and vector. */
class PairingTest {
    private val code = "Xq3fV0kq7mP2sYtR9wZb4A"
    private val pin = "J7pCvj_X0luG9SgZ7rrB0TGpD72G8BVi5G3NaLsCPS8"

    /** The pairing contract's files (the tests run in the module's folder). */
    private fun resource(name: String) = java.io.File("../../docs/protocol/pairing/$name").readText()

    @Test fun parsesTheContractsExample() {
        val qr = PairingQr.parse(resource("examples/qr.json"))
        assertEquals("https://100.98.12.7:9434", Addresses.display(qr.base))
        assertEquals("Qm9ndXMtcGFpci1jb2RlMQ", qr.code)
        assertEquals(pin, qr.key.text)
        assertFalse(qr.toString().contains(qr.code))
    }

    @Test fun theKeyVectorGivesThePinAndTheFingerprint() {
        val v = Json.parseToJsonElement(resource("key-vector.json")).jsonObject
        fun s(k: String) = v.getValue(k).jsonPrimitive.content
        val spki = s("spki_der_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = ServerAuth.PinnedKey.of(spki)
        assertEquals(s("pin"), key.text)
        assertEquals(s("fingerprint"), key.fingerprint)
        assertEquals(key, ServerAuth.PinnedKey.parse(s("pin")))
        assertTrue(key.matches(spki))
        assertFalse(key.matches(spki.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertEquals("PinnedKey(27BA 42BE 3FD7 D25B 86F5)", key.toString())
    }

    @Test fun aPinHasOneSpelling() {
        assertEquals(pin, ServerAuth.PinnedKey.parse(pin)?.text)
        for (bad in listOf("", pin.dropLast(1), pin + "A", pin.replace('_', '/'), pin.dropLast(1) + "9", "$pin=", " $pin")) {
            assertNull(bad, ServerAuth.PinnedKey.parse(bad))
        }
    }

    @Test fun refusesOlderAndOtherQrs() {
        val bad = mapOf(
            "https://example.com" to Addresses.Problem.NOT_A_PAIRING_QR,
            "[1,2]" to Addresses.Problem.NOT_A_PAIRING_QR,
            """{"fb":2,"url":"http://100.98.12.7:9434","code":"$code"}""" to Addresses.Problem.OLD_QR,
            """{"fb":1,"url":"http://100.98.12.7:9434","phone":"${"P".repeat(43)}","app":"${"A".repeat(43)}"}""" to Addresses.Problem.OLD_QR,
            """{"fb":4,"url":"https://100.98.12.7:9434","code":"$code","key":"$pin"}""" to Addresses.Problem.UNKNOWN_QR_VERSION,
            """{"fb":3,"url":"http://100.98.12.7:9434","code":"$code","key":"$pin"}""" to Addresses.Problem.CLEARTEXT,
            """{"fb":3,"url":"https://my-server:9434","code":"$code","key":"$pin"}""" to Addresses.Problem.NOT_PRIVATE,
            """{"fb":3,"url":"https://192.168.1.2:9434","code":"$code","key":"$pin"}""" to Addresses.Problem.NOT_PRIVATE,
            """{"fb":3,"code":"$code","key":"$pin"}""" to Addresses.Problem.QR_WITHOUT_ADDRESS,
            """{"fb":3,"url":"https://100.98.12.7:9434","key":"$pin"}""" to Addresses.Problem.QR_BAD_CODE,
            """{"fb":3,"url":"https://100.98.12.7:9434","code":"short","key":"$pin"}""" to Addresses.Problem.QR_BAD_CODE,
            """{"fb":3,"url":"https://100.98.12.7:9434","code":"$code/../x","key":"$pin"}""" to Addresses.Problem.QR_BAD_CODE,
            """{"fb":3,"url":"https://100.98.12.7:9434","code":"$code"}""" to Addresses.Problem.QR_BAD_KEY,
            """{"fb":3,"url":"https://100.98.12.7:9434","code":"$code","key":"${pin.dropLast(1)}"}""" to Addresses.Problem.QR_BAD_KEY,
        )
        for ((text, problem) in bad) {
            val e = assertThrows(text, Addresses.Invalid::class.java) { PairingQr.parse(text) }
            assertEquals(text, problem, e.problem)
        }
    }

    /** Seeded random inputs: parse never throws anything but Addresses.Invalid, and accepts only valid payloads. */
    @Test fun randomInputsNeverCrashAndOnlyValidOnesPass() {
        val gen = RandomQrs(java.util.Random(20261031), code, pin)
        var accepted = 0
        repeat(20_000) {
            val text = gen.next()
            val qr = runCatching { PairingQr.parse(text) }
                .onFailure { assertTrue("$text: ${it.javaClass.simpleName}", it is Addresses.Invalid) }.getOrNull()
            if (qr != null) {
                accepted++
                assertTrue(text, Regex("[A-Za-z0-9_-]{16,64}").matches(qr.code) && qr.key.text == pin && isCleanBase(qr.base))
            }
        }
        assertTrue("some generated QRs are valid ($accepted)", accepted > 50)
    }

    /** What parseBase itself would accept and give back unchanged. */
    private fun isCleanBase(base: okhttp3.HttpUrl) = Addresses.parseBase(Addresses.display(base)) == base
}

/** Pairing texts, mostly broken: JSON pieces in any order, and objects whose fields are valid, junk or missing. */
private class RandomQrs(private val rnd: java.util.Random, code: String, pin: String) {
    private val pieces = listOf("{", "}", "\"", ":", ",", "[", "]", "fb", "url", "code", "key", "1", "2", "3", "-1", "null",
        "true", "https://100.98.12.7:9434", "http://100.98.12.7:9434", "https://my-server:9434", "wss://100.98.12.7:9434/phone",
        "\\u0000", code, pin, "short", "$code/../x", " ", "\n", "é", "\uD83D")
    private val good = mapOf("url" to listOf("https://100.98.12.7:9434", "http://100.98.12.7:9434", "wss://100.98.12.7:9434/phone"),
        "code" to listOf(code), "key" to listOf(pin))
    private val versions = listOf("2", "3", "\"3\"", "4", "3.0")

    fun next(): String = if (rnd.nextBoolean()) junk() else
        "{" + listOf("fb", "url", "code", "key").filter { rnd.nextInt(4) != 0 }
            .joinToString(",") { k -> "\"$k\":" + (if (k == "fb") pick(versions) else value(k)) } + "}"

    private fun pick(from: List<String>) = from[rnd.nextInt(from.size)]
    private fun junk() = (0 until rnd.nextInt(12)).joinToString("") { pick(pieces) }
    private fun anyString() = when (rnd.nextInt(4)) {
        0 -> junk()
        1 -> String(CharArray(rnd.nextInt(70)) { (32 + rnd.nextInt(95)).toChar() })
        2 -> pick(pieces).dropLast(rnd.nextInt(10))
        else -> pick(pieces)
    }
    private fun value(k: String) = when (rnd.nextInt(4)) {
        0 -> anyString()
        1 -> "\"" + anyString().replace("\"", "") + "\""
        else -> "\"" + pick(good.getValue(k)) + "\""
    }
}
