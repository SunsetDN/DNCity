package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.client.security.ClientPgpAuthReceiver
import io.github.jwyoon1220.dncity.security.PgpAuthConfigurationTask
import io.github.jwyoon1220.dncity.security.PgpAuthManager
import net.minecraft.network.chat.Component
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

/** Registers the PGP login-gate's two configuration-phase payloads -- see
 * [io.github.jwyoon1220.dncity.security.PgpAuthServerEvents]/[PgpAuthConfigurationTask] for how
 * the handshake is driven. */
object PgpNetworking {
    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")

        registrar.configurationToClient(PgpChallengePayload.TYPE, PgpChallengePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                ClientPgpAuthReceiver.handleChallenge(payload, context)
            }
        })

        registrar.configurationToServer(PgpResponsePayload.TYPE, PgpResponsePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val listener = context.listener() as? ServerConfigurationPacketListenerImpl ?: return@enqueueWork
                if (PgpAuthManager.verifyAndComplete(listener, payload.signatureArmored)) {
                    context.finishCurrentTask(PgpAuthConfigurationTask.TYPE)
                } else {
                    context.disconnect(Component.literal("PGP signature verification failed."))
                }
            }
        })
    }
}
