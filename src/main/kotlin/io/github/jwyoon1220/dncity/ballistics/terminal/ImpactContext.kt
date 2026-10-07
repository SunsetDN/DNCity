// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

/** Where solvers look things up. The default is the loaded data ([LoadedCatalog]); tests and tools can supply their own. */
interface BallisticsCatalog {
    fun projectile(id: Ident): ProjectileDefinition
    fun material(id: Ident): ArmorMaterial
    fun preset(id: Ident): ResistancePreset
    fun effect(id: Ident): ArmorEffectSpec
    fun construction(id: Ident): ArmorConstruction
}

/**
 * One projectile meeting one surface. Everything a [PenetratorModel] may depend on is here, nothing is read from global
 * state, so the same context always gives the same result and client prediction, server solving and tests agree.
 *
 * **Determinism rule for solvers:** no `Random`, no clock, no entity or world state. Anything stochastic (spall pattern,
 * fuze failure) draws from [seed], which the server fixes per shot, so a claimed shot can be reproduced exactly.
 *
 * @property surfaceNormal unit normal of the surface, pointing out of the armor towards the projectile
 * @property angleFromNormalRad derived from the projectile's current direction and [surfaceNormal], 0 = perpendicular
 * @property seed explicit randomness for this impact
 */
class ImpactContext private constructor(
    val projectile: ProjectileState,
    val definition: ProjectileDefinition,
    val impactPoint: V3,
    val surfaceNormal: V3,
    val angleFromNormalRad: Double,
    val catalog: BallisticsCatalog,
    val seed: Long,
) {
    /** The preset the layer's material uses against this projectile's penetrator model, if the material defines one. */
    fun resistanceOf(layer: ArmorLayer): ResistancePreset? = resistanceOf(catalog.material(layer.materialId))

    fun resistanceOf(material: ArmorMaterial): ResistancePreset? {
        val presetId = material.presetFor(definition.terminalModel) ?: return null
        return catalog.preset(presetId)
    }

    companion object {
        fun of(
            projectile: ProjectileState,
            catalog: BallisticsCatalog,
            impactPoint: V3,
            surfaceNormal: V3,
            seed: Long = 0L,
        ): ImpactContext {
            val n = surfaceNormal.normalize()
            require(n.lengthSqr() > 0.0) { "surface normal must not be zero" }
            return ImpactContext(
                projectile, catalog.projectile(projectile.definitionId), impactPoint, n,
                ImpactAngle.fromNormal(projectile.velocity, n), catalog, seed,
            )
        }

        /** Mixes a shot's seed with the position in the stack so every layer gets its own, reproducible, stream. */
        fun seedFor(shotSeed: Long, elementIndex: Int, layerIndex: Int): Long {
            var h = shotSeed xor (elementIndex.toLong() * -0x61c8864680b583ebL) xor (layerIndex.toLong() * 0x2545F4914F6CDD1DL)
            h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
            h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
            return h xor (h ushr 31)
        }
    }
}
