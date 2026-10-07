// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/**
 * What happens when a projectile of one *kind of penetration physics* meets one solid layer: small arms, full-caliber AP,
 * APCR/APDS, long rod, shaped charge. A projectile names its model by [ProjectileDefinition.terminalModel] and a material
 * names its resistance data for that model by [ArmorMaterial.resistance]. No model is the "default physics" of the core.
 *
 * Implementations must be deterministic and free of side effects: they get everything through [ImpactContext], and any
 * randomness comes from a seed in it (to be added with the first model that needs one), because the server solves the
 * same shot the client claimed.
 */
interface PenetratorModel {
    val id: ResourceLocation

    /** Meets one solid [layer] (already known to be hit); the angle and projectile state are in [context]. */
    fun solve(context: ImpactContext, layer: ArmorLayer): PenetrationResult
}

/** What an interaction armor did: the projectile that comes out of it (possibly nothing left) and whether it was used up. */
data class EffectResult(val projectile: ProjectileState?, val consumed: Boolean, val spall: SpallSource? = null)

/** ERA, NERA and other interactions of an [ArmorElement.EffectPackage]; the layers of the package are traversed afterwards. */
interface ArmorEffectModel {
    val id: ResourceLocation

    fun interact(context: ImpactContext, pack: ArmorElement.EffectPackage, effect: ArmorEffectSpec): EffectResult
}

/** Code registry of solvers, filled once at mod setup. Data refers to them by id. */
class ModelRegistry<T : Any>(private val label: String, private val idOf: (T) -> ResourceLocation) {
    private val models = ConcurrentHashMap<ResourceLocation, T>()

    fun register(model: T) {
        val id = idOf(model)
        check(models.putIfAbsent(id, model) == null) { "$label $id is already registered" }
    }

    operator fun get(id: ResourceLocation): T? = models[id]

    fun getOrThrow(id: ResourceLocation): T = models[id] ?: throw NoSuchElementException("no $label registered for $id")

    fun ids(): Set<ResourceLocation> = models.keys
}

object PenetratorModels {
    val registry = ModelRegistry<PenetratorModel>("penetrator model") { it.id }
}

object ArmorEffectModels {
    val registry = ModelRegistry<ArmorEffectModel>("armor effect model") { it.id }
}
