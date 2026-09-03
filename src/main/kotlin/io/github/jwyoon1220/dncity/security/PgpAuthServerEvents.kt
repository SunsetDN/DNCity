package io.github.jwyoon1220.dncity.security

import io.github.jwyoon1220.dncity.network.PgpChallengePayload
import net.minecraft.network.chat.Component
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent

/**
 * Gates every connection at the configuration phase -- before it ever reaches the play phase / the
 * actual world -- behind a PGP challenge-response (see [PgpAuthConfigurationTask]), unless the
 * whole gate is turned off via [PgpSettings] (`/pgp disable`). A player with no registered key
 * ([PgpKeyRegistry]) is disconnected outright rather than let through unauthenticated, and so is a
 * client that doesn't even have this mod's channel open (a vanilla client couldn't complete the
 * handshake anyway, and this server requires the mod for gameplay regardless).
 */
object PgpAuthServerEvents {
    fun onRegisterConfigurationTasks(event: RegisterConfigurationTasksEvent) {
        if (!PgpSettings.enabled) return

        val listener = event.listener as? ServerConfigurationPacketListenerImpl ?: return

        if (!listener.hasChannel(PgpChallengePayload.TYPE)) {
            listener.disconnect(Component.literal("This server requires the DNCity mod (PGP login verification)."))
            return
        }

        val playerId = listener.owner.id
        if (!PgpKeyRegistry.hasKey(playerId)) {
            listener.disconnect(
                Component.literal("No PGP key is registered for your account. Ask an operator to register one with /pgp register."),
            )
            return
        }

        val challenge = PgpChallengeGenerator.generate(playerId)
        PgpAuthManager.begin(listener, playerId, challenge)
        event.register(PgpAuthConfigurationTask(challenge))
    }
}
