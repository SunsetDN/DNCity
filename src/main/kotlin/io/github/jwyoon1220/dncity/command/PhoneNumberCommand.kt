package io.github.jwyoon1220.dncity.command

import com.mojang.brigadier.CommandDispatcher
import io.github.jwyoon1220.dncity.phone.PhoneDirectory
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * "/phone number" -- reports this player's own [PhoneDirectory] number (a chat-based fallback for
 * the dialer UI's own display of it -- see `client.phone.PhoneCallManager.myNumber`). Numbers are
 * never player-chosen: [PhoneDirectory.numberOf] derives them deterministically from the player's
 * UUID, auto-assigned on login (`phone.PhoneServerEvents`), so there's no "set"/"clear" subcommand
 * here anymore. Registered here (common `init {}` in `Dncity`, same as [RadioCommand]/
 * [MusicCommand]) rather than alongside `PhoneCommand`'s "open"/"close" -- this only touches
 * [PhoneDirectory] (a plain in-memory server-side map), unlike those two, which touch client-only
 * screen APIs and so must never run on a dedicated server. Brigadier merges both registrations
 * under the same "phone" root literal, so `/phone open`, `/phone number`, etc. all coexist normally.
 */
object PhoneNumberCommand {
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("phone")
                .then(
                    Commands.literal("number").executes { ctx ->
                        val number = PhoneDirectory.numberOf(ctx.source.playerOrException.uuid)
                        ctx.source.sendSuccess({ Component.literal("Your phone number is $number") }, false)
                        1
                    },
                ),
        )
    }
}
