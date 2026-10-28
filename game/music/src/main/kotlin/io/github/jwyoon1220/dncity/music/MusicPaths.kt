package io.github.jwyoon1220.dncity.music

import net.neoforged.fml.loading.FMLPaths
import java.io.File

/**
 * Where the server-side music assets live: `<config>/dncity/soundfont.sf2` (a single soundfont,
 * server-provided -- not bundled with the mod, since soundfonts are large and often
 * licensing-restricted) and `<config>/dncity/music/` (MIDI files an operator drops in, played by
 * name via `/music play <name>`, e.g. `<name>.mid`).
 */
object MusicPaths {
    private val configDir: File by lazy { FMLPaths.CONFIGDIR.get().resolve("dncity").toFile() }

    val soundfontFile: File get() = File(configDir, "soundfont.sf2")
    val musicDir: File get() = File(configDir, "music")
}
