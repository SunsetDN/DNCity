// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.neoforge.event.AddReloadListenerEvent

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
}

/**
 * Checks the references between the catalogs once all are loaded: a material's preset must exist and belong to the same
 * solver as the terminal model it is filed under, a construction's materials and effects must exist. Strict like the
 * catalogs themselves (see [JsonDataRegistry]).
 */
object TerminalBallisticsValidator : SimplePreparableReloadListener<Unit>() {
    override fun prepare(resourceManager: ResourceManager, profiler: ProfilerFiller) = Unit

    override fun apply(prepared: Unit, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        val problems = validate(BallisticsCatalogView.loaded())
        if (problems.isEmpty()) return
        if (JsonDataRegistry.STRICT) {
            throw IllegalStateException("Inconsistent ballistics data (${problems.size}):\n  " + problems.joinToString("\n  "))
        }
        problems.forEach { io.github.jwyoon1220.dncity.Dncity.LOGGER.error("Ballistics data: {}", it) }
    }

    /** Pure so it can be tested without a running game. */
    fun validate(view: BallisticsCatalogView): List<String> {
        val problems = ArrayList<String>()
        for (m in view.materials) {
            for ((model, presetId) in m.resistance) {
                val preset = view.presets[presetId]
                if (preset == null) problems += "material ${m.id}: unknown preset $presetId"
                else if (preset.solver != model) problems += "material ${m.id}: preset $presetId is for ${preset.solver}, filed under $model"
            }
        }
        fun checkLayer(owner: Any, layer: ArmorLayer) {
            if (layer.materialId !in view.materialIds) problems += "$owner: unknown material ${layer.materialId}"
        }
        for (c in view.constructions) {
            for (e in c.elements) when (e) {
                is ArmorElement.Solid -> checkLayer(c.id, e.layer)
                is ArmorElement.EffectPackage -> {
                    e.layers.forEach { checkLayer(c.id, it) }
                    if (e.effectId !in view.effectIds) problems += "${c.id}: unknown effect ${e.effectId}"
                }
                is ArmorElement.Gap -> e.fillMaterialId?.let { if (it !in view.materialIds) problems += "${c.id}: unknown gap fill $it" }
                is ArmorElement.InternalSpace -> Unit
            }
        }
        return problems
    }
}

/** The loaded catalogs as plain collections, so the validator does not care where they came from. */
class BallisticsCatalogView(
    val materials: Collection<ArmorMaterial>,
    val presets: Map<net.minecraft.resources.ResourceLocation, ResistancePreset>,
    val constructions: Collection<ArmorConstruction>,
    val effectIds: Set<net.minecraft.resources.ResourceLocation>,
) {
    val materialIds: Set<net.minecraft.resources.ResourceLocation> = materials.mapTo(HashSet()) { it.id }

    companion object {
        fun loaded() = BallisticsCatalogView(
            ArmorMaterialRegistry.all(),
            ResistancePresetRegistry.ids().associateWith { ResistancePresetRegistry.getOrThrow(it) },
            ArmorConstructionRegistry.all(),
            ArmorEffectRegistry.ids(),
        )
    }
}
