package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.network.VoiceAudioRelayPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Server-side fan-out for close-range voice frames: who's in range is decided here, once, per
 * frame; the client only needs to turn "in range" into a volume (see [CloseRangeVoice.gain]).
 */
object VoiceRelay {
    fun relay(sender: ServerPlayer, opusData: ByteArray) {
        val payload = VoiceAudioRelayPayload(sender.id, opusData)
        val senderPos = sender.position()
        // .players() is scoped to this ServerLevel already, so cross-dimension listeners are
        // never considered -- no separate dimension check needed.
        for (listener in sender.serverLevel().players()) {
            if (listener === sender) continue
            val distance = listener.position().distanceTo(senderPos)
            if (CloseRangeVoice.isInRange(distance)) {
                PacketDistributor.sendToPlayer(listener, payload)
            }
        }
    }
}
