package io.github.jwyoon1220.dncity.security

import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Server-side bookkeeping for in-flight PGP login challenges -- one entry per connecting
 * [ServerConfigurationPacketListenerImpl] between [begin] (when
 * [PgpAuthServerEvents.onRegisterConfigurationTasks] issues the challenge) and either
 * [verifyAndComplete] succeeding or the connection dying before the client ever responds.
 *
 * A [WeakHashMap] (not a plain map cleared on disconnect) is deliberate: there's no NeoForge event
 * fired specifically for "a configuration listener disconnected without finishing," so relying on
 * the listener object itself becoming unreachable once its connection closes is the simplest way
 * to not leak an entry per failed/abandoned login attempt.
 */
object PgpAuthManager {
    private data class Pending(val playerId: UUID, val challenge: String)

    private val pending = Collections.synchronizedMap(WeakHashMap<ServerConfigurationPacketListenerImpl, Pending>())

    fun begin(listener: ServerConfigurationPacketListenerImpl, playerId: UUID, challenge: String) {
        pending[listener] = Pending(playerId, challenge)
    }

    fun verifyAndComplete(listener: ServerConfigurationPacketListenerImpl, signatureArmored: String): Boolean {
        val entry = pending[listener] ?: return false
        val publicKey = PgpKeyRegistry.getKey(entry.playerId) ?: return false
        val ok = PgpCrypto.verify(publicKey, entry.challenge.toByteArray(Charsets.UTF_8), signatureArmored)
        if (ok) pending.remove(listener)
        return ok
    }
}
