package io.github.jwyoon1220.dncity.music

import io.github.jwyoon1220.dncity.Dncity
import org.apache.logging.log4j.Level
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequencer
import javax.sound.midi.Synthesizer

/**
 * Client-side MIDI playback: renders a `.mid` sequence through Java Sound's built-in software
 * synthesizer (Gervill, `javax.sound.midi`'s default [Synthesizer]) loaded with a soundfont the
 * server sent (see [MusicClientReceiver]) -- not vanilla note-block sounds and not a pushed
 * resource pack (contrast with e.g. OpenMCMelody's approach).
 *
 * Deliberately plays through Java Sound's own default output device (`synthesizer.open()`,
 * standard/public API) rather than trying to render offline and mix the PCM into this mod's own
 * `NativeAudio`/`VoiceAudioMixer` pipeline -- the class that would enable offline rendering,
 * `AudioSynthesizer`, lives in the non-public `com.sun.media.sound` package (not exported by the
 * `java.desktop` module without extra JVM flags), so it isn't a stable public API to build on.
 * **Known consequence**: MIDI music plays on a separate OS audio stream from the mod's own
 * voice/radio audio, not mixed/balanced together -- acceptable for a first pass, but worth
 * revisiting if that separation turns out to matter in practice.
 */
object MidiPlayer {
    private var sequencer: Sequencer? = null
    private var synthesizer: Synthesizer? = null

    /** Set by [MusicClientReceiver] once a soundfont transfer completes. */
    var soundbankFile: File? = null

    fun play(midiBytes: ByteArray) {
        stop()

        val soundbankPath = soundbankFile
        if (soundbankPath == null) {
            Dncity.LOGGER.log(Level.WARN, "[Music] No soundfont received yet, can't play MIDI")
            return
        }

        try {
            val sequence = MidiSystem.getSequence(ByteArrayInputStream(midiBytes))

            val synth = MidiSystem.getSynthesizer()
            synth.open()
            val soundbank = MidiSystem.getSoundbank(soundbankPath)
            if (synth.isSoundbankSupported(soundbank)) {
                synth.loadAllInstruments(soundbank)
            } else {
                Dncity.LOGGER.log(Level.WARN, "[Music] Synthesizer doesn't support the received soundfont, using its default instruments instead")
            }

            val seq = MidiSystem.getSequencer(false) // unconnected -- wired to our own synth below
            seq.open()
            seq.sequence = sequence
            seq.transmitter.receiver = synth.receiver
            seq.start()

            sequencer = seq
            synthesizer = synth
        } catch (e: Exception) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Playback failed", e)
            stop()
        }
    }

    /** Stops the currently playing track, if any. Safe to call when nothing is playing. */
    fun stop() {
        sequencer?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        synthesizer?.let {
            runCatching { it.close() }
        }
        sequencer = null
        synthesizer = null
    }
}
