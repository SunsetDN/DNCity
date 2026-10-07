// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.neoforge.event.AddReloadListenerEvent

// The Minecraft-facing side of terminal ballistics: data catalogs as reload listeners. Everything with logic is in the plain
// classes (fromJson, BallisticsValidation, ArmorTraverser) so it runs and is tested without the game.

object ResistancePresetRegistry : JsonDataRegistry<ResistancePreset>("dncity/resistance_presets", "resistance presets") {
    override fun parse(id: Ident, json: JsonObject) = ResistancePreset.fromJson(id, json)
}

object ArmorMaterialRegistry : JsonDataRegistry<ArmorMaterial>("dncity/armor_materials", "armor materials") {
    override fun parse(id: Ident, json: JsonObject) = ArmorMaterial.fromJson(id, json)
}

object ArmorEffectRegistry : JsonDataRegistry<ArmorEffectSpec>("dncity/armor_effects", "armor effects") {
    override fun parse(id: Ident, json: JsonObject) = ArmorEffectSpec.fromJson(id, json)
}

object ArmorConstructionRegistry : JsonDataRegistry<ArmorConstruction>("dncity/armor_constructions", "armor constructions") {
    override fun parse(id: Ident, json: JsonObject) = ArmorConstruction.fromJson(id, json)
}

object ProjectileDefinitionRegistry : JsonDataRegistry<ProjectileDefinition>("dncity/projectiles", "projectile definitions") {
    override fun parse(id: Ident, json: JsonObject) = ProjectileDefinition.fromJson(id, json)
}

/** The loaded data packs. Looks up by id on every call, so a reload is picked up and nothing stale is kept. */
object LoadedCatalog : BallisticsCatalog {
    override fun projectile(id: Ident) = ProjectileDefinitionRegistry.getOrThrow(id)
    override fun material(id: Ident) = ArmorMaterialRegistry.getOrThrow(id)
    override fun preset(id: Ident) = ResistancePresetRegistry.getOrThrow(id)
    override fun effect(id: Ident) = ArmorEffectRegistry.getOrThrow(id)
    override fun construction(id: Ident) = ArmorConstructionRegistry.getOrThrow(id)
}

/** Entry point of the terminal ballistics data: registers every catalog as a server data reload listener. */
object TerminalBallistics {
    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(ResistancePresetRegistry)
        event.addListener(ArmorMaterialRegistry)
        event.addListener(ArmorEffectRegistry)
        event.addListener(ArmorConstructionRegistry)
        event.addListener(ProjectileDefinitionRegistry)
        // Last: listeners apply in the order they were added, so everything it checks is already loaded.
        event.addListener(TerminalBallisticsValidator)
    }

    fun loadedView() = BallisticsCatalogView(
        ArmorMaterialRegistry.all(),
        ResistancePresetRegistry.asMap(),
        ArmorConstructionRegistry.all(),
        ArmorEffectRegistry.all(),
        ProjectileDefinitionRegistry.all(),
    )
}

/** Runs [BallisticsValidation] after a (re)load. Strict like the catalogs themselves, see [JsonDataRegistry]. */
object TerminalBallisticsValidator : SimplePreparableReloadListener<Unit>() {
    override fun prepare(resourceManager: ResourceManager, profiler: ProfilerFiller) = Unit

    override fun apply(prepared: Unit, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val problems = BallisticsValidation.validate(
            TerminalBallistics.loadedView(),
            registeredPenetrators = PenetratorModels.registry.ids(),
            registeredEffectModels = ArmorEffectModels.registry.ids(),
        )
        if (problems.isEmpty()) return
        if (JsonDataRegistry.STRICT) {
            throw IllegalStateException("Inconsistent ballistics data (${problems.size}):\n  " + problems.joinToString("\n  "))
        }
        problems.forEach { Dncity.LOGGER.error("Ballistics data: {}", it) }
    }
}
