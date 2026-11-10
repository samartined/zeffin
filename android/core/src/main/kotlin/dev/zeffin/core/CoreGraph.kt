// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** One instance per process of each store: DataStore must not open the same file twice. */
class CoreGraph private constructor(context: Context) {
    private val settingsData = PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })
    val settings = SettingsStore(settingsData)
    val pairing: PairingStore = KeystoreCipher().let { cipher ->
        PairingStore(PreferenceDataStoreFactory.create(migrations = listOf(PairingStore.migration(settingsData, cipher)),
            produceFile = { context.preferencesDataStoreFile("secrets") }), cipher)
    }
    /** The private network the server is reached through, and the one place that builds requests to the server. */
    val network: PrivateNetwork = TailscaleNetwork(context)
    /**
     * The saved pairing's key, only for that pairing's own server. Read from the store at each request, never from a
     * copy that could be one write behind a new pairing (the record is decrypted once per write).
     */
    val http = ServerHttp(network, { url -> runBlocking(Dispatchers.IO) { pairing.current() }?.tokensFor(url)?.key }, File(context.cacheDir, "http"))

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { network.changes.collect { http.dropConnections() } }
        // A new pairing (another key, another server): its sockets start afresh.
        scope.launch { pairing.pairing.map { it?.base to it?.key }.distinctUntilChanged().collect { http.dropConnections() } }
    }

    companion object {
        /**
         * The private network this build uses, chosen here only (here and in [network]): its pure side, for
         * Addresses.parseBase and the words. Another private network is another PrivateNetwork, chosen here.
         */
        val privateAddresses: PrivateAddresses = TailscaleNetwork

        @Volatile private var instance: CoreGraph? = null

        fun get(context: Context): CoreGraph = instance ?: synchronized(this) {
            instance ?: CoreGraph(context.applicationContext).also { instance = it }
        }
    }
}
