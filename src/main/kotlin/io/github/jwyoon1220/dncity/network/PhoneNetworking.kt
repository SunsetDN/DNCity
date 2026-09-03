package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.client.phone.PhoneCallManager
import io.github.jwyoon1220.dncity.phone.PhoneCallSession
import io.github.jwyoon1220.dncity.phone.PhoneCallState
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

object PhoneNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")

        registrar.playToServer(PhoneCallRequestPayload.TYPE, PhoneCallRequestPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val player = context.player() as ServerPlayer
                PhoneCallSession.requestCall(player.serverLevel().server, player, payload.number)
            }
        })

        registrar.playToServer(PhoneCallAcceptPayload.TYPE, PhoneCallAcceptPayload.STREAM_CODEC, IPayloadHandler { _, context ->
            context.enqueueWork {
                val player = context.player() as ServerPlayer
                PhoneCallSession.accept(player.serverLevel().server, player)
            }
        })

        registrar.playToServer(PhoneCallDeclinePayload.TYPE, PhoneCallDeclinePayload.STREAM_CODEC, IPayloadHandler { _, context ->
            context.enqueueWork {
                val player = context.player() as ServerPlayer
                PhoneCallSession.decline(player.serverLevel().server, player)
            }
        })

        registrar.playToServer(PhoneCallHangupPayload.TYPE, PhoneCallHangupPayload.STREAM_CODEC, IPayloadHandler { _, context ->
            context.enqueueWork {
                val player = context.player() as ServerPlayer
                PhoneCallSession.hangup(player.serverLevel().server, player)
            }
        })

        registrar.playToClient(PhoneNumberAssignedPayload.TYPE, PhoneNumberAssignedPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                PhoneCallManager.setMyNumber(payload.number)
            }
        })

        registrar.playToClient(PhoneCallStatePayload.TYPE, PhoneCallStatePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val state = PhoneCallState.entries.getOrNull(payload.state) ?: return@enqueueWork
                PhoneCallManager.handleServerState(state, payload.peerName)
            }
        })

        // Call audio itself no longer rides this channel -- see
        // io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer/VoiceKcpClient.
    }
}
