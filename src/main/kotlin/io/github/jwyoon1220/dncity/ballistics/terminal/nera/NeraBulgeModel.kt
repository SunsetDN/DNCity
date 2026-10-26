// AGENT-DONE(claude): nera-bulge-model
package io.github.jwyoon1220.dncity.ballistics.terminal.nera

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorEffectModel
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorEffectSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorElement
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorTear
import io.github.jwyoon1220.dncity.ballistics.terminal.EffectInteractionResult
import io.github.jwyoon1220.dncity.ballistics.terminal.EffectInvocation
import io.github.jwyoon1220.dncity.ballistics.terminal.EffectPhase
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationOutcome
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileState
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallSource
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tuning of one NERA tile, read from [ArmorEffectSpec.parameters]. **None of these numbers is calibrated against a test**; the
 * defaults are in the range the open literature gives for rubber/polyurethane sandwiches and are a starting point, not data.
 *
 * @property cavityExpansionRatio radial speed at which the cavity in the elastomer grows, as a fraction of the projectile speed
 * @property elastomerWaveSpeedMps bulk wave speed of the elastomer; with the ratio above it sets the pressure on the plates
 * @property energyCapFraction most of the projectile's kinetic energy that may end up as plate motion (keeps the plates from
 *   getting more energy than the projectile had to give)
 * @property plateSpreadRatio the loaded plate area has the radius `d/2 + ratio * elastomer thickness`
 * @property lateralFailureMps sideways speed (at the rod's ends) at which a penetrator is destroyed; the damage grows with the
 *   square of the ratio
 * @property disruptionCoefficient scales that damage
 * @property debrisSpeedRatio speed of the broken-off pieces relative to the rest of the projectile
 * @property tearStrain plate strain at which the plates fail and petal instead of just bulging
 * @property plateSpallEnergyFraction share of the residual energy that torn plate pieces take off the back of a perforated sandwich
 */
data class NeraParameters(
    val cavityExpansionRatio: Double = 0.25,
    val elastomerWaveSpeedMps: Double = 1600.0,
    val energyCapFraction: Double = 0.25,
    val plateSpreadRatio: Double = 1.0,
    val lateralFailureMps: Double = 400.0,
    val disruptionCoefficient: Double = 1.0,
    val debrisSpeedRatio: Double = 0.6,
    val tearStrain: Double = 0.12,
    val plateSpallEnergyFraction: Double = 0.05,
) {
    init {
        require(cavityExpansionRatio in 0.0..1.0) { "cavity_expansion_ratio must be in 0..1" }
        require(elastomerWaveSpeedMps > 0.0) { "elastomer_wave_speed_mps must be positive" }
        require(energyCapFraction in 0.0..1.0) { "energy_cap_fraction must be in 0..1" }
        require(plateSpreadRatio >= 0.0) { "plate_spread_ratio must not be negative" }
        require(lateralFailureMps > 0.0) { "lateral_failure_mps must be positive" }
        require(disruptionCoefficient >= 0.0) { "disruption_coefficient must not be negative" }
        require(debrisSpeedRatio in 0.0..1.0) { "debris_speed_ratio must be in 0..1" }
        require(tearStrain > 0.0) { "tear_strain must be positive" }
        require(plateSpallEnergyFraction in 0.0..1.0) { "plate_spall_energy_fraction must be in 0..1" }
    }

    companion object {
        private val KEYS = setOf(
            "cavity_expansion_ratio", "elastomer_wave_speed_mps", "energy_cap_fraction", "plate_spread_ratio",
            "lateral_failure_mps", "disruption_coefficient", "debris_speed_ratio", "tear_strain", "plate_spall_energy_fraction",
        )

        fun of(spec: ArmorEffectSpec): NeraParameters {
            val p = spec.parameters
            val unknown = p.keys - KEYS
            require(unknown.isEmpty()) { "unknown NERA parameter(s) $unknown" }
            val d = NeraParameters()
            return NeraParameters(
                p["cavity_expansion_ratio"] ?: d.cavityExpansionRatio,
                p["elastomer_wave_speed_mps"] ?: d.elastomerWaveSpeedMps,
                p["energy_cap_fraction"] ?: d.energyCapFraction,
                p["plate_spread_ratio"] ?: d.plateSpreadRatio,
                p["lateral_failure_mps"] ?: d.lateralFailureMps,
                p["disruption_coefficient"] ?: d.disruptionCoefficient,
                p["debris_speed_ratio"] ?: d.debrisSpeedRatio,
                p["tear_strain"] ?: d.tearStrain,
                p["plate_spall_energy_fraction"] ?: d.plateSpallEnergyFraction,
            )
        }
    }
}

/**
 * Non-explosive reactive armor: **plate / elastomer / plate**. A penetrator that has gone through the front plate and the
 * elastomer drives the rubber outwards; the rubber cannot be compressed, so it shoves the two plates apart (the front one back
 * towards the shooter, the back one away from him). The plates move along their *normal*, so a hit that is not perpendicular sees
 * them moving *sideways* across its line of flight. In 3D:
 *
 * 1. **Plate speeds** come from an impulse balance, `v = kappa * c * (rho_e * t_e) / (rho_p * t_p) / cos(theta)`: the pressure
 *    `rho_e * c * kappa * v_proj` on the loaded patch acts for the time the projectile needs to cross the elastomer, so the
 *    projectile's own speed drops out. The plates cannot take more than `energy_cap_fraction` of the projectile's energy.
 * 2. **Sideways speed at the ends of the rod.** The plate normal `n` and the flight direction `d` span a plane; the component of
 *    the plate velocity across `d` is `v * sin(theta)` along `l = normalize(n + cos(theta) d)`. The back plate drags the rod's head
 *    one way, the front plate its tail the other way, scaled by the share of plate mass that actually couples to the rod. A
 *    perpendicular hit (`theta = 0`) has no sideways component, and NERA does very little against it, as in reality.
 * 3. **Effect on the projectile:** the velocity turns a little (the net sideways push), the body axis yaws (the couple of the two
 *    pushes), and a fraction of the penetrator breaks off as debris, `1 - exp(-c * (u / u_fail)^2)`; the mass, length and
 *    integrity drop by that fraction and the debris flies on as a [SpallSource]. Energy: the plates' energy is taken out first, the
 *    rest is shared between the remainder and the debris with the debris slower, so the sum never exceeds what the projectile had.
 * 4. **Tearing.** The strain of the bulging plate decides whether it just dishes or fails. The result is an elliptical
 *    [ArmorTear] (long side along the line of fire's trace in the plate) that the vehicle keeps: a later hit inside it meets no
 *    working sandwich, one at its rim a weakened one. A perforated sandwich also throws plug/petal fragments off its back, energy
 *    taken from what is left of the projectile.
 *
 * What is **not** modelled: the back plate moving away lowers the closing speed on it (the penetrator model sees the world-frame
 * speed), the multi-hit overlap of bulges, plate thinning, elastomer type beyond density and wave speed. Hence "provisional".
 *
 * Passive: no stored energy, never consumed; the tile degrades by tears instead. The package must be exactly three layers.
 */
class NeraBulgeModel : ArmorEffectModel {
    override val id: Ident = ID
    override val phases: Set<EffectPhase> = setOf(EffectPhase.BEFORE_LAYER, EffectPhase.AFTER_LAYER)

    override fun interact(invocation: EffectInvocation): EffectInteractionResult {
        val pack = invocation.pack
        check(pack.layers.size == 3) { "a NERA package is plate/elastomer/plate, got ${pack.layers.size} layers" }
        if (invocation.layerIndex != 2) return EffectInteractionResult.untouched()
        val params = NeraParameters.of(invocation.spec)
        val ctx = invocation.context
        val result = invocation.layerResult
        return when (invocation.phase) {
            EffectPhase.BEFORE_LAYER -> disrupt(invocation, ctx, params)
            EffectPhase.AFTER_LAYER -> rupture(invocation, ctx, params, result!!)
            else -> EffectInteractionResult.untouched()
        }
    }

    // ---- geometry shared by both hooks ------------------------------------------------------------------------------------

    /** Everything about the sandwich and the shot that both hooks need, derived once from the context. */
    private class Sandwich(val ctx: ImpactContext, val pack: ArmorElement.EffectPackage, val p: NeraParameters, projectileEnergyJ: Double) {
        val n: V3 = ctx.surfaceNormal
        val frontLayer = pack.layers[0]
        val elastomerLayer = pack.layers[1]
        val backLayer = pack.layers[2]
        val front = ctx.catalog.material(frontLayer.materialId)
        val elastomer = ctx.catalog.material(elastomerLayer.materialId)
        val back = ctx.catalog.material(backLayer.materialId)

        val projectile: ProjectileState = ctx.projectile
        val diameterM: Double = ctx.definition.penetrator?.diameterM ?: ctx.definition.projectileDiameterM
        val theta: Double = ctx.angleFromNormalRad
        val cosTheta: Double = max(cos(theta), MIN_COS)
        val sinTheta: Double = sin(theta)

        /** Normal speed of the plates, with the energy cap applied: positive numbers, front towards +n, back towards -n. */
        val frontSpeedMps: Double
        val backSpeedMps: Double
        val plateEnergyJ: Double

        init {
            val loadedRadius = diameterM / 2.0 + p.plateSpreadRatio * elastomerLayer.thicknessM
            val patch = PI * loadedRadius * loadedRadius
            val k = p.cavityExpansionRatio * p.elastomerWaveSpeedMps * elastomer.densityKgM3 * elastomerLayer.thicknessM / cosTheta
            var vf = k / (front.densityKgM3 * frontLayer.thicknessM)
            var vb = k / (back.densityKgM3 * backLayer.thicknessM)
            val mf = front.densityKgM3 * frontLayer.thicknessM * patch
            val mb = back.densityKgM3 * backLayer.thicknessM * patch
            var e = 0.5 * mf * vf * vf + 0.5 * mb * vb * vb
            val cap = p.energyCapFraction * projectileEnergyJ
            if (e > cap) {
                val s = if (e > 0.0) sqrt(cap / e) else 0.0
                vf *= s
                vb *= s
                e = cap
            }
            frontSpeedMps = vf
            backSpeedMps = vb
            plateEnergyJ = e
        }

        /** Where the projectile went into the front plate, walked back along the line of flight by [depthM] of real thickness. */
        fun entryPoint(at: V3, direction: V3, depthM: Double): V3 = at - direction.scale(depthM / cosTheta)

        /** Share of the penetrator's own mass that the plate patch it touches is worth, i.e. how well the plate grips the rod. */
        fun coupling(plateDensity: Double, plateThicknessM: Double, rodMassPerLosM: Double, losM: Double): Double {
            val plateMass = plateDensity * plateThicknessM * PI * diameterM * diameterM
            val rodMass = rodMassPerLosM * losM
            return plateMass / (plateMass + rodMass)
        }

        companion object {
            const val MIN_COS = 0.05
        }
    }

    private fun sandwich(ctx: ImpactContext, pack: ArmorElement.EffectPackage, p: NeraParameters, energyJ: Double) = Sandwich(ctx, pack, p, energyJ)

    /** How much of the sandwich is still working at [entry]: 0 inside an existing tear, 1 well clear of all of them. */
    private fun effectiveness(invocation: EffectInvocation, entry: V3): Double {
        var e = 1.0
        for (tear in invocation.runtime.tearsOf(invocation.slot)) {
            val q = tear.normalizedDistance(entry)
            e *= when {
                q <= 1.0 -> 0.0
                q >= RIM_FACTOR -> 1.0
                else -> (q - 1.0) / (RIM_FACTOR - 1.0)
            }
        }
        return e
    }

    // ---- BEFORE the back plate: the moving plates work on the projectile ------------------------------------------------

    private fun disrupt(invocation: EffectInvocation, ctx: ImpactContext, p: NeraParameters): EffectInteractionResult {
        val projectile = ctx.projectile
        val s = sandwich(ctx, invocation.pack, p, projectile.kineticEnergyJ)
        val d = projectile.velocity.normalize()
        val entry = s.entryPoint(ctx.impactPoint, d, s.frontLayer.thicknessM + s.elastomerLayer.thicknessM)
        val working = effectiveness(invocation, entry)
        if (working <= 0.0 || s.plateEnergyJ <= 0.0) return EffectInteractionResult.untouched()

        val vf = s.frontSpeedMps * working
        val vb = s.backSpeedMps * working
        val speed = projectile.speedMps

        // Sideways direction: the part of the plate normal that is across the flight line (zero length for a head-on hit).
        val across = s.n - d.scale(s.n.dot(d))
        val lateral = if (across.length() < 1e-9) V3.ZERO else across.normalize()

        // Mass per metre of line of flight, over the length of the penetrator that is inside the sandwich at once.
        val rodLengthM = projectile.penetratorLengthRemainingM ?: (2.0 * s.diameterM)
        val insideLosM = (s.elastomerLayer.thicknessM + s.backLayer.thicknessM) / s.cosTheta
        val massPerLos = projectile.massRemainingKg / max(rodLengthM, 1e-6)
        val couplingBack = s.coupling(s.back.densityKgM3, s.backLayer.thicknessM, massPerLos, min(insideLosM, rodLengthM))
        val couplingFront = s.coupling(s.front.densityKgM3, s.frontLayer.thicknessM, massPerLos, min(insideLosM, rodLengthM))
        val uBack = couplingBack * vb * s.sinTheta   // head dragged one way
        val uFront = couplingFront * vf * s.sinTheta // tail dragged the other way

        // Net sideways push of the whole rod, and the couple that yaws it.
        val turn = lateral.scale(0.5 * (uFront - uBack))
        val yaw = atan2(uBack + uFront, speed)

        // Energy: plates first (scaled by how much of the sandwich was really working), then share the rest.
        val plateJ = s.plateEnergyJ * working * working
        val availableJ = projectile.kineticEnergyJ - plateJ
        val u0 = speed * sqrt(max(availableJ, 0.0) / projectile.kineticEnergyJ)

        val severity = (uBack + uFront) / p.lateralFailureMps
        val broken = (1.0 - exp(-p.disruptionCoefficient * severity * severity)).coerceIn(0.0, 1.0)

        val newDir = (d.scale(speed) + turn).normalize().let { if (it.lengthSqr() == 0.0) d else it }
        val debris = if (broken > 1e-9) {
            val mass = projectile.massRemainingKg * broken
            val debrisSpeed = p.debrisSpeedRatio * u0
            val energy = 0.5 * mass * debrisSpeed * debrisSpeed
            if (energy > 0.0) {
                val half = min(PI / 2.0, atan2(uBack + uFront, max(u0, 1e-9)) + FRAGMENT_BASE_HALF_ANGLE_RAD)
                SpallSource(ctx.impactPoint, newDir, half, energy, mass, ctx.definition.penetrator?.material ?: s.frontLayer.materialId)
            } else null
        } else null

        if (broken >= DESTROYED_AT) {
            return EffectInteractionResult.destroyed(spall = listOfNotNull(debris))
        }
        val keep = 1.0 - broken
        val axis = (projectile.axis.scale(cos(yaw)) - lateral.scale(sin(yaw))).normalize().let { if (it.lengthSqr() == 0.0) projectile.axis else it }
        val changed = projectile.copy(
            velocity = newDir.scale(u0),
            axis = axis,
            massRemainingKg = projectile.massRemainingKg * keep,
            penetratorLengthRemainingM = projectile.penetratorLengthRemainingM?.times(keep),
            integrity = projectile.integrity * keep,
            deformation = projectile.deformation + (1.0 - projectile.deformation) * broken,
        )
        return EffectInteractionResult.replaced(changed, spall = listOfNotNull(debris))
    }

    // ---- AFTER the back plate: the sandwich is torn; a perforated one throws plate pieces off its back -------------------

    private fun rupture(invocation: EffectInvocation, ctx: ImpactContext, p: NeraParameters, layerResult: PenetrationResult): EffectInteractionResult {
        val perforated = layerResult.outcome == PenetrationOutcome.PERFORATED
        val ricochet = layerResult.outcome == PenetrationOutcome.RICOCHET
        // The projectile energy the plates saw: what it has now plus what the back plate took (nothing, if it did not get through).
        val energyBefore = ctx.projectile.kineticEnergyJ +
            (if (perforated) layerResult.depositedEnergyJ + (layerResult.spall?.energyJ ?: 0.0) else 0.0)
        val s = sandwich(ctx, invocation.pack, p, energyBefore)
        val sandwichDepth = s.frontLayer.thicknessM + s.elastomerLayer.thicknessM + (if (perforated) s.backLayer.thicknessM else 0.0)
        val flight = if (ricochet) s.n.scale(-1.0) else ctx.projectile.velocity.normalize()
        val entry = if (ricochet) ctx.impactPoint + s.n.scale(sandwichDepth) else s.entryPoint(ctx.impactPoint, flight, sandwichDepth)

        val working = effectiveness(invocation, entry)
        if (working <= 0.0 || s.plateEnergyJ <= 0.0) return EffectInteractionResult.untouched()

        // Bulge: height of the back plate dome after the projectile has crossed elastomer and plate, and its strain.
        val vb = s.backSpeedMps * working
        val speed = max(sqrt(2.0 * energyBefore / max(ctx.projectile.massRemainingKg, 1e-9)), 1.0)
        val crossingS = (s.elastomerLayer.thicknessM + s.backLayer.thicknessM) / s.cosTheta / speed
        val height = vb * crossingS
        val radius = s.diameterM / 2.0 + p.plateSpreadRatio * s.elastomerLayer.thicknessM
        val strain = 2.0 / 3.0 * (height / radius) * (height / radius)
        val severity = min(strain / p.tearStrain, MAX_TEAR_SEVERITY)

        // The hole grows from the penetrator's radius up to several times it as the plate petals; an oblique hit tears a slot.
        val holeRadius = s.diameterM / 2.0 * (1.0 + severity)
        val minor = holeRadius
        val major = holeRadius / s.cosTheta
        val inPlane = flight - s.n.scale(flight.dot(s.n))
        val majorAxis = if (inPlane.length() < 1e-6) perpendicularTo(s.n) else inPlane.normalize()
        val tear = ArmorTear(entry, s.n, majorAxis, major, minor)

        if (!perforated) return EffectInteractionResult.untouched(tears = listOf(tear)) // an observer: it has no energy to spend

        // Plug and petals off the back, carried by the plate's own motion as well as by the projectile: velocity adds as a vector.
        val residual = ctx.projectile
        val coefficient = if (s.back.spall.enabled) s.back.spall.coefficient else 0.0
        val pieceEnergy = min(p.plateSpallEnergyFraction * coefficient * residual.kineticEnergyJ, residual.kineticEnergyJ)
        val pieceMass = s.back.densityKgM3 * s.backLayer.thicknessM * PI * holeRadius * holeRadius
        if (pieceEnergy <= 0.0 || pieceMass <= 0.0) return EffectInteractionResult.untouched(tears = listOf(tear))

        val push = residual.velocity + s.n.scale(-vb)
        val axis = push.normalize().let { if (it.lengthSqr() == 0.0) flight else it }
        val half = min(PI / 2.0, atan2(vb, max(residual.speedMps, 1e-9)) + FRAGMENT_BASE_HALF_ANGLE_RAD + 0.5 * s.theta)
        val spall = SpallSource(ctx.impactPoint, axis, half, pieceEnergy, pieceMass, s.backLayer.materialId)
        val slowed = residual.copy(velocity = residual.velocity.scale(sqrt(1.0 - pieceEnergy / residual.kineticEnergyJ)))
        return EffectInteractionResult.replaced(slowed, spall = listOf(spall), tears = listOf(tear))
    }

    private fun perpendicularTo(n: V3): V3 {
        val helper = if (kotlin.math.abs(n.x) < 0.9) V3(1.0, 0.0, 0.0) else V3(0.0, 1.0, 0.0)
        return n.cross(helper).normalize()
    }

    companion object {
        val ID = Ident("dncity", "nera_bulge")

        /** A hit this many tear-radii from the middle of a tear sees a fully working sandwich again. */
        const val RIM_FACTOR = 1.5

        /** Above this much broken off the projectile is counted as destroyed, not as a lighter projectile. */
        const val DESTROYED_AT = 0.995

        const val MAX_TEAR_SEVERITY = 4.0
        private const val FRAGMENT_BASE_HALF_ANGLE_RAD = 0.087 // 5 degrees
    }
}
