// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

/** The loaded catalogs as plain collections, so validation does not care where they came from (data reload or a test). */
class BallisticsCatalogView(
    val materials: Collection<ArmorMaterial>,
    val presets: Map<Ident, ResistancePreset>,
    val constructions: Collection<ArmorConstruction>,
    val effects: Collection<ArmorEffectSpec>,
    val projectiles: Collection<ProjectileDefinition> = emptyList(),
) {
    val materialIds: Set<Ident> = materials.mapTo(HashSet()) { it.id }
    val effectIds: Set<Ident> = effects.mapTo(HashSet()) { it.id }
}

/**
 * Cross-reference checks, run once every catalog is loaded. Structural rules (positive thickness and distance, a package
 * has layers, internal space last) are enforced by the data classes themselves and fail while parsing.
 */
object BallisticsValidation {
    /**
     * @param registeredPenetrators ids of the penetrator models that exist in code, or null to skip the check
     * @param registeredEffectModels ids of the armor effect models that exist in code, or null to skip the check
     */
    fun validate(
        view: BallisticsCatalogView,
        registeredPenetrators: Set<Ident>? = null,
        registeredEffectModels: Set<Ident>? = null,
    ): List<String> {
        val problems = ArrayList<String>()
        for (m in view.materials) {
            for ((model, presetId) in m.resistance) {
                val preset = view.presets[presetId]
                if (preset == null) problems += "material ${m.id}: unknown preset $presetId"
                else if (preset.solver != model) problems += "material ${m.id}: preset $presetId is for ${preset.solver}, filed under $model"
            }
        }
        fun checkLayer(owner: Ident, layer: ArmorLayer) {
            if (layer.materialId !in view.materialIds) problems += "$owner: unknown material ${layer.materialId}"
        }
        for (c in view.constructions) {
            for (e in c.elements) when (e) {
                is ArmorElement.Solid -> checkLayer(c.id, e.layer)
                is ArmorElement.EffectPackage -> {
                    e.layers.forEach { checkLayer(c.id, it) }
                    if (e.effectId !in view.effectIds) problems += "${c.id}: unknown effect ${e.effectId}"
                }
                is ArmorElement.Gap -> Unit
                is ArmorElement.InternalSpace -> Unit
            }
        }
        if (registeredEffectModels != null) {
            for (e in view.effects) if (e.solver !in registeredEffectModels) problems += "effect ${e.id}: no effect model registered for ${e.solver}"
        }
        if (registeredPenetrators != null) {
            for (p in view.projectiles) if (p.terminalModel !in registeredPenetrators) problems += "projectile ${p.id}: no penetrator model registered for ${p.terminalModel}"
        }
        return problems
    }
}
