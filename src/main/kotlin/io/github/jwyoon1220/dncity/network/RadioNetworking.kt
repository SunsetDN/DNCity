package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioMode
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

object RadioNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")

        registrar.playToServer(RadioTunePayload.TYPE, RadioTunePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val mode = RadioMode.entries.firstOrNull { it.name.equals(payload.mode, ignoreCase = true) } ?: return@enqueueWork
                RadioActions.tune(context.player() as net.minecraft.server.level.ServerPlayer, payload.slot, payload.frequencyKhz, mode)
            }
        })

        registrar.playToServer(RadioSetActivePayload.TYPE, RadioSetActivePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                RadioActions.setActive(context.player() as net.minecraft.server.level.ServerPlayer, payload.slot)
            }
        })

        registrar.playToServer(RadioSetPoweredPayload.TYPE, RadioSetPoweredPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                RadioActions.setPowered(context.player() as net.minecraft.server.level.ServerPlayer, payload.powered)
            }
        })

        // Radio audio itself no longer rides this channel -- see
        // io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer/VoiceKcpClient.
    }
}
