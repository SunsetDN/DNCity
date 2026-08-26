package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.network.RadioAudioRelayPayload
import io.github.jwyoon1220.dncity.network.RadioSenderPosition
import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioBand
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.abs

/**
 * Server-side fan-out for radio-voice frames: authoritative on both ends -- who's transmitting
 * (some hotbar radio of the sender's that's powered with an enabled active slot -- see
 * [RadioActions.transmittableHotbarRadio], carrying a radio in the hotbar is enough, it doesn't
 * need to be held) and who's listening (every other player carrying a powered hotbar radio with
 * some slot tuned within the transmit band's tolerance, and within the band's range). Mirrors
 * [VoiceRelay]'s "server decides who's in range, client decides how it sounds" split.
 */
object RadioRelay {
    fun relay(sender: ServerPlayer, audioData: ByteArray) {
        val (radio, stack) = RadioActions.transmittableHotbarRadio(sender) ?: return
        val data = radio.dataOf(stack)
        val txSlot = data.slots[data.activeSlot]
        val band = RadioBand.fromFrequencyKhz(txSlot.frequencyKhz) ?: return

        val senderPos = sender.position()
        val payload = RadioAudioRelayPayload(
            sender.id, audioData, txSlot.frequencyKhz, txSlot.mode.name,
            RadioSenderPosition(senderPos.x, senderPos.y, senderPos.z),
        )

        for (listener in sender.serverLevel().players()) {
            if (listener === sender) continue

            val tunedIn = RadioActions.hotbarRadios(listener).any { (rxRadio, rxStack) ->
                val rxData = rxRadio.dataOf(rxStack)
                rxData.powered && rxData.slots.any {
                    it.enabled && abs(it.frequencyKhz - txSlot.frequencyKhz) <= band.tuningToleranceKhz
                }
            }
            if (!tunedIn) continue

            val maxRange = band.maxRangeBlocks
            if (maxRange != null && listener.position().distanceTo(senderPos) > maxRange) continue

            PacketDistributor.sendToPlayer(listener, payload)
        }
    }
}
