package io.github.jwyoon1220.dncity.music

import com.iwei20.fmod.FModLoad
import com.iwei20.fmod.studio.FMODCoreSystem
import io.github.jwyoon1220.dncity.Dncity
import org.apache.logging.log4j.Level
import java.io.File

/**
 * Client-side playback for direct audio-file tracks (OGG/FLAC/MP3/Opus) received from the server
 * (see [MusicClientReceiver]) -- decoded and played via FMOD's own Core `System`
 * ([FMODCoreSystem]), not [MidiPlayer]'s `javax.sound.midi` path, since these formats need a
 * decoder rather than a synthesizer and FMOD already ships one for all four.
 *
 * A second, independent FMOD Core system from the one TACZ's Studio system owns internally --
 * see [FMODCoreSystem]'s class doc for why. Streamed from disk
 * ([FMODCoreSystem.MODE_CREATESTREAM]), not decoded fully into memory up front, since tracks can
 * run several minutes.
 */
object AudioPlayer {
    private var coreSystem: FMODCoreSystem? = null
    private var currentSound: FMODCoreSystem.Sound? = null
    private var currentChannel: FMODCoreSystem.Channel? = null
    private var initFailed = false

    private fun ensureSystem(): FMODCoreSystem? {
        coreSystem?.let { return it }
        if (initFailed) return null
        return try {
            FModLoad.load()
            val system = FMODCoreSystem()
            system.init()
            coreSystem = system
            system
        } catch (e: Throwable) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Failed to initialize FMOD Core system for audio playback", e)
            initFailed = true
            null
        }
    }

    fun play(file: File) {
        stop()
        val system = ensureSystem() ?: return
        try {
            val sound = system.createSound(file.absolutePath, FMODCoreSystem.MODE_CREATESTREAM)
            currentSound = sound
            currentChannel = system.play(sound)
        } catch (e: Exception) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Failed to play {}", file, e)
            stop()
        }
    }

    /** Stops the currently playing track, if any. Safe to call when nothing is playing. */
    fun stop() {
        currentChannel?.let { channel -> runCatching { channel.stop() } }
        currentSound?.let { sound -> runCatching { sound.close() } }
        currentChannel = null
        currentSound = null
    }

    /** Must be called regularly (once per client tick, see Dncity.kt) for streamed playback to
     * actually progress -- mirrors FMOD Studio's own per-tick `System.update()`. */
    fun tick() {
        coreSystem?.let { system -> runCatching { system.update() } }
    }

    /** Called on leaving a world/disconnecting -- releases the FMOD Core system entirely, not
     * just the current track, so a later `play()` starts from a clean init. */
    fun shutdown() {
        stop()
        coreSystem?.let { system -> runCatching { system.close() } }
        coreSystem = null
        initFailed = false
    }
}
