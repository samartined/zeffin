// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no Android Keystore; the same AES-GCM with a software key stands in. */
class SoftwareCipher(seed: Byte = 7) : SecretCipher {
    private val key = SecretKeySpec(ByteArray(32) { seed }, "AES")
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return c.iv + c.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

@RunWith(RobolectricTestRunner::class)
class PairingStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cipher = SoftwareCipher()
    private val base = Addresses.parseBase("https://100.98.12.7:9434")
    private val key = ServerAuth.PinnedKey.parse("J7pCvj_X0luG9SgZ7rrB0TGpD72G8BVi5G3NaLsCPS8")!!
    /** Where a pairing from before the server's key was pinned points: cleartext, then. */
    private val oldBase = "http://100.98.12.7:9434".toHttpUrl()
    private val app = "app-token-" + "y".repeat(32)
    private val phone = "phone-token-" + "x".repeat(32)
    private val baseKey = stringPreferencesKey("base_url")
    private val appKey = stringPreferencesKey("app_token")
    private val phoneKey = stringPreferencesKey("phone_token")

    private fun file(name: String) = File(tmp.root, "$name.preferences_pb")

    private fun CoroutineScope.store(name: String, migrations: List<DataMigration<Preferences>> = emptyList()) =
        PreferenceDataStoreFactory.create(scope = this, migrations = migrations, produceFile = { file(name) })

    /** A phone as 0.5.0 left it: the address in the settings, each token encrypted apart in the secrets. */
    private suspend fun oldPhone(settings: DataStore<Preferences>, secrets: DataStore<Preferences>, withBase: Boolean = true) {
        if (withBase) settings.edit { it[baseKey] = "http://100.98.12.7:9434" }
        secrets.edit {
            it[appKey] = Base64.getEncoder().encodeToString(cipher.encrypt(app.toByteArray()))
            it[phoneKey] = Base64.getEncoder().encodeToString(cipher.encrypt(phone.toByteArray()))
        }
    }

    /** The 0.5.0 stores, filled and closed; then the settings and the pairing store on the same files, as this version opens them. */
    private suspend fun TestScope.upgrade(withBase: Boolean = true, before: suspend (DataStore<Preferences>) -> Unit = {}):
        Pair<DataStore<Preferences>, PairingStore> {
        val old = Job()
        with(CoroutineScope(Dispatchers.IO + old)) {
            val secrets = store("secrets")
            oldPhone(store("settings"), secrets, withBase)
            before(secrets)
        }
        old.cancelAndJoin()
        val settings = backgroundScope.store("settings")
        return settings to PairingStore(backgroundScope.store("secrets", listOf(PairingStore.migration(settings, cipher))), cipher)
    }

    @Test fun savesTheAddressTheKeyAndBothTokensTogetherEncrypted() = runTest {
        val ds = backgroundScope.store("secrets")
        val store = PairingStore(ds, cipher)
        assertNull(store.current())
        store.save(PairingRecord(base, app, phone, key))
        assertEquals(PairingRecord(base, app, phone, key), store.current())
        assertFalse(store.current()!!.needsPairing)
        assertEquals("PairingRecord(https://100.98.12.7:9434, 27BA 42BE 3FD7 D25B 86F5, app=•••• yyyy, phone=•••• xxxx)",
            store.current().toString())
        assertFalse(String(file("secrets").readBytes()).contains("token"))
        assertFalse(String(file("secrets").readBytes()).contains("100.98.12.7"))
        assertFalse(String(file("secrets").readBytes()).contains(key.text))
        // a key that no longer decrypts reads as "not paired", never as garbage
        assertNull(PairingStore(ds, SoftwareCipher(9)).current())
    }

    @Test fun aNewerFormatReadsAsNotPaired() = runTest {
        val ds = backgroundScope.store("secrets")
        ds.edit { it[stringPreferencesKey("pairing")] = blob("""{"v":3,"base":"https://100.98.12.7:9434","key":"${key.text}"}""") }
        assertNull(PairingStore(ds, cipher).current())
    }

    private fun blob(json: String) = Base64.getEncoder().encodeToString(cipher.encrypt(json.toByteArray()))

    @Test fun aPairingFromBeforeTheKeyNeedsPairingAgain() = runTest {
        val ds = backgroundScope.store("secrets")
        ds.edit { it[stringPreferencesKey("pairing")] = blob("""{"v":1,"base":"http://100.98.12.7:9434","app":"$app","phone":"$phone"}""") }
        val r = PairingStore(ds, cipher).current()!!
        assertEquals(PairingRecord(oldBase, app, phone, null), r)
        assertTrue(r.needsPairing)
        assertFalse("nothing that works to lose: no question", r.asksBeforeReplacing(base))
        assertNull("http is another server than https: its tokens are never carried over", r.tokensFor(base))
    }

