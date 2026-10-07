// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import java.util.concurrent.ConcurrentHashMap

/**
 * What happens when a projectile of one *kind of penetration physics* meets one solid layer: small arms, full-caliber AP,
 * APCR/APDS, long rod, shaped charge. A projectile names its model by [ProjectileDefinition.terminalModel] and a material
 * names its resistance data for that model by [ArmorMaterial.resistance]. No model is the "default physics" of the core.
 *
 * Implementations are pure: deterministic (see [ImpactContext]), no side effects, no world access. They never lose track of
 * energy in the other direction: see [EnergyAccounting].
 */
interface PenetratorModel {
    val id: Ident

    /** Meets one solid [layer] (already known to be hit); the angle and projectile state are in [context]. */
    fun solve(context: ImpactContext, layer: ArmorLayer): PenetrationResult
}

/** Code registry of solvers, filled once at mod setup. Data refers to them by id. */
class ModelRegistry<T : Any>(private val label: String, private val idOf: (T) -> Ident) {
    private val models = ConcurrentHashMap<Ident, T>()

    fun register(model: T) {
        val id = idOf(model)
        check(models.putIfAbsent(id, model) == null) { "$label $id is already registered" }
    }

    operator fun get(id: Ident): T? = models[id]

    fun getOrThrow(id: Ident): T = models[id] ?: throw NoSuchElementException("no $label registered for $id")

    fun ids(): Set<Ident> = models.keys
}

object PenetratorModels {
    val registry = ModelRegistry<PenetratorModel>("penetrator model") { it.id }
}

object ArmorEffectModels {
    val registry = ModelRegistry<ArmorEffectModel>("armor effect model") { it.id }
}
