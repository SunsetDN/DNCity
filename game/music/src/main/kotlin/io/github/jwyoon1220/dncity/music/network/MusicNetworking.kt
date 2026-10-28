package io.github.jwyoon1220.dncity.music.network

import io.github.jwyoon1220.dncity.music.AudioPlayer
import io.github.jwyoon1220.dncity.music.MidiPlayer
import io.github.jwyoon1220.dncity.music.MusicAssetSender
import io.github.jwyoon1220.dncity.music.MusicClientReceiver
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler

object MusicNetworking {

    fun onRegisterPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        // "2": chunks are acknowledged (MusicAssetAckPayload) and may also arrive over the UDP session
        val registrar = event.registrar("2")

        registrar.playToClient(MusicAssetChunkPayload.TYPE, MusicAssetChunkPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                MusicClientReceiver.handleChunk(payload)
            }
        })

        registrar.playToServer(MusicAssetAckPayload.TYPE, MusicAssetAckPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                MusicAssetSender.onAck(player, payload.transferId, payload.chunkIndex)
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
