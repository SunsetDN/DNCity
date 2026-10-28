package io.github.jwyoon1220.dncity.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioMode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * "/radio" -- a chat-based fallback for tuning/power (see also RadioItem's right click, which
 * opens the graphical RadioScreen and drives the same [RadioActions]).
 */
object RadioCommand {

    private val ACTION_FAILED = SimpleCommandExceptionType(Component.literal("You need to hold a radio in your main hand (with a valid slot/frequency)"))
    private val UNKNOWN_MODE = SimpleCommandExceptionType(Component.literal("Unknown mode. Use AM, FM, or USB"))

    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("radio")
                .then(
                    Commands.literal("tune")
                        .then(
                            Commands.argument("slot", IntegerArgumentType.integer(1, 5))
                                .then(
                                    Commands.argument("frequency_khz", DoubleArgumentType.doubleArg(0.003, 3_000_000_000.0))
                                        .then(
                                            Commands.argument("mode", StringArgumentType.word())
                                                .executes { ctx ->
                                                    val mode = RadioMode.entries.firstOrNull {
                                                        it.name.equals(StringArgumentType.getString(ctx, "mode"), ignoreCase = true)
                                                    } ?: throw UNKNOWN_MODE.create()
                                                    if (!RadioActions.tune(
                                                            ctx.source.playerOrException,
                                                            IntegerArgumentType.getInteger(ctx, "slot") - 1,
                                                            DoubleArgumentType.getDouble(ctx, "frequency_khz"),
                                                            mode,
                                                        )
                                                    ) {
                                                        throw ACTION_FAILED.create()
                                                    }
                                                    1
                                                },
                                        ),
                                ),
                        ),
                )
                .then(
                    Commands.literal("active")
                        .then(
                            Commands.argument("slot", IntegerArgumentType.integer(1, 5))
                                .executes { ctx ->
                                    if (!RadioActions.setActive(ctx.source.playerOrException, IntegerArgumentType.getInteger(ctx, "slot") - 1)) {
                                        throw ACTION_FAILED.create()
                                    }
                                    1
                                },
                        ),
                )
                .then(
                    Commands.literal("power").executes { ctx ->
                        val player = ctx.source.playerOrException
                        val radio = RadioActions.heldRadio(player) ?: throw ACTION_FAILED.create()
                        val currentlyPowered = radio.dataOf(player.mainHandItem).powered
                        RadioActions.setPowered(player, !currentlyPowered)
                        1
                    },
                ),
        )
    }
}
