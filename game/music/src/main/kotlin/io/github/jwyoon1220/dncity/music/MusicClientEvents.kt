// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.music

import io.github.jwyoon1220.dncity.music.network.MusicAssetChunkPayload
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpClient
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup of [DncityMusic] (see [io.github.jwyoon1220.dncity.Dncity] for why it is a class of its own). */
@Suppress("removal")
@EventBusSubscriber(modid = DncityMusic.ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object MusicClientEvents {
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        // File chunks that came over the UDP session instead of the game connection
        VoiceKcpClient.registerHandler(VoiceKcpProtocol.TYPE_ASSET_CHUNK) { msg ->
            val chunk = VoiceKcpProtocol.decodeAssetChunk(msg)
            MusicClientReceiver.handleChunk(
                MusicAssetChunkPayload(chunk.kind, chunk.transferId, chunk.chunkIndex, chunk.totalChunks, chunk.extension, chunk.data),
            )
        }
        NeoForge.EVENT_BUS.addListener(::onLogout)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
    }

    private fun onLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        MidiPlayer.stop()
        AudioPlayer.shutdown()
        MusicClientReceiver.reset()
    }

    private fun onClientTick(event: ClientTickEvent.Post) = AudioPlayer.tick()
}
