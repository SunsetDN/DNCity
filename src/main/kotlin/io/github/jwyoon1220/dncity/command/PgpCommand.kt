package io.github.jwyoon1220.dncity.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.jwyoon1220.dncity.security.PgpKeyRegistry
import io.github.jwyoon1220.dncity.security.PgpPaths
import io.github.jwyoon1220.dncity.security.PgpSettings
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.io.File
import java.util.UUID

/**
 * Operator-only key lifecycle management for [PgpKeyRegistry] -- see
 * [io.github.jwyoon1220.dncity.security.PgpAuthServerEvents] for how a registered key gates
 * login. Registration itself stays a two-step, out-of-band process rather than a single command
 * that takes an armored key as a chat argument (those routinely run to 20+ lines, far past what a
 * Brigadier word/quoted-string argument is meant for): the player exports their public key from
 * Kleopatra and sends it to an operator by any channel, the operator drops the file at
 * `config/dncity/security/pgp/incoming/<playername>.asc`, then runs `/pgp register <playername>`
 * to move it into the real registry. `/pgp enable`/`/pgp disable` toggle the whole login gate
 * ([io.github.jwyoon1220.dncity.security.PgpSettings]) without touching anyone's registered key.
 */
object PgpCommand {
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("pgp")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.literal("register").then(
                        Commands.argument("player", StringArgumentType.word()).executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "player")
                            val playerId = resolvePlayerId(ctx.source.server, name)
                            if (playerId == null) {
                                ctx.source.sendFailure(Component.literal("Unknown player: $name"))
                                return@executes 0
                            }
                            val incoming = File(PgpPaths.incomingDir, "$name.asc")
                            if (!incoming.isFile) {
                                ctx.source.sendFailure(
                                    Component.literal("No pending key at config/dncity/security/pgp/incoming/$name.asc"),
                                )
                                return@executes 0
                            }
                            PgpKeyRegistry.register(playerId, incoming.readText())
                            incoming.delete()
                            ctx.source.sendSuccess({ Component.literal("Registered PGP key for $name") }, true)
                            1
                        },
                    ),
                )
                .then(
                    Commands.literal("unregister").then(
                        Commands.argument("player", StringArgumentType.word()).executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "player")
                            val playerId = resolvePlayerId(ctx.source.server, name)
                            if (playerId == null || !PgpKeyRegistry.unregister(playerId)) {
                                ctx.source.sendFailure(Component.literal("No registered key for $name"))
                                return@executes 0
                            }
                            ctx.source.sendSuccess({ Component.literal("Unregistered PGP key for $name") }, true)
                            1
                        },
                    ),
                )
                .then(
                    Commands.literal("list").executes { ctx ->
                        val ids = PgpKeyRegistry.registeredPlayerIds()
                        ctx.source.sendSuccess(
                            { Component.literal(if (ids.isEmpty()) "No registered PGP keys" else "Registered: ${ids.joinToString()}") },
                            false,
                        )
                        1
                    },
                )
                .then(
                    Commands.literal("enable").executes { ctx ->
                        PgpSettings.enabled = true
                        ctx.source.sendSuccess({ Component.literal("PGP login gate enabled") }, true)
                        1
                    },
                )
                .then(
                    Commands.literal("disable").executes { ctx ->
                        PgpSettings.enabled = false
                        ctx.source.sendSuccess({ Component.literal("PGP login gate disabled") }, true)
                        1
                    },
                ),
        )
    }

    private fun resolvePlayerId(server: MinecraftServer, name: String): UUID? {
        server.playerList.getPlayerByName(name)?.let { return it.uuid }
        return server.profileCache?.get(name)?.map { it.id }?.orElse(null)
    }
}
