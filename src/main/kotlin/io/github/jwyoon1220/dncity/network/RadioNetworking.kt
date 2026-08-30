package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioMode
import io.github.jwyoon1220.dncity.voice.ClientRadioReceiver
import io.github.jwyoon1220.dncity.voice.RadioRelay
import net.minecraft.server.level.ServerPlayer
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

        registrar.playToServer(RadioAudioPayload.TYPE, RadioAudioPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                RadioRelay.relay(context.player() as ServerPlayer, payload.audioData)
            }
        })

        registrar.playToClient(RadioAudioRelayPayload.TYPE, RadioAudioRelayPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                ClientRadioReceiver.handleRelayedFrame(
                    payload.senderEntityId, payload.audioData, payload.frequencyKhz, payload.mode,
                    payload.senderPosition, payload.effectiveMaxRangeBlocks, payload.obstructed, payload.stormNoise,
                )
            }
        })
    }
}
