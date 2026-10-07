// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.math.cos
import kotlin.math.sin

/**
 * Test fixtures only. Every number here is made up to be easy to check by hand; none of it is ballistics data and none of it
 * may move into src/main/resources.
 */
object Fx {
    val MODEL = Ident("test", "fake")
    val PROJECTILE = Ident("test", "projectile")
    val STEEL = Ident("test", "steel")
    val ERA = Ident("test", "era")
    val ERA_SOLVER = Ident("test", "era_solver")

    fun fuzed(arming: Double?) = ProjectileDefinition(
        id = PROJECTILE, gunCaliberM = 0.1, projectileDiameterM = 0.1, massKg = 1.0, geometry = Geometry.OGIVE,
        stabilization = Stabilization.NONE, penetrator = null,
        payload = arming?.let { PayloadSpec(1.0, null, null, FuzeSpec(it, 0.0, null, 0.0)) },
        external = ExternalBallisticsSpec(0.3, 0.0), terminalModel = MODEL,
    )

    val steel = ArmorMaterial(STEEL, MaterialClass.METAL, 7850.0, 300.0, emptyMap(), SpallSpec(false, 0.0))
    val eraSpec = ArmorEffectSpec(ERA, ERA_SOLVER, emptyMap(), singleUse = true)

    class Catalog(val projectile: ProjectileDefinition = fuzed(null)) : BallisticsCatalog {
        override fun projectile(id: Ident) = projectile
        override fun material(id: Ident) = steel
        override fun preset(id: Ident) = throw UnsupportedOperationException()
        override fun effect(id: Ident) = eraSpec
        override fun construction(id: Ident) = throw UnsupportedOperationException()
    }

    fun layer(thicknessM: Double) = ArmorLayer(STEEL, thicknessM, LayerGeometry.FLAT, LayerRole.ARMOR)
    fun solid(thicknessM: Double) = ArmorElement.Solid(layer(thicknessM))
    fun pack(vararg thicknessM: Double) = ArmorElement.EffectPackage(thicknessM.map { layer(it) }, ERA)

    /** Head-on: moving +x at [speed], the surface faces -x. */
    fun headOn(speed: Double = 1000.0, def: ProjectileDefinition = fuzed(null)) =
        ProjectileState.launch(def, V3.ZERO, V3(1.0, 0.0, 0.0), speed)

    /** [angleRad] off the surface normal, in the x/y plane. */
    fun oblique(angleRad: Double, speed: Double = 1000.0, def: ProjectileDefinition = fuzed(null)) =
        ProjectileState.launch(def, V3.ZERO, V3(cos(angleRad), sin(angleRad), 0.0), speed)

    val facingMinusX = UniformSurface(V3(-1.0, 0.0, 0.0))

    fun construction(vararg elements: ArmorElement) = ArmorConstruction(Ident("test", "c"), elements.toList())

    /**
     * Loses 1000 m/s per metre of line-of-sight thickness, stops when nothing is left, passes on a state that has been
     * deformed by 0.1 per layer so a test can tell the residual really was handed on. Energy is conserved exactly.
     */
    class ThicknessModel(val log: MutableList<String> = ArrayList()) : PenetratorModel {
        override val id = MODEL
        val inputs = ArrayList<ProjectileState>()
        val seeds = ArrayList<Long>()

        override fun solve(context: ImpactContext, layer: ArmorLayer): PenetrationResult {
            val p = context.projectile
            inputs += p
            seeds += context.seed
            log += "layer(${layer.thicknessM})"
            val los = ImpactAngle.losThicknessM(layer.thicknessM, context.angleFromNormalRad)
            val newSpeed = p.speedMps - los * 1000.0
            if (newSpeed <= 0.0) return PenetrationResult.stopped(p.kineticEnergyJ)
            val dir = p.velocity.normalize()
            val point = p.position + dir.scale(los)
            val residual = p.copy(position = point, velocity = dir.scale(newSpeed), deformation = p.deformation + 0.1)
            return PenetrationResult.perforated(residual, p.kineticEnergyJ - residual.kineticEnergyJ, point)
        }
    }

    /** Bounces everything: half the speed, mirrored in y. */
    class RicochetModel : PenetratorModel {
        override val id = MODEL
        var calls = 0

        override fun solve(context: ImpactContext, layer: ArmorLayer): PenetrationResult {
            calls++
            val p = context.projectile
            val v = p.velocity
            val out = p.copy(velocity = V3(v.x * 0.5, -v.y * 0.5 + 0.1, v.z * 0.5))
            return PenetrationResult.ricochet(out, p.kineticEnergyJ - out.kineticEnergyJ)
        }
    }

    /** Calls are logged in order; [handler] decides what the effect does. */
    class RecordingEffect(
        override val phases: Set<EffectPhase>,
        val log: MutableList<String>,
        val handler: (EffectInvocation) -> EffectInteractionResult = { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = false) },
    ) : ArmorEffectModel {
        override val id = ERA_SOLVER

        override fun interact(invocation: EffectInvocation): EffectInteractionResult {
            log += "${invocation.phase}${invocation.layerIndex?.let { "($it)" } ?: ""}"
            return handler(invocation)
        }
    }

    fun traverser(model: PenetratorModel, effect: ArmorEffectModel? = null, catalog: BallisticsCatalog = Catalog()): ArmorTraverser {
        val pens = ModelRegistry<PenetratorModel>("penetrator") { it.id }.also { it.register(model) }
        val effs = ModelRegistry<ArmorEffectModel>("effect") { it.id }.also { e -> effect?.let(e::register) }
        return ArmorTraverser(catalog, pens, effs)
    }
}
