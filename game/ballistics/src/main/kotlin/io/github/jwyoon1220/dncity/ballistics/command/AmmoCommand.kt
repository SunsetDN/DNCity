// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerGeometry
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerRole
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileState
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoDefinition
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoEvaluation
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoValidation
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.ExternalBallistics
import io.github.jwyoon1220.dncity.ballistics.item.AmmoItem
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component

import net.neoforged.neoforge.event.RegisterCommandsEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * `/ammo`, operator-only. `list`/`info` show what the data packs declared, `give` hands out a stack, and `test` runs the real
 * [io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoImpactResolver] for one plate: the way to check an ammo JSON in game
 * without any gun or vehicle. `test` builds the shot exactly as a real one would be (ProjectileState + ImpactContext), so an
 * answer here is the answer the traversal gets.
 */
object AmmoCommand {
    private val UNKNOWN = SimpleCommandExceptionType(Component.literal("Unknown ammo"))

    fun onRegisterCommands(event: RegisterCommandsEvent) = register(event.dispatcher)

    private fun ammoArg() = Commands.argument("ammo", ResourceLocationArgument.id())
        .suggests { _, b -> SharedSuggestionProvider.suggestResource(AmmoRegistry.ids().map { net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(it.namespace, it.path) }, b) }

    private fun register(d: CommandDispatcher<CommandSourceStack>) {
        d.register(
            Commands.literal("ammo").requires { it.hasPermission(2) }
                .then(Commands.literal("list").executes { ctx -> list(ctx) })
                .then(Commands.literal("info").then(ammoArg().executes { ctx -> info(ctx) }))
                .then(
                    Commands.literal("give").then(
                        ammoArg().executes { ctx -> give(ctx, 1) }
                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 99)).executes { ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "count")) }),
                    ),
                )
                .then(
                    Commands.literal("test").then(
                        ammoArg().then(
                            Commands.argument("material", ResourceLocationArgument.id()).then(
                                Commands.argument("thickness_mm", DoubleArgumentType.doubleArg(0.001)).then(
                                    Commands.argument("angle_deg", DoubleArgumentType.doubleArg(0.0, 89.9)).executes { ctx -> test(ctx, null) }
                                        .then(Commands.argument("distance_m", DoubleArgumentType.doubleArg(0.0)).executes { ctx -> test(ctx, DoubleArgumentType.getDouble(ctx, "distance_m")) }),
                                ),
                            ),
                        ),
                    ),
                ),
        )
    }

    private fun ammoOf(ctx: CommandContext<CommandSourceStack>): AmmoDefinition {
        val rl = ResourceLocationArgument.getId(ctx, "ammo")
        return AmmoRegistry[Ident(rl.namespace, rl.path)] ?: throw UNKNOWN.create()
    }

    private fun say(ctx: CommandContext<CommandSourceStack>, text: String) = ctx.source.sendSuccess({ Component.literal(text) }, false)

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val all = AmmoRegistry.all().sortedBy { it.id.toString() }
        say(ctx, "${all.size} ammo definition(s)")
        all.forEach { say(ctx, "  ${it.id}  ${it.projectile.type} ${"%.1f".format(it.compat.gunCaliberM * 1000)} mm  ${it.penetration::class.simpleName}  ${AmmoValidation.calibrationOf(it)}") }
        return all.size
    }

    private fun info(ctx: CommandContext<CommandSourceStack>): Int {
        val a = ammoOf(ctx)
        val p = a.projectile
        say(ctx, "${a.id}: ${a.display.name} (${p.type}, ${p.construction})")
        say(ctx, "  gun ${"%.1f".format(a.compat.gunCaliberM * 1000)} mm, body ${"%.1f".format(p.diameterM * 1000)} mm, ${p.massKg} kg, v0 ${p.muzzleVelocityMps} m/s")
        p.penetrator?.let { say(ctx, "  penetrator ${it.material} d=${"%.1f".format(it.diameterM * 1000)} mm L=${"%.1f".format(it.lengthM * 1000)} mm L/D=${"%.1f".format(it.slenderness)}") }
        say(ctx, "  penetration ${a.penetration::class.simpleName}, solver ${a.terminal.solver}, calibration ${AmmoValidation.calibrationOf(a)}, provenance ${a.provenance.status}")
        a.table?.let { t ->
            say(ctx, "  table ${t.grid.distancesM.size} distances x ${t.grid.anglesRad.size} angles, ref ${t.meta.referenceMaterial}, criterion ${t.meta.criterion}, out_of_range ${t.meta.rangePolicy.mode}")
        }
        return 1
    }

    private fun give(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val a = ammoOf(ctx)
        val player = ctx.source.playerOrException
        val stack = AmmoItem.stackOf(a, count)
        if (!player.inventory.add(stack)) player.drop(stack, false)
        say(ctx, "Gave ${stack.count} x ${a.display.name}")
        return stack.count
    }

    private fun test(ctx: CommandContext<CommandSourceStack>, distanceM: Double?): Int {
        val a = ammoOf(ctx)
        val rl = ResourceLocationArgument.getId(ctx, "material")
        val thicknessM = DoubleArgumentType.getDouble(ctx, "thickness_mm") / 1000.0
        val angle = Math.toRadians(DoubleArgumentType.getDouble(ctx, "angle_deg"))
        val layer = ArmorLayer(Ident(rl.namespace, rl.path), thicknessM, LayerGeometry.FLAT, LayerRole.ARMOR)

        // Same construction as a real shot: speed from the distance the test asks about (or muzzle velocity if none).
        val speed = distanceM?.let { ExternalBallistics.speedAtDistance(a.projectile, it) } ?: a.projectile.muzzleVelocityMps
        val def = AmmoRegistry.catalog.projectile(a.id)
        val state = ProjectileState.launch(def, V3.ZERO, V3(cos(angle), sin(angle), 0.0), speed)
        val context = ImpactContext.of(state, AmmoRegistry.catalog, V3.ZERO, V3(-1.0, 0.0, 0.0))

        when (val e = AmmoRegistry.resolver.resolve(context, layer, distanceM)) {
            is AmmoEvaluation.Resolved -> {
                say(ctx, "${e.verdict} via ${e.source}: plate ${"%.1f".format(thicknessM * 1000)} mm at ${"%.1f".format(Math.toDegrees(angle))} deg, v ${"%.0f".format(speed)} m/s")
                e.limitThicknessM?.let { say(ctx, "  table limit ${"%.1f".format(it * 1000)} mm at ${"%.0f".format(e.flightDistanceM ?: 0.0)} m") }
                e.result?.let { say(ctx, "  outcome ${it.outcome}, residual ${it.residual?.speedMps?.let { v -> "%.0f m/s".format(v) } ?: "none"}") }
                    ?: say(ctx, "  no residual (a table gives a limit, not an exit speed)")
                if (e.diagnostics.isNotEmpty()) say(ctx, "  diagnostics: ${e.diagnostics.joinToString()}")
            }
            is AmmoEvaluation.Unresolved -> {
                say(ctx, "UNRESOLVED (${e.reason}): ${e.detail}")
                if (e.diagnostics.isNotEmpty()) say(ctx, "  diagnostics: ${e.diagnostics.joinToString()}")
            }
        }
        return 1
    }
}
