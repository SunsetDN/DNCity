package io.github.jwyoon1220.dncity.phone

import io.github.jwyoon1220.dncity.network.PhoneNumberAssignedPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Assigns/looks up a player's [PhoneDirectory] number as soon as they log in and pushes it to
 * their client (see [io.github.jwyoon1220.dncity.client.phone.PhoneCallManager.myNumber]), so the
 * dialer UI can display it without a round-trip. Also ends whatever call [PhoneCallSession] has a
 * disconnecting player in (ringing or active), so the other participant gets an
 * [PhoneCallState.ENDED] push instead of a call stuck open forever. [PhoneDirectory] registrations
 * are otherwise untouched by logout -- a player's number is derived from their UUID, so it's
 * already stable across sessions without needing to be cleared/reissued.
 */
object PhoneServerEvents {
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val number = PhoneDirectory.numberOf(player.uuid)
        PacketDistributor.sendToPlayer(player, PhoneNumberAssignedPayload(number))
    }

    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        PhoneCallSession.onDisconnect(player.serverLevel().server, player)
    }
}
