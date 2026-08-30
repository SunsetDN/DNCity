package io.github.jwyoon1220.dncity.radio

import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent

/** Clears a disconnecting player's [RadioStationRegistry] membership -- otherwise a stale
 * broadcaster entry would linger on whatever station they'd joined until someone else's [join]
 * call happened to overwrite it (same shape as [io.github.jwyoon1220.dncity.phone.PhoneServerEvents]'s
 * logout cleanup). */
object RadioStationServerEvents {
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        RadioStationRegistry.onPlayerDisconnected(player.serverLevel(), player)
    }
}
