package io.github.jwyoon1220.dncity.command

import com.mojang.brigadier.CommandDispatcher
import io.github.jwyoon1220.dncity.client.phone.PhoneController
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * "/phone open" / "/phone close" -- opens/closes [PhoneController]'s ModernUI screen. Client-only:
 * registered from `Dncity.onClientSetup`, not the shared `init {}` block other commands use, same
 * reason as [WindowCommand] (the body touches client-only Minecraft/screen APIs that must never
 * run on a dedicated server). Every handler hops onto the render thread before touching
 * [PhoneController], same as [WindowCommand]'s handlers.
 */
object PhoneCommand {
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("phone")
                .then(Commands.literal("open").executes { openPhone(); 1 })
                .then(Commands.literal("close").executes { closePhone(); 1 }),
        )
    }

    private fun openPhone() {
        Minecraft.getInstance().execute { PhoneController.open() }
    }

    private fun closePhone() {
        Minecraft.getInstance().execute { PhoneController.close() }
    }
}
