// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.aircraft

import io.github.jwyoon1220.dncity.ballistics.flight.Atmosphere
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A single main rotor helicopter, modelled with momentum theory (Glauert's induced-velocity relation for forward and climbing flight,
 * the textbook model of Leishman, *Principles of Helicopter Aerodynamics*, ch. 2-5):
 *
 * - The engines' power (less the tail rotor's share) drives the rotor; the thrust that power can hold is solved from
 *   `P FM = T (v_i + V_c)`, with `v_i` from `v_i = v_h^2 / sqrt(V_x^2 + (V_c + v_i)^2)`. Forward speed lowers `v_i`, so the same power
 *   holds more thrust: effective translational lift falls out of this, it is not added on.
 * - Collective picks a share of that thrust. Thrust acts along the body's up axis (the rotor disc tilts with the cyclic).
 * - Ground effect: Cheeseman & Bennett (1955), `T_IGE / T_OGE = 1 / (1 - (R / 4z)^2)`, faded out with forward speed.
 * - Vortex ring state (War Thunder has a "VortexRing" block; the model here is our own): descending into the own wake at low forward
 *   speed loses thrust, worst near a descent rate of the hover induced velocity.
 * - Fuselage drag through two equivalent flat-plate areas, for flow along the disc and through it.
 */
data class HelicopterSpec(
    val massKg: Double,
    val rotorRadiusM: Double,
    val enginePowerW: Double,
    /** Turboshaft power lapse with density, `P = P0 sigma^n`. */
    val powerDensityExponent: Double,
    val tailRotorPowerFraction: Double,
    val figureOfMerit: Double,
    val dragAreaM2: Double,
    val verticalDragAreaM2: Double,
    val vortexRingMaxThrustLoss: Double,
    val limits: StructuralLimits,
) {
    init {
        require(massKg > 0.0 && rotorRadiusM > 0.0 && enginePowerW > 0.0) { "mass, rotor radius and power must be positive" }
        require(powerDensityExponent >= 0.0 && tailRotorPowerFraction in 0.0..0.5) { "bad power lapse/tail rotor share" }
        require(figureOfMerit in 0.4..0.9) { "figure of merit must be within 0.4..0.9" }
        require(dragAreaM2 > 0.0 && verticalDragAreaM2 > 0.0) { "drag areas must be positive" }
        require(vortexRingMaxThrustLoss in 0.0..0.9) { "vortex ring loss must be within 0..0.9" }
    }

    val discAreaM2: Double get() = PI * rotorRadiusM * rotorRadiusM
}

data class HelicopterStep(
    val velocityMps: V3,
    val thrustN: Double,
    val availableThrustN: Double,
    val hoverInducedVelocityMps: Double,
    val groundEffect: Double,
    val vortexRing: Double,
    val iasMps: Double,
    val loadFactor: Double,
    val failure: StructuralFailure,
)

object HelicopterSim {
    const val SUBSTEPS = 4
    private const val G = Atmosphere.Isa.G

    /** Shaft power available for the main rotor at [densityKgM3]. */
    fun rotorPowerW(spec: HelicopterSpec, densityKgM3: Double): Double =
        spec.enginePowerW * (densityKgM3 / Powerplant.SEA_LEVEL_DENSITY).pow(spec.powerDensityExponent) * (1.0 - spec.tailRotorPowerFraction)

    /** Induced velocity for thrust [thrustN], in-plane speed [vx] and climb speed through the disc [vc] (>= 0), by fixed-point iteration. */
    fun inducedVelocity(spec: HelicopterSpec, thrustN: Double, densityKgM3: Double, vx: Double, vc: Double): Double {
        if (thrustN <= 0.0) return 0.0
        val vh2 = thrustN / (2.0 * densityKgM3 * spec.discAreaM2)
        var vi = sqrt(vh2)
        repeat(30) {
            val next = vh2 / sqrt(vx * vx + (vc + vi) * (vc + vi))
            vi = 0.5 * (vi + next)
        }
        return vi
    }

    /** The most thrust [powerW] can hold at this flow (bisection on `P FM = T (v_i + V_c)`). */
    fun availableThrustN(spec: HelicopterSpec, powerW: Double, densityKgM3: Double, vx: Double, vc: Double): Double {
        if (powerW <= 0.0) return 0.0
        var lo = 0.0
        var hi = spec.massKg * G * 10.0
        repeat(50) {
            val mid = 0.5 * (lo + hi)
            val need = mid * (inducedVelocity(spec, mid, densityKgM3, vx, vc) + vc) / spec.figureOfMerit
            if (need > powerW) hi = mid else lo = mid
        }
        return lo
    }

