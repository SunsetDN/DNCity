package io.github.jwyoon1220.dncity.music

import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent

/**
 * Sends the server's configured soundfont ([MusicPaths.soundfontFile]) to a player as soon as
 * they log in, so it's already cached client-side (see
 * [io.github.jwyoon1220.dncity.music.MusicClientReceiver]) by the time any `/music play` command
 * reaches them -- avoids a request/response round-trip on every play, at the cost of sending the
 * (potentially large) soundfont to every joining player whether they ever hear music or not. A
 * missing soundfont file is silently skipped -- MIDI playback (and `/music play`) is simply
 * unavailable until an operator provides one, not a startup error.
 */
object MusicServerEvents {
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val soundfont = MusicPaths.soundfontFile
        if (soundfont.isFile) {
            MusicAssetSender.sendSoundfont(player, soundfont)
        }
    }
}
