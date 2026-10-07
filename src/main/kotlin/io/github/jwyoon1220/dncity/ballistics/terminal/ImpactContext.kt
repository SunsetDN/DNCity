// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/** Where solvers look things up. The default is the loaded data; tests (and tools) can supply their own. */
interface BallisticsCatalog {
    fun projectile(id: ResourceLocation): ProjectileDefinition
    fun material(id: ResourceLocation): ArmorMaterial
    fun preset(id: ResourceLocation): ResistancePreset
    fun effect(id: ResourceLocation): ArmorEffectSpec
    fun construction(id: ResourceLocation): ArmorConstruction

    /** The loaded data packs. Looks up by id on every call, so a reload is picked up and nothing stale is kept. */
    object Loaded : BallisticsCatalog {
        override fun projectile(id: ResourceLocation) = ProjectileDefinitionRegistry.getOrThrow(id)
        override fun material(id: ResourceLocation) = ArmorMaterialRegistry.getOrThrow(id)
        override fun preset(id: ResourceLocation) = ResistancePresetRegistry.getOrThrow(id)
        override fun effect(id: ResourceLocation) = ArmorEffectRegistry.getOrThrow(id)
        override fun construction(id: ResourceLocation) = ArmorConstructionRegistry.getOrThrow(id)
    }
}

/**
 * One projectile meeting one armor surface. Everything a [PenetratorModel] may depend on is here, nothing is read from
 * global state: the same context always gives the same result, so client prediction, server solving and tests agree.
 *
 * @property surfaceNormal unit normal of the surface, pointing out of the armor towards the projectile
 * @property angleFromNormalRad 0 = perpendicular hit (see [ImpactAngle])
 */
class ImpactContext private constructor(
    val projectile: ProjectileState,
    val definition: ProjectileDefinition,
    val impactPoint: Vec3,
    val surfaceNormal: Vec3,
    val angleFromNormalRad: Double,
    val catalog: BallisticsCatalog,
) {
    /** The preset the layer's material uses against this projectile's penetrator model, if the material defines one. */
    fun resistanceOf(layer: ArmorLayer): ResistancePreset? = resistanceOf(catalog.material(layer.materialId))

    fun resistanceOf(material: ArmorMaterial): ResistancePreset? {
        val presetId = material.presetFor(definition.terminalModel) ?: return null
        return catalog.preset(presetId)
    }

    /** The same surface met by the (changed) projectile of the next element, with the angle worked out again. */
    fun next(projectile: ProjectileState, impactPoint: Vec3 = projectile.position, surfaceNormal: Vec3 = this.surfaceNormal): ImpactContext =
        of(projectile, catalog, impactPoint, surfaceNormal)

    companion object {
        fun of(
            projectile: ProjectileState,
            catalog: BallisticsCatalog,
            impactPoint: Vec3,
            surfaceNormal: Vec3,
        ): ImpactContext {
            val n = surfaceNormal.normalize()
            val speed = projectile.velocity.length()
            val cos = if (speed < 1e-9) 1.0 else (-projectile.velocity.dot(n) / speed).coerceIn(-1.0, 1.0)
            return ImpactContext(projectile, catalog.projectile(projectile.definitionId), impactPoint, n, kotlin.math.acos(cos), catalog)
        }
    }
}
