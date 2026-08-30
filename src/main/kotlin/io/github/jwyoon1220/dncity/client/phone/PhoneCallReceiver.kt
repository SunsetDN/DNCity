package io.github.jwyoon1220.dncity.client.phone

import com.plasmoverse.opus.OpusDecoder
import io.github.jwyoon1220.dncity.phone.PhoneCallState
import io.github.jwyoon1220.dncity.voice.OpusCodec
import io.github.jwyoon1220.dncity.voice.VoiceAudioMixer

/**
 * Client-side RX pump for an active phone call: one shared [OpusDecoder] (a player is only ever
 * in one call at a time -- see [PhoneCallManager]), fed into the same
 * [io.github.jwyoon1220.dncity.voice.VoiceAudioMixer] that close-range voice and radio already
 * drain from [io.github.jwyoon1220.dncity.voice.VoiceClientLoop], under a reserved source id that
 * can never collide with a real speaker.
 */
object PhoneCallReceiver {
    /** Entity ids are always >= 0 in vanilla, so this can never collide with a real speaker's id in [VoiceAudioMixer]. */
    private const val SOURCE_ID = -1000

    private var decoder: OpusDecoder? = null

    fun handleFrame(opusData: ByteArray) {
        if (PhoneCallManager.state != PhoneCallState.ACTIVE) return
        val dec = decoder ?: OpusCodec.createDecoder().also { decoder = it }
        val samples = runCatching { dec.decode(opusData) }.getOrNull() ?: return
        VoiceAudioMixer.submit(SOURCE_ID, samples, 1f)
    }

    /** Called when the call ends and on world logout. */
    fun reset() {
        decoder?.close()
        decoder = null
        VoiceAudioMixer.releaseSource(SOURCE_ID)
    }
}
