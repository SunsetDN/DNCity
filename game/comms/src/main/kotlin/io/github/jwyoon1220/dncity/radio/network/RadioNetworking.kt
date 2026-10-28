package io.github.jwyoon1220.dncity.radio.network

import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioMode
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

object RadioNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        // "2": the radio_* payloads carry which hand the radio is in.
        val registrar = event.registrar("2")

        registrar.playToServer(RadioTunePayload.TYPE, RadioTunePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val mode = RadioMode.entries.firstOrNull { it.name.equals(payload.mode, ignoreCase = true) } ?: return@enqueueWork
                RadioActions.tune(context.player() as ServerPlayer, payload.slot, payload.frequencyKhz, mode, hand(payload.offHand))
            }
        })

        registrar.playToServer(RadioSetActivePayload.TYPE, RadioSetActivePayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                RadioActions.setActive(context.player() as ServerPlayer, payload.slot, hand(payload.offHand))
            }
        })

        registrar.playToServer(RadioSetPoweredPayload.TYPE, RadioSetPoweredPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                RadioActions.setPowered(context.player() as ServerPlayer, payload.powered, hand(payload.offHand))
            }
        })

        // Radio audio itself no longer rides this channel -- see
        // io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer/VoiceKcpClient.
    }

    private fun hand(offHand: Boolean) = if (offHand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
}
