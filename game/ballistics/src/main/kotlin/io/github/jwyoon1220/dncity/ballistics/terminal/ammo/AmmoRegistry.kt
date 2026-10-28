// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.JsonDataRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.LoadedCatalog
import io.github.jwyoon1220.dncity.ballistics.terminal.TerminalBallistics
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.neoforge.event.AddReloadListenerEvent

/**
 * The Minecraft boundary of the ammo system: `data/<ns>/dncity/ammo/(any depth)/name.json` as a data reload listener.
 *
 * Deliberately separate from item registration: items are registered once at mod load and cannot come and go, ammo data is
 * reloaded at will. A stack refers to its ammo by id (see AmmoItem) and looks the definition up each time, so a reload can
 * change, add or remove ammo without touching the item registry.
 */
object AmmoRegistry : JsonDataRegistry<AmmoDefinition>("dncity/ammo", "ammo definitions") {
    override fun parse(id: Ident, json: JsonObject) = AmmoDefinition.fromJson(id, json)

    /** The loaded ammo as an [AmmoSource] (looks up on every call, nothing stale is kept). */
    val source = AmmoSource { id -> this[id] }

    /** Loaded ballistics data plus every ammo as a projectile. */
    val catalog by lazy { AmmoAwareCatalog(LoadedCatalog, source) }

    val resolver by lazy { AmmoImpactResolver(source) }

    /** `-Ddncity.ballistics.production=true` refuses ammo whose table is not fully sourced (see [AmmoValidation.calibrationOf]). */
    val PRODUCTION: Boolean get() = java.lang.Boolean.getBoolean("dncity.ballistics.production")

    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        // After TerminalBallistics' own listeners: the validator below needs materials and projectiles to be loaded.
        event.addListener(io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber.FullCaliberApPresetRegistry)
        event.addListener(this)
        event.addListener(AmmoValidator)
    }
}

/** Cross-reference checks after a (re)load. Strict like the catalogs themselves, see [JsonDataRegistry]. */
object AmmoValidator : SimplePreparableReloadListener<Unit>() {
    override fun prepare(resourceManager: ResourceManager, profiler: ProfilerFiller) = Unit

    override fun apply(prepared: Unit, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val warnings = ArrayList<String>()
        val problems = AmmoValidation.validate(
            AmmoRegistry.all(), TerminalBallistics.loadedView(), AmmoSolvers.registry.ids(), AmmoRegistry.PRODUCTION, warnings,
        )
        warnings.forEach { Dncity.LOGGER.warn("Ammo data: {}", it) }
        if (problems.isEmpty()) return
        if (JsonDataRegistry.STRICT) throw IllegalStateException("Inconsistent ammo data (${problems.size}):\n  " + problems.joinToString("\n  "))
        problems.forEach { Dncity.LOGGER.error("Ammo data: {}", it) }
    }
}
