package io.github.jwyoon1220.dncity.client.phone

import com.plasmoverse.opus.OpusEncoder
import io.github.jwyoon1220.dncity.network.PhoneCallAudioPayload
import io.github.jwyoon1220.dncity.voice.OpusCodec
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client-side TX pump for an active phone call, driven by
 * [io.github.jwyoon1220.dncity.voice.VoiceClientLoop] while [PhoneCallManager.state] is
 * [io.github.jwyoon1220.dncity.phone.PhoneCallState.ACTIVE]. Full-duplex like close-range voice
 * (no PTT, no VAD gating -- a call sends continuously, the way a real phone would), same
 * [OpusCodec] as close-range/AM/FM radio. Keeps its own [OpusEncoder] instance rather than
 * sharing close-range's (`VoiceClientLoop`'s `encoder` field) or radio's
 * ([io.github.jwyoon1220.dncity.voice.RadioTransmitter]'s) -- matching how each of those already
 * keeps its own; Opus encoder state is per logical channel, not something to share across
 * unrelated destinations even when they happen to carry the same physical mic audio.
 */
object PhoneCallTransmitter {
    private var encoder: OpusEncoder? = null

    fun submit(frame48k: ShortArray) {
        val enc = encoder ?: OpusCodec.createEncoder().also { encoder = it }
        PacketDistributor.sendToServer(PhoneCallAudioPayload(enc.encode(frame48k)))
    }

    /** Called on [io.github.jwyoon1220.dncity.voice.VoiceClientLoop.stop]. */
    fun shutdown() {
        encoder?.close()
        encoder = null
    }
}
