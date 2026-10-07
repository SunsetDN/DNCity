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

        /**
         * The seed of one random stream: the shot's seed combined with *what* the stream is for (construction, element, layer and
         * [SeedDomain]), not with how many calls came before. Adding a phase, a layer or a domain therefore never shifts the streams of
         * the others, and the same inputs give the same seed on the server and on a client.
         */
        fun seedFor(shotSeed: Long, constructionId: Ident, elementIndex: Int, layerIndex: Int, domain: SeedDomain): Long {
            var h = mix(shotSeed)
            h = mix(h xor fnv64(constructionId.toString()))
            h = mix(h xor elementIndex.toLong())
            h = mix(h xor (layerIndex.toLong() + 0x9E3779B97F4A7C15uL.toLong()))
            return mix(h xor fnv64(domain.name))
        }

        private fun fnv64(text: String): Long {
            var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
            for (b in text.toByteArray(Charsets.UTF_8)) {
                h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
            }
            return h
        }

        /** SplitMix64 finalizer. */
        private fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

/** What a random stream is for. Keyed by name, so reordering or extending this list never changes an existing stream. */
enum class SeedDomain { PENETRATION, EFFECT_BEFORE_PACKAGE, EFFECT_BEFORE_LAYER, EFFECT_AFTER_LAYER, EFFECT_AFTER_PACKAGE, SPALL, FUZE }
