package io.github.jwyoon1220.dncity.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.github.jwyoon1220.dncity.music.MusicAssetSender
import io.github.jwyoon1220.dncity.music.MusicPaths
import io.github.jwyoon1220.dncity.network.MusicStopPayload
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.network.PacketDistributor
import java.io.File

/**
 * "/music" -- server-triggered playback via files the server sends the client (see
 * `io.github.jwyoon1220.dncity.music`), not vanilla note blocks and not a pushed resource pack.
 * `/music play <name>` looks for `<name>.<ext>` in `config/dncity/music/` across every supported
 * extension (`.mid`/`.midi` play through `io.github.jwyoon1220.dncity.music.MidiPlayer` --
 * Java Sound plus a server-provided soundfont; `.ogg`/`.flac`/`.mp3`/`.opus` play through
 * `io.github.jwyoon1220.dncity.music.AudioPlayer` -- FMOD's own native decoders). Broadcasts to
 * every online player; no per-player/group targeting or playlists.
 */
object MusicCommand {
    private val FILE_NOT_FOUND =
        SimpleCommandExceptionType(Component.literal("No such track in config/dncity/music/ (tried .mid/.midi/.ogg/.flac/.mp3/.opus)"))

    // Checked in this order for a given <name> -- MIDI first since it's this feature's original
    // format, then the direct-audio formats FMOD Core decodes natively.
    private val MIDI_EXTENSIONS = listOf("mid", "midi")
    private val AUDIO_EXTENSIONS = listOf("ogg", "flac", "mp3", "opus")

    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("music")
                .then(
                    Commands.literal("play")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    val name = StringArgumentType.getString(ctx, "name")
                                    val (file, isMidi) = findTrack(name) ?: throw FILE_NOT_FOUND.create()

                                    for (player in ctx.source.server.playerList.players) {
                                        if (isMidi) {
                                            MusicAssetSender.sendMidi(player, file)
                                        } else {
                                            MusicAssetSender.sendAudio(player, file)
                                        }
                                    }
                                    ctx.source.sendSuccess({ Component.literal("Playing $name") }, false)
                                    1
                                },
                        ),
                )
                .then(
                    Commands.literal("stop").executes { ctx ->
                        for (player in ctx.source.server.playerList.players) {
                            PacketDistributor.sendToPlayer(player, MusicStopPayload())
                        }
                        1
                    },
                ),
        )
    }

    /** Returns the first matching file for [name] plus whether it's a MIDI track, or null if
     * none of the supported extensions exist. */
    private fun findTrack(name: String): Pair<File, Boolean>? {
        for (ext in MIDI_EXTENSIONS) {
            val file = File(MusicPaths.musicDir, "$name.$ext")
            if (file.isFile) return file to true
        }
        for (ext in AUDIO_EXTENSIONS) {
            val file = File(MusicPaths.musicDir, "$name.$ext")
            if (file.isFile) return file to false
        }
        return null
    }
}
