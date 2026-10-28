// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.music

import io.github.jwyoon1220.dncity.music.command.MusicCommand
import io.github.jwyoon1220.dncity.music.network.MusicNetworking
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * `dncity_music`: `/music` -- server-sent MIDI (with the server's soundfont) and audio files, played on every client.
 * Files travel over DNCity core's UDP transport when the player has a session ([MusicAssetSender]), the game
 * connection otherwise. Client-only setup is in [MusicClientEvents].
 */
@Mod(DncityMusic.ID)
object DncityMusic {
    const val ID = "dncity_music"

    init {
        NeoForge.EVENT_BUS.addListener(MusicCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(MusicServerEvents::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(MusicAssetSender::onServerTick)
        NeoForge.EVENT_BUS.addListener(MusicAssetSender::onPlayerLoggedOut)
        MOD_BUS.addListener(MusicNetworking::onRegisterPayloadHandlers)
    }
}
