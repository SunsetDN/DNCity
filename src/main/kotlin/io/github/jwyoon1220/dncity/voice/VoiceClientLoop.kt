package io.github.jwyoon1220.dncity.voice

import com.plasmoverse.opus.OpusEncoder
import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.audio.NativeAudio
import io.github.jwyoon1220.dncity.client.ModKeyMappings
import io.github.jwyoon1220.dncity.client.phone.PhoneCallManager
import io.github.jwyoon1220.dncity.client.phone.PhoneCallTransmitter
import io.github.jwyoon1220.dncity.network.VoiceAudioPayload
import io.github.jwyoon1220.dncity.phone.PhoneCallState
import net.neoforged.neoforge.network.PacketDistributor
import org.apache.logging.log4j.Level

/**
 * Client-side pump for the close-range voice tier: drains mic capture into
 * [OpusCodec.FRAME_SIZE]-sample frames, encodes and sends the ones that clear
 * [NativeAudio]'s native VAD (no PTT for this tier -- see the design plan's tier table, and
 * engine/audio's noise gate), and drains [VoiceAudioMixer]'s output back out to the speakers.
 * [start]/[stop] follow the client's join/leave-world lifecycle; [tick] runs once per client
 * tick (see `Dncity.onClientSetup`'s event registration).
 */
object VoiceClientLoop {
    private var running = false
    private var encoder: OpusEncoder? = null
    private val captureFrame = ShortArray(OpusCodec.FRAME_SIZE)
    private var captureFill = 0
    // Scratch space for NativeAudio.readCapture -- reused rather than allocated per pumpCapture()
    // call, since that runs every client tick (20/s) while voice is active.
    private val readBuffer = ShortArray(OpusCodec.FRAME_SIZE)
    private var pttWasDown = false

    val isRunning: Boolean get() = running

    /** Client-controlled mute -- captured frames still run through the gate/VAD, just never sent. */
    var muted: Boolean = false

    /**
     * The native noise gate's open threshold in dBFS -- see [NativeAudio.setNoiseGateThresholdDb].
     * Applying the setter pushes the new value to the native side immediately if capture is
     * already running, so a settings screen can adjust it live.
     */
    var noiseGateThresholdDb: Float = DEFAULT_NOISE_GATE_DB
        set(value) {
            field = value
            if (running) NativeAudio.setNoiseGateThresholdDb(value)
        }

    /**
     * Switches the capture (microphone) device by index into [NativeAudio.listCaptureDevices],
     * or -1 for the OS default. Restarts capture immediately if voice is already running, so a
     * settings screen change takes effect live rather than needing a rejoin.
     */
    fun selectCaptureDevice(index: Int) {
        NativeAudio.selectCaptureDevice(index)
        if (running) {
            NativeAudio.stopCapture()
            NativeAudio.startCapture()
            NativeAudio.setNoiseGateThresholdDb(noiseGateThresholdDb)
        }
    }

    /** Switches the playback (speaker) device -- see [selectCaptureDevice]. */
    fun selectPlaybackDevice(index: Int) {
        NativeAudio.selectPlaybackDevice(index)
        if (running) {
            NativeAudio.stopPlayback()
            NativeAudio.startPlayback()
        }
    }

    fun start() {
        if (running) return
        NativeAudio.init()
        if (!NativeAudio.startCapture() || !NativeAudio.startPlayback()) {
            Dncity.LOGGER.log(Level.WARN, "Close-range voice: failed to open capture/playback device, voice disabled this session")
            return
        }
        NativeAudio.setNoiseGateThresholdDb(noiseGateThresholdDb)
        encoder = OpusCodec.createEncoder()
        captureFill = 0
        pttWasDown = false
        running = true
    }

    fun stop() {
        if (!running) return
        NativeAudio.stopCapture()
        NativeAudio.stopPlayback()
        encoder?.close()
        encoder = null
        RadioTransmitter.shutdown()
        PhoneCallTransmitter.shutdown()
        ClientVoiceReceiver.releaseAll()
        ClientRadioReceiver.releaseAll()
        captureFill = 0
        running = false
    }

    fun tick() {
        if (!running) return
        pumpCapture()
        pumpPlayback()
    }

    private fun pumpCapture() {
        while (true) {
            val read = NativeAudio.readCapture(readBuffer)
            if (read <= 0) break

            var offset = 0
            while (offset < read) {
                val toCopy = minOf(captureFrame.size - captureFill, read - offset)
                System.arraycopy(readBuffer, offset, captureFrame, captureFill, toCopy)
                captureFill += toCopy
                offset += toCopy
                if (captureFill == captureFrame.size) {
                    flushCaptureFrame()
                }
            }
        }
    }

    /**
     * Three destinations, in priority order, mutually exclusive per frame:
     * 1. Radio is PTT-required and half-duplex with everything else (see the design plan's tier
     *    table): while [ModKeyMappings.RADIO_PTT] is held, captured frames go out on the radio
     *    channel (via [RadioTransmitter], which handles codec2's mode-dependent frame sizing).
     * 2. An active phone call ([PhoneCallManager.state]) is full-duplex like close-range and
     *    sends continuously while connected, the way a real phone would -- no VAD gating (unlike
     *    close-range below), since a live call shouldn't clip the start of speech waiting for the
     *    gate to open.
     * 3. Close-range voice, gated by the native noise gate/VAD in place of PTT.
     */
    private fun flushCaptureFrame() {
        val pttDown = ModKeyMappings.RADIO_PTT.isDown
        if (pttWasDown && !pttDown) {
            RadioTransmitter.reset()
        }
        pttWasDown = pttDown

        if (!muted && pttDown) {
            RadioTransmitter.submit(captureFrame)
        } else if (!muted && PhoneCallManager.state == PhoneCallState.ACTIVE) {
            PhoneCallTransmitter.submit(captureFrame)
        } else if (!muted && NativeAudio.isVoiceActive()) {
            sendCloseRangeFrame()
        }
        captureFill = 0
    }

    private fun sendCloseRangeFrame() {
        val enc = encoder ?: return
        // Auto-VAD in place of PTT: only spend bandwidth on frames the native noise gate
        // considers actual speech, not the constant background noise floor.
        val encoded = enc.encode(captureFrame)
        PacketDistributor.sendToServer(VoiceAudioPayload(encoded))
    }

    private fun pumpPlayback() {
        // Drains every queued mixed frame, not just one: the 50ms client tick is more than
        // double a 20ms voice frame, so writing only one frame per tick chronically underfeeds
        // NativeAudio's playback ring buffer (~60% silence) and sounds like a rapid on/off chop.
        while (true) {
            var remaining = VoiceAudioMixer.drainFrame() ?: break
            while (remaining.isNotEmpty()) {
                val written = NativeAudio.writePlayback(remaining, remaining.size)
                if (written <= 0) break
                if (written >= remaining.size) break
                remaining = remaining.copyOfRange(written, remaining.size)
            }
        }
    }

    private const val DEFAULT_NOISE_GATE_DB = -50f
}