    @Test fun aVersion2RecordWithoutItsKeyReadsAsNotPaired() = runTest {
        val ds = backgroundScope.store("secrets")
        for (json in listOf("""{"v":2,"base":"https://100.98.12.7:9434","app":"$app"}""",
            """{"v":2,"base":"https://100.98.12.7:9434","key":"short","app":"$app"}""")) {
            ds.edit { it[stringPreferencesKey("pairing")] = blob(json) }
            assertNull(json, PairingStore(ds, cipher).current())
        }
    }

    @Test fun updatingFrom050KeepsThePairing() = runTest {
        val (settings, pairing) = upgrade()
        assertEquals(PairingRecord(oldBase, app, phone, null), pairing.current())
        assertTrue("from before the key: it asks to pair again", pairing.current()!!.needsPairing)
        val secrets = file("secrets").readBytes().let(::String)
        assertFalse("the old tokens are gone", secrets.contains("app_token") || secrets.contains("phone_token"))
        assertNull("the old address is gone", settings.data.first()[baseKey])
    }

    @Test fun tokensWithoutAnAddressAreDropped() = runTest {
        val (_, pairing) = upgrade(withBase = false)
        assertNull(pairing.current())
        val secrets = file("secrets").readBytes().let(::String)
        assertFalse(secrets.contains("app_token") || secrets.contains("phone_token"))
    }

    @Test fun killedBetweenTheTwoWritesTheNextStartRemovesTheOldAddress() = runTest {
        // The record was written and the old tokens removed, but the app died before the settings lost base_url.
        val (settings, pairing) = upgrade { secrets ->
            secrets.edit {
                it.remove(appKey)
                it.remove(phoneKey)
                it[stringPreferencesKey("pairing")] = PairingStore.encode(cipher, PairingRecord(oldBase, app, null))
            }
        }
        assertEquals("the record is kept, not rebuilt from base_url", PairingRecord(oldBase, app, null), pairing.current())
        assertNull(settings.data.first()[baseKey])
    }

    @Test fun theSameServerTypedDifferentlyIsNotAnotherServer() {
        val saved = PairingRecord(base, app, phone, key)
        for (same in listOf("https://100.98.12.7:9434/", "HTTPS://100.98.12.7:9434", "wss://100.98.12.7:9434/phone", "https://100.98.12.7:9434/app")) {
            assertFalse(same, saved.isOtherServer(Addresses.parseBase(same)))
            assertFalse(same, saved.asksBeforeReplacing(Addresses.parseBase(same)))
            assertEquals(same, saved, saved.tokensFor(Addresses.parseBase(same)))
        }
        val dflt = PairingRecord(Addresses.parseBase("https://100.98.12.7"), app, phone, key)
        assertFalse(dflt.isOtherServer(Addresses.parseBase("https://100.98.12.7:443")))
        for (other in listOf("https://100.98.12.9:9434", "https://100.98.12.7:9435", "http://100.98.12.7:9434")) {
            assertTrue(other, saved.asksBeforeReplacing(other.toHttpUrl()))
            assertNull(other, saved.tokensFor(other.toHttpUrl()))
        }
        val elsewhere = Addresses.parseBase("https://100.98.12.9:9434")
        assertFalse("nothing to lose: no question", PairingRecord(base, null, null, key).asksBeforeReplacing(elsewhere))
        assertFalse("no key, nothing that works: no question", PairingRecord(base, app, phone).asksBeforeReplacing(elsewhere))
    }

    @Test fun theRelayOnAnotherServerNeverKeepsTheAppTokenOrTheKeyOfTheOldOne() {
        val saved = PairingRecord(base, app, phone, key)
        val other = Addresses.parseBase("https://100.98.12.9:9434")
        assertEquals(PairingRecord(other, null, "new-$phone", null), PairingRecord.forRelay(saved, other, "new-$phone"))
        assertEquals(PairingRecord(base, app, "new-$phone", key),
            PairingRecord.forRelay(saved, Addresses.parseBase("wss://100.98.12.7:9434/phone"), "new-$phone"))
        assertEquals(PairingRecord(other, null, phone), PairingRecord.forRelay(null, other, phone))
    }
}
