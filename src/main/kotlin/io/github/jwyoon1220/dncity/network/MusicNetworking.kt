package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.music.AudioPlayer
import io.github.jwyoon1220.dncity.music.MidiPlayer
import io.github.jwyoon1220.dncity.music.MusicClientReceiver
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

object MusicNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")

        registrar.playToClient(MusicAssetChunkPayload.TYPE, MusicAssetChunkPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                MusicClientReceiver.handleChunk(payload)
            }
        })

        registrar.playToClient(MusicStopPayload.TYPE, MusicStopPayload.STREAM_CODEC, IPayloadHandler { _, context ->
            context.enqueueWork {
                MidiPlayer.stop()
                AudioPlayer.stop()
            }
        })
    }
}
