package io.github.jwyoon1220.dncity.network.kcp

import io.github.jwyoon1220.dncity.network.VoiceKcpHandshakePayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.PacketDistributor

/** Lifecycle wiring for [VoiceKcpServer] -- see that class's doc comment for the overall design. */
object VoiceKcpServerEvents {
    fun onServerStarting(event: ServerStartingEvent) {
        VoiceKcpServer.attachServer(event.server)
        VoiceKcpServer.start()
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        VoiceKcpServer.stop()
    }

    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val (conv, secret) = VoiceKcpServer.beginSession(player.uuid)
        PacketDistributor.sendToPlayer(player, VoiceKcpHandshakePayload(conv, secret, VoiceKcpServer.boundPort()))
    }

    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VoiceKcpServer.endSession(player.uuid)
    }
}
