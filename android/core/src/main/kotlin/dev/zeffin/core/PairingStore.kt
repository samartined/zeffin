// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/**
 * The pairing as the app keeps it: the server's address, its pinned key and the two tokens it gave, saved and read
 * together, so an address is never used with another server's key or tokens. [key] is null only in a pairing from
 * before the server's key was pinned: nothing is sent with it, and the owner pairs again ([needsPairing]). [appToken] is null only after
 * the relay alone moved to another server. A token is never logged or shown; screens get maskToken().
 */
data class PairingRecord(val base: HttpUrl, val appToken: String?, val phoneToken: String?, val key: ServerAuth.PinnedKey? = null) {
    override fun toString() =
        "PairingRecord(${Addresses.display(base)}, ${key?.fingerprint ?: "no key"}, app=${maskToken(appToken)}, phone=${maskToken(phoneToken)})"

    /** Saved before the app checked the server's key: it never connects, and the owner pairs again. */
    val needsPairing get() = key == null

    /** Whether [other] is a different server (scheme, host and port): the same one typed differently is not. */
    fun isOtherServer(other: HttpUrl) = !Addresses.sameServer(base, other)

    /**
     * Replacing this pairing with one for [other] asks first: it works (it holds a key and a token), and [other] is
     * another server. A pairing without a key does not work, so there is nothing to lose.
     */
    fun asksBeforeReplacing(other: HttpUrl) = key != null && (appToken != null || phoneToken != null) && isOtherServer(other)

    /** This pairing's key and tokens, to keep where a form leaves a token empty: only for the same server, never another. */
    fun tokensFor(other: HttpUrl): PairingRecord? = takeIf { !isOtherServer(other) }

    companion object {
        fun of(p: Pairing) = PairingRecord(p.base, p.appToken, p.phoneToken, p.key)

        /**
         * Relay server's save: the relay's address and Phone token. The same server keeps the app's token and the key;
         * another server drops both (they belong to the old one), so the app pairs again.
         */
        fun forRelay(saved: PairingRecord?, base: HttpUrl, phoneToken: String): PairingRecord {
            val same = saved?.tokensFor(base)
            return PairingRecord(base, same?.appToken, phoneToken, same?.key)
        }
    }
}

/**
 * The pairing record, one encrypted value in the secrets DataStore: one edit writes the address, the key and both tokens
 * at once, and every read is one snapshot of them. Backups are off for the whole app, so it never leaves the phone.
 */
class PairingStore(private val store: DataStore<Preferences>, private val cipher: SecretCipher) {
    /** The last blob read and its record: every request asks for the key (ServerHttp), so each write is decrypted once. */
    @Volatile private var last: Pair<String?, PairingRecord?> = null to null
    val pairing: Flow<PairingRecord?> = store.data.map { p -> p[KEY].let { blob -> last.takeIf { it.first == blob }?.second ?: decode(cipher, blob).also { last = blob to it } } }
        .distinctUntilChanged()

    suspend fun current(): PairingRecord? = pairing.first()

    /** Replaces the whole pairing in one write; null forgets it. */
    suspend fun save(record: PairingRecord?) {
        store.edit { if (record == null) it.remove(KEY) else it[KEY] = encode(cipher, record) }
    }

    companion object {
        /**
         * The record's format: 2 holds the server's key. 1 has none: it is still read, as a pairing that
         * needs pairing again, and written only for a pairing without a key (the move from older versions). A newer
         * one (after a downgrade) reads as not paired.
         */
        const val VERSION = 2
        private const val VERSION_WITHOUT_KEY = 1
        internal val KEY = stringPreferencesKey("pairing")

        // In older versions the address was in the settings and each token apart in the secrets.
        internal val OLD_BASE = stringPreferencesKey("base_url")
        internal val OLD_PHONE = stringPreferencesKey("phone_token")
        internal val OLD_APP = stringPreferencesKey("app_token")

        internal fun encode(cipher: SecretCipher, r: PairingRecord): String {
            val json = buildJsonObject {
                put("v", if (r.key != null) VERSION else VERSION_WITHOUT_KEY)
                put("base", Addresses.display(r.base))
                r.key?.let { put("key", it.text) }
                r.appToken?.let { put("app", it) }
                r.phoneToken?.let { put("phone", it) }
            }
            return Base64.getEncoder().encodeToString(cipher.encrypt(json.toString().toByteArray()))
        }

        /** A blob that no longer decrypts (key lost after a restore, say) or does not parse counts as not paired. */
        internal fun decode(cipher: SecretCipher, blob: String?): PairingRecord? = blob
            ?.let { runCatching { Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(it)))).jsonObject }.getOrNull() }
            ?.let { o ->
                val key = str(o, "key")?.let(ServerAuth.PinnedKey::parse)
                val version = (o["v"] as? JsonPrimitive)?.intOrNull
                val base = str(o, "base")?.toHttpUrlOrNull()
                // Version 2 needs its key; version 1 never has one (a pairing from before the key).
                val readable = if (version == VERSION) key != null else version == VERSION_WITHOUT_KEY
                if (base == null || !readable) null else PairingRecord(base, str(o, "app"), str(o, "phone"), key.takeIf { version == VERSION })
            }

        private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** An address saved by an older version: cleartext to the private network then, so it needs pairing again now. */
        private fun oldBase(text: String) = text.trim().toHttpUrlOrNull()
            ?.takeIf { it.scheme == "http" && CoreGraph.privateAddresses.isPrivate(it.host) }
            ?.newBuilder()?.encodedPath("/")?.build()

        private fun oldToken(cipher: SecretCipher, blob: String?): String? = blob?.let {
            runCatching { String(cipher.decrypt(Base64.getDecoder().decode(it))) }.getOrNull()
        }

        /**
         * Moves a pairing saved by an older version into the record, before anything reads the secrets: the address from
         * [settings] (passed in, never opened twice) and the tokens from the secrets themselves. Tokens without a valid
         * address are dropped. The record and the removal of the old tokens are one write; the old address is removed
         * from the settings after it, and again on the next start if the app was killed in between.
         */
        fun migration(settings: DataStore<Preferences>, cipher: SecretCipher): DataMigration<Preferences> = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                val oldTokens = OLD_PHONE in currentData || OLD_APP in currentData
                return (KEY !in currentData && oldTokens) || OLD_BASE in settings.data.first()
            }

            override suspend fun migrate(currentData: Preferences): Preferences {
                val p = currentData.toMutablePreferences()
                if (KEY !in p) {
                    val base = settings.data.first()[OLD_BASE]?.let(::oldBase)
                    val app = oldToken(cipher, p[OLD_APP])
                    val phone = oldToken(cipher, p[OLD_PHONE])
                    if (base != null) p[KEY] = encode(cipher, PairingRecord(base, app, phone))
                }
                p.remove(OLD_APP)
                p.remove(OLD_PHONE)
                return p.toPreferences()
            }

            override suspend fun cleanUp() {
                settings.edit { it.remove(OLD_BASE) }
            }
        }
    }
}