    /** Cheeseman & Bennett ground effect for the rotor [heightM] above the ground, faded out above twice the hover induced velocity. */
    fun groundEffect(spec: HelicopterSpec, heightM: Double, vx: Double, vh: Double): Double {
        val z = heightM.coerceAtLeast(0.5 * spec.rotorRadiusM)
        val k = 1.0 / (1.0 - (spec.rotorRadiusM / (4.0 * z)).pow(2))
        val fade = if (vh <= 0.0) 0.0 else (1.0 - vx / (2.0 * vh)).coerceIn(0.0, 1.0)
        return 1.0 + (k - 1.0) * fade
    }

    /** 0..1 how deep in the vortex ring state: descent rate around 0.3..1.6 times the hover induced velocity, little forward speed. */
    fun vortexRing(descentMps: Double, vx: Double, vh: Double): Double {
        if (vh <= 0.0 || descentMps <= 0.0) return 0.0
        val d = descentMps / vh
        val depth = (1.0 - abs(d - 0.95) / 0.65).coerceIn(0.0, 1.0)
        val forward = (1.0 - vx / (0.7 * vh)).coerceIn(0.0, 1.0)
        return depth * forward
    }

    /**
     * One tick. [collective] 0..1 is the share of the available thrust asked for; [rotorHeightM] is the rotor hub's height above the
     * ground below it (`Double.POSITIVE_INFINITY` when out of ground effect).
     */
    fun step(spec: HelicopterSpec, state: AircraftState, collective: Double, rotorHeightM: Double, dtS: Double): HelicopterStep {
        val rho = FixedWingSim.density(state.altitudeM)
        val power = rotorPowerW(spec, rho)
        var v = state.velocityMps
        var last = HelicopterStep(v, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, StructuralFailure.NONE)
        val sub = dtS / SUBSTEPS
        for (i in 0 until SUBSTEPS) {
            val axial = v.dot(state.up)
            val inPlane = v.perpendicularTo(state.up)
            val vx = inPlane.length()
            val available = availableThrustN(spec, power, rho, vx, axial.coerceAtLeast(0.0))
            val hoverVh = sqrt(spec.massKg * G / (2.0 * rho * spec.discAreaM2))
            val ge = groundEffect(spec, rotorHeightM, vx, hoverVh)
            val vrs = vortexRing(-axial, vx, hoverVh)
            val thrust = available * collective.coerceIn(0.0, 1.0) * ge * (1.0 - spec.vortexRingMaxThrustLoss * vrs)
            var force = V3(0.0, -spec.massKg * G, 0.0) + state.up.scale(thrust)
            force += inPlane.scale(-0.5 * rho * vx * spec.dragAreaM2)
            force += state.up.scale(-0.5 * rho * axial * abs(axial) * spec.verticalDragAreaM2)
            v += force.scale(sub / spec.massKg)
            last = HelicopterStep(v, thrust, available, hoverVh, ge, vrs, 0.0, thrust / (spec.massKg * G), StructuralFailure.NONE)
        }
        val ias = v.length() * sqrt(rho / Powerplant.SEA_LEVEL_DENSITY)
        val failure = when {
            last.loadFactor > spec.limits.failureLoadPos -> StructuralFailure.WING_OVERLOAD
            ias > spec.limits.vneIasMps * FixedWingSim.VNE_FAILURE_MARGIN -> StructuralFailure.OVERSPEED
            else -> StructuralFailure.NONE
        }
        return last.copy(iasMps = ias, failure = failure)
    }

    /**
     * Best steady climb rate at [altitudeM], m/s: the largest spare power over weight across forward speeds, with the power needed
     * for level flight at each speed from the same momentum-theory balance as [deriveDragArea].
     */
    fun maxClimbRateMps(spec: HelicopterSpec, altitudeM: Double): Double {
        val rho = FixedWingSim.density(altitudeM)
        val power = rotorPowerW(spec, rho)
        val w = spec.massKg * G
        var best = 0.0
        var v = 0.0
        while (v <= 120.0) {
            val drag = 0.5 * rho * v * v * spec.dragAreaM2
            val thrust = sqrt(w * w + drag * drag)
            val need = thrust * inducedVelocity(spec, thrust, rho, v * w / thrust, 0.0) / spec.figureOfMerit + drag * v
            best = maxOf(best, (power - need) / w)
            v += 1.0
        }
        return best
    }

