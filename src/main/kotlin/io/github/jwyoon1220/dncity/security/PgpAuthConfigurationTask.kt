package io.github.jwyoon1220.dncity.security

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.network.PgpChallengePayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.network.ConfigurationTask
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import java.util.function.Consumer

/**
 * Sends [PgpChallengePayload] and blocks the connection in the configuration phase (before it
 * ever reaches the play phase) until the client's signed response
 * ([io.github.jwyoon1220.dncity.network.PgpNetworking]'s `configurationToServer` handler) calls
 * `finishCurrentTask(TYPE)`. Registered per-connection by [PgpAuthServerEvents].
 */
class PgpAuthConfigurationTask(private val challenge: String) : ICustomConfigurationTask {
    override fun run(sender: Consumer<CustomPacketPayload>) {
        sender.accept(PgpChallengePayload(challenge))
    }

    override fun type(): ConfigurationTask.Type = TYPE

    companion object {
        val TYPE: ConfigurationTask.Type = ConfigurationTask.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "pgp_auth"))
    }
}
