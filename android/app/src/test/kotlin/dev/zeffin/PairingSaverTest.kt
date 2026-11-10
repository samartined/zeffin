// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.zeffin.core.Addresses
import dev.zeffin.core.PairingRecord
import dev.zeffin.core.PairingStore
import dev.zeffin.core.SecretCipher
import dev.zeffin.link.PairingPorts
import dev.zeffin.link.PairingSaver
import dev.zeffin.link.appTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Saving a pairing: the order (stop, write, start), and the snapshot /app is started from. */
class PairingSaverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val old = PairingRecord(Addresses.parseBase("https://100.98.12.7:9434"), "A".repeat(43), "P".repeat(43), Fixtures.key)
    private val new = PairingRecord(Addresses.parseBase("https://100.98.12.9:9434"), "B".repeat(43), "Q".repeat(43), TestTls.key)

    private class Ports(val relayRunning: Boolean, val failWrite: Boolean = false) : PairingPorts {
        val calls = mutableListOf<String>()
        override suspend fun stopLink() { calls += "stop link" }
        override suspend fun stopRelay(): Boolean { calls += "stop relay"; return relayRunning }
        override suspend fun write(record: PairingRecord) {
            calls += "write ${Addresses.hostPort(record.base)}"
            if (failWrite) error("disk full")
        }
        override suspend fun startLink() { calls += "start link" }
        override fun startRelay() { calls += "start relay" }
    }

    @Test fun stopsBothThenWritesThenStartsAgain() = runTest {
        val ports = Ports(relayRunning = true)
        PairingSaver(ports).save(new)
        assertEquals(listOf("stop link", "stop relay", "write 100.98.12.9:9434", "start link", "start relay"), ports.calls)
    }

    @Test fun aStoppedRelayStaysStopped() = runTest {
        val ports = Ports(relayRunning = false)
        PairingSaver(ports).save(new)
        assertEquals(listOf("stop link", "stop relay", "write 100.98.12.9:9434", "start link"), ports.calls)
    }

    @Test fun aFailedWriteStillStartsAgain() = runTest {
        val ports = Ports(relayRunning = true, failWrite = true)
        runCatching { PairingSaver(ports).save(new) }
        assertEquals(listOf("stop link", "stop relay", "write 100.98.12.9:9434", "start link", "start relay"), ports.calls)
    }

    @Test fun leavingTheScreenDoesNotCutASaveInHalf() = runTest {
        val ports = Ports(relayRunning = true)
        val slow = CompletableDeferred<Unit>()
        val saver = PairingSaver(object : PairingPorts by ports {
            override suspend fun stopLink() { ports.stopLink(); slow.await() }
        })
        val job = launch { saver.save(new) }
        yield()
        job.cancel()  // the pairing screen closed while the link was stopping
        slow.complete(Unit)
        job.join()
        assertEquals(listOf("stop link", "stop relay", "write 100.98.12.9:9434", "start link", "start relay"), ports.calls)
    }

    /** No-op encryption: this test is about which values travel together, not about the cipher. */
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(blob: ByteArray) = blob
    }

    @Test fun appIsStartedOnlyWithAnAddressAndTheTokenSavedForIt() = runTest {
        val store = PairingStore(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { File(tmp.root, "secrets.preferences_pb") }), Plain)
        store.save(old)
        val targets = mutableListOf<Pair<String?, String?>>()
        backgroundScope.launch { store.pairing.collect { targets += appTarget(it) } }
        repeat(20) { i ->
            store.save(if (i % 2 == 0) new else old)
            yield()
        }
        store.pairing.first { it == old }
        advanceUntilIdle()
        val valid = setOf(appTarget(old), appTarget(new))
        assertTrue(targets.toString(), targets.isNotEmpty() && targets.all { it in valid })
        assertEquals("wss://100.98.12.9:9434/app" to "B".repeat(43), appTarget(new))
        assertEquals(appTarget(old), targets.last())
    }
}
