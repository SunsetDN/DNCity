package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.voice.ClientVoiceReceiver
import io.github.jwyoon1220.dncity.voice.VoiceRelay
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

object VoiceNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")

        registrar.playToServer(VoiceAudioPayload.TYPE, VoiceAudioPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                VoiceRelay.relay(context.player() as ServerPlayer, payload.opusData)
            }
        })

        registrar.playToClient(VoiceAudioRelayPayload.TYPE, VoiceAudioRelayPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                ClientVoiceReceiver.handleRelayedFrame(payload.senderEntityId, payload.opusData)
            }
        })
    }
}
