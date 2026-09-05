package io.github.jwyoon1220.dncity.security

import io.github.jwyoon1220.dncity.network.PgpChallengePayload
import net.minecraft.network.chat.Component
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.net.InetSocketAddress

/**
 * Gates every connection at the configuration phase -- before it ever reaches the play phase / the
 * actual world -- behind a PGP challenge-response (see [PgpAuthConfigurationTask]), unless the
 * whole gate is turned off via [PgpSettings] (`/pgp enable`/`/pgp disable`, off by default) or the
 * connection is [isExempt] (plain singleplayer, the host of a LAN-opened world, or -- only once an
 * operator opts in via [PgpSettings.exemptLoopback] -- any connection arriving from localhost --
 * the gate only matters once a world is actually reachable by someone else). A player with no
 * registered key ([PgpKeyRegistry]) is disconnected outright rather
 * than let through unauthenticated, and so is a client that doesn't even have this mod's channel
 * open (a vanilla client couldn't complete the handshake anyway, and this server requires the mod
 * for gameplay regardless) -- both kicks go through a registered [PgpKickConfigurationTask] rather
 * than calling `disconnect()` directly here; see that class's doc comment for why (a real crash
 * this caused, not just theoretical).
 */
object PgpAuthServerEvents {
    fun onRegisterConfigurationTasks(event: RegisterConfigurationTasksEvent) {
        if (!PgpSettings.enabled) return

        val listener = event.listener as? ServerConfigurationPacketListenerImpl ?: return
        if (isExempt(listener)) return

        if (!listener.hasChannel(PgpChallengePayload.TYPE)) {
            event.register(
                PgpKickConfigurationTask(
                    listener,
                    Component.literal("This server requires the DNCity mod (PGP login verification)."),
                ),
            )
            return
        }

        val playerId = listener.owner.id
        if (!PgpKeyRegistry.hasKey(playerId)) {
            event.register(
                PgpKickConfigurationTask(
                    listener,
                    Component.literal("No PGP key is registered for your account. Ask an operator to register one with /pgp register."),
                ),
            )
            return
        }

        val challenge = PgpChallengeGenerator.generate(playerId)
        PgpAuthManager.begin(listener, playerId, challenge)
        event.register(PgpAuthConfigurationTask(challenge))
    }

    /**
     * True when this connection shouldn't be gated at all, regardless of [PgpSettings.enabled]:
     * - Plain singleplayer (an integrated server not published to LAN) -- nobody else can reach it
     *   over the network anyway.
     * - The host's own connection on a LAN-opened integrated server (`MinecraftServer.isSingleplayerOwner`) --
     *   they already own the world; the gate exists for guests, not the owner.
     * - Any connection whose remote address is loopback (127.0.0.1/::1), but **only** when an
     *   operator has explicitly opted in via [PgpSettings.exemptLoopback] -- see that flag's doc
     *   comment for why this can't default to on.
     *
     * `isPublished()`/`isSingleplayerOwner()` are both declared directly on the dist-neutral
     * `MinecraftServer` base class (overridden by the client-only `IntegratedServer`, always
     * false/false on a `DedicatedServer`), so they're called straight on [server] here -- this
     * deliberately never references the `IntegratedServer` class itself: that class doesn't exist
     * on a real dedicated-server jar, and an `instanceof` check against it would throw
     * `NoClassDefFoundError` the moment any player connects, breaking the login gate entirely.
     */
    private fun isExempt(listener: ServerConfigurationPacketListenerImpl): Boolean {
        val server = ServerLifecycleHooks.getCurrentServer()
        if (server != null && server.javaClass.name == "net.minecraft.client.server.IntegratedServer") {
            if (!server.isPublished()) return true
            if (server.isSingleplayerOwner(listener.owner)) return true
        }

        if (PgpSettings.exemptLoopback) {
            val remoteAddress = listener.connection.remoteAddress
            if (remoteAddress is InetSocketAddress && remoteAddress.address.isLoopbackAddress) return true
        }

        return false
    }
}