    /**
     * Fits the engine power (scale 0.3..2 of the given figure) and the drag area together so the model climbs at [climbMps] and tops
     * out at [maxSpeedMps]: each drag area is re-derived for the current power, then the power scale is bisected on the climb. The
     * figure of merit stays as given (a property of the rotor, not of the game balance).
     */
    fun fitClimbAndTopSpeed(spec: HelicopterSpec, climbMps: Double, maxSpeedMps: Double, altitudeM: Double): HelicopterSpec {
        fun fitted(k: Double): HelicopterSpec {
            val s = spec.copy(enginePowerW = spec.enginePowerW * k)
            return s.copy(dragAreaM2 = deriveDragArea(s, maxSpeedMps, altitudeM))
        }
        var a = 0.3
        var b = 2.0
        while (runCatching { fitted(a) }.isFailure && a < b) a *= 1.1
        if (maxClimbRateMps(fitted(a), altitudeM) >= climbMps) return fitted(a)
        if (maxClimbRateMps(fitted(b), altitudeM) <= climbMps) return fitted(b)
        repeat(40) {
            val mid = 0.5 * (a + b)
            if (maxClimbRateMps(fitted(mid), altitudeM) < climbMps) a = mid else b = mid
        }
        return fitted(b)
    }

    /**
     * Raises the engine power, if needed, until a hover out of ground effect at [altitudeM] takes no more than [collective] of the
     * available thrust (War Thunder's helicopters hover with margin even where their published climb rate alone would not give the
     * power for it); the drag area is re-derived for the new power so the top speed holds. Never lowers the power.
     */
    fun fitHoverMargin(spec: HelicopterSpec, collective: Double, altitudeM: Double, maxSpeedMps: Double, maxSpeedAltitudeM: Double): HelicopterSpec {
        val rho = FixedWingSim.density(altitudeM)
        val needed = spec.massKg * G / collective
        fun thrust(k: Double) = spec.copy(enginePowerW = spec.enginePowerW * k).let { availableThrustN(it, rotorPowerW(it, rho), rho, 0.0, 0.0) }
        if (thrust(1.0) >= needed) return spec
        var a = 1.0
        var b = 4.0
        repeat(50) {
            val mid = 0.5 * (a + b)
            if (thrust(mid) < needed) a = mid else b = mid
        }
        val s = spec.copy(enginePowerW = spec.enginePowerW * b)
        return s.copy(dragAreaM2 = deriveDragArea(s, maxSpeedMps, maxSpeedAltitudeM))
    }

    /**
     * The fuselage flat-plate area that makes [maxSpeedMps] at [altitudeM] the top speed in level flight: at full power, the thrust
     * that holds both weight and drag, `T = sqrt(W^2 + D^2)` (disc tilted forward by `atan(D / W)`), needs exactly the available power
     * once the drag's own power `D V` is taken out of it. Solved by bisection, so a sourced top speed sets the drag instead of a guess.
     */
    fun deriveDragArea(spec: HelicopterSpec, maxSpeedMps: Double, altitudeM: Double): Double {
        val rho = FixedWingSim.density(altitudeM)
        val power = rotorPowerW(spec, rho)
        val w = spec.massKg * G
        fun spare(f: Double): Double {
            val drag = 0.5 * rho * maxSpeedMps * maxSpeedMps * f
            val thrust = sqrt(w * w + drag * drag)
            // The flow's component along the disc; the drag's power is counted separately as D V
            val vxDisc = maxSpeedMps * w / thrust
            val need = thrust * inducedVelocity(spec, thrust, rho, vxDisc, 0.0) / spec.figureOfMerit + drag * maxSpeedMps
            return power - need
        }
        var lo = 1e-3
        var hi = 50.0
        require(spare(lo) > 0.0) { "top speed $maxSpeedMps m/s is not reachable with this power even without drag" }
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            if (spare(mid) > 0.0) lo = mid else hi = mid
        }
        return lo
    }
}
