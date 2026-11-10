// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import dev.zeffin.core.Addresses
import dev.zeffin.core.PairingRecord
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What saving a pairing touches; AppGraph wires the link, the relay and the pairing store. */
interface PairingPorts {
    /** Stops /app and waits for its loop to end: no request goes out with the old token after this. */
    suspend fun stopLink()
    /** Stops the relay and waits for its engine to end; true when it was running. */
    suspend fun stopRelay(): Boolean
    /** Writes the whole pairing in one step. */
    suspend fun write(record: PairingRecord)
    /** Opens /app again with what is saved now (only while the app is in view). */
    suspend fun startLink()
    fun startRelay()
}

/**
 * Saves a new pairing with nothing connected meanwhile: the link and the relay stop and are waited for, the record is
 * written in one step, then they start again (the relay only if it was running). One save at a time.
 */
class PairingSaver(private val ports: PairingPorts) {
    private val lock = Mutex()

    suspend fun save(record: PairingRecord) = lock.withLock {
        // Once started, a save finishes even if the screen that asked for it goes away: never a half-stopped app.
        withContext(NonCancellable) {
            ports.stopLink()
            val relayWasRunning = ports.stopRelay()
            try {
                ports.write(record)
            } finally {
                ports.startLink()
                if (relayWasRunning) ports.startRelay()
            }
        }
    }
}

/** /app's address and App token, both from one pairing record: never one server's address with another's token. */
fun appTarget(p: PairingRecord?): Pair<String?, String?> = p?.base?.let(Addresses::appUrl) to p?.appToken
