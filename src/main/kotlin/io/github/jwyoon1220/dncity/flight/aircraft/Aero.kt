// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.aircraft

import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Aerodynamic building blocks shared by the fixed-wing and helicopter models. Plain JVM, SI units, no Minecraft types.
 *
 * The *structure* follows how War Thunder organizes a flight model (read from its configuration key names only: a per-
 * configuration wing polar with a linear range, critical angles of attack and a post-stall branch, a clean and a flaps polar
 * blended by flap position, Mach drag rise, control effectiveness that changes with speed, structural load and speed limits).
 * No War Thunder number, table or code is used; the formulas are textbook ones named at each place.
 */

internal fun V3.cross(o: V3): V3 = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

/** Component of this vector perpendicular to the unit vector [n]. */
internal fun V3.perpendicularTo(n: V3): V3 = this - n.scale(this.dot(n))

/** Lift-curve slope of a finite wing, per radian: Helmbold's form, `a = 2 pi AR / (2 + sqrt(AR^2 + 4))` (USAF DATCOM's low-speed limit). */
fun finiteWingLiftSlope(aspectRatio: Double): Double {
    require(aspectRatio > 0.0 && aspectRatio.isFinite()) { "aspect ratio must be positive" }
    return 2.0 * PI * aspectRatio / (2.0 + sqrt(aspectRatio * aspectRatio + 4.0))
}

private fun smoothstep(t: Double): Double {
    val c = t.coerceIn(0.0, 1.0)
    return c * c * (3.0 - 2.0 * c)
}

/**
 * Lift and drag of the whole aircraft against angle of attack (radians, positive nose up relative to the flight path).
 *
 * Linear lift `cl0 + a * alpha` between the critical angles where it reaches [clMax] / [clMin]; past them the flow separates and the
 * coefficients blend over [stallWidthRad] into those of a flat plate (`Cl = 2 sin a cos a`, `Cd = 2 sin^2 a`, the post-stall limit used by
 * Viterna & Corrigan, 1982), so lift collapses and drag climbs. Attached-flow drag is `cdMin + Cl^2 / (pi e AR)` (parabolic polar).
 */
data class WingPolar(
    val liftSlopePerRad: Double,
    val cl0: Double,
    val clMax: Double,
    val clMin: Double,
    val cdMin: Double,
    val oswald: Double,
    val aspectRatio: Double,
    val stallWidthRad: Double,
) {
    init {
        require(liftSlopePerRad > 0.0) { "lift slope must be positive" }
        require(clMax > cl0 && clMin < cl0) { "cl_max must be above cl0 and cl_min below it" }
        require(cdMin > 0.0) { "cd_min must be positive" }
        require(oswald in 0.3..1.0) { "Oswald efficiency must be within 0.3..1" }
        require(aspectRatio > 0.0) { "aspect ratio must be positive" }
        require(stallWidthRad > 0.0) { "stall width must be positive" }
    }

    val aoaCritHighRad: Double get() = (clMax - cl0) / liftSlopePerRad
    val aoaCritLowRad: Double get() = (clMin - cl0) / liftSlopePerRad

    /** 0 in attached flow, rising to 1 when the flow is fully separated. */
    fun separation(aoaRad: Double): Double = when {
        aoaRad > aoaCritHighRad -> smoothstep((aoaRad - aoaCritHighRad) / stallWidthRad)
        aoaRad < aoaCritLowRad -> smoothstep((aoaCritLowRad - aoaRad) / stallWidthRad)
        else -> 0.0
    }

    fun isStalled(aoaRad: Double): Boolean = aoaRad > aoaCritHighRad || aoaRad < aoaCritLowRad

    fun cl(aoaRad: Double): Double {
        val attached = (cl0 + liftSlopePerRad * aoaRad).coerceIn(clMin, clMax)
        val s = separation(aoaRad)
        return if (s == 0.0) attached else attached * (1.0 - s) + 2.0 * sin(aoaRad) * cos(aoaRad) * s
    }

    fun cd(aoaRad: Double): Double {
        val attachedCl = (cl0 + liftSlopePerRad * aoaRad).coerceIn(clMin, clMax)
        val attached = cdMin + attachedCl * attachedCl / (PI * oswald * aspectRatio)
        val s = separation(aoaRad)
        if (s == 0.0) return attached
        val flat = cdMin + 2.0 * sin(aoaRad).pow(2)
        return attached * (1.0 - s) + maxOf(attached, flat) * s
    }

    /** Angle of attack giving [cl] in the linear range (for trim and derivations). */
    fun aoaForCl(cl: Double): Double = (cl - cl0) / liftSlopePerRad

    /** This polar with flaps out by [fraction] (0..1) of [flaps]: War Thunder's "NoFlaps/FullFlaps" blending idea. */
    fun withFlaps(flaps: FlapsEffect?, fraction: Double): WingPolar {
        if (flaps == null || fraction <= 0.0) return this
        val f = fraction.coerceIn(0.0, 1.0)
        return copy(cl0 = cl0 + flaps.deltaCl0 * f, clMax = clMax + flaps.deltaClMax * f, cdMin = cdMin + flaps.deltaCdMin * f)
    }
}

/** What full flaps add to the clean polar. */
data class FlapsEffect(val deltaCl0: Double, val deltaClMax: Double, val deltaCdMin: Double) {
    init {
        require(deltaCl0 >= 0.0 && deltaClMax >= 0.0 && deltaCdMin >= 0.0) { "flaps add lift and drag, never remove them" }
    }
}

/**
 * Compressibility drag rise. Below [criticalMach] nothing; up to Mach 1.05 it climbs as `((M - Mcr) / (1.05 - Mcr))^2` to [peakCd];
 * above, it falls off like the supersonic wave drag of a thin body, `peak * sqrt((1.05^2 - 1) / (M^2 - 1))`.
 */
data class MachDrag(val criticalMach: Double, val peakCd: Double) {
    init {
        require(criticalMach in 0.3..1.0) { "critical Mach must be within 0.3..1" }
        require(peakCd >= 0.0) { "wave drag peak must not be negative" }
    }

    fun cd(mach: Double): Double = when {
        mach <= criticalMach -> 0.0
        mach <= PEAK_MACH -> peakCd * ((mach - criticalMach) / (PEAK_MACH - criticalMach)).pow(2)
        else -> peakCd * sqrt((PEAK_MACH * PEAK_MACH - 1.0) / (mach * mach - 1.0))
    }

    companion object {
        const val PEAK_MACH = 1.05
    }
}

/** What makes the aircraft go forward. [throttle] 0..1 is normal power, [wep] 0..1 the emergency range on top of it. */
sealed interface Powerplant {
    /** Seconds the engine needs to follow a throttle change (War Thunder's engine inertia idea): a first-order lag. */
    val spoolTimeS: Double

    fun thrustN(throttle: Double, wep: Double, speedMps: Double, densityKgM3: Double, mach: Double): Double

    /** The same engine with every thrust (or power) figure times [factor]: what fitting to a published climb rate adjusts. */
    fun scaled(factor: Double): Powerplant

    /**
     * Turbojet / turbofan: static thrust at sea level, scaled by density `sigma^n` (the usual thrust-lapse form, Mattingly) and a linear
     * loss with Mach. [wepThrustN] is the afterburner (or emergency) thrust reached at full [wep].
     */
    data class Jet(
        val count: Int,
        val staticThrustN: Double,
        val wepThrustN: Double?,
        val densityExponent: Double,
        val machLapse: Double,
        override val spoolTimeS: Double,
    ) : Powerplant {
        init {
            require(count >= 1 && staticThrustN > 0.0) { "jet needs at least one engine with thrust" }
            require(wepThrustN == null || wepThrustN >= staticThrustN) { "WEP thrust must not be below normal thrust" }
            require(densityExponent >= 0.0 && machLapse in 0.0..1.0 && spoolTimeS > 0.0) { "bad jet lapse/spool values" }
        }

        override fun scaled(factor: Double): Powerplant = copy(staticThrustN = staticThrustN * factor, wepThrustN = wepThrustN?.times(factor))

        override fun thrustN(throttle: Double, wep: Double, speedMps: Double, densityKgM3: Double, mach: Double): Double {
            val perEngine = staticThrustN * throttle.coerceIn(0.0, 1.0) + ((wepThrustN ?: staticThrustN) - staticThrustN) * wep.coerceIn(0.0, 1.0)
            val lapse = (densityKgM3 / SEA_LEVEL_DENSITY).pow(densityExponent) * (1.0 - machLapse * mach).coerceAtLeast(0.0)
            return count * perEngine * lapse
        }
    }

    /**
     * Piston or turboprop engine turning a propeller: thrust is `eta P / V` in flight, capped at the static thrust of an actuator disc of
     * the propeller's diameter (momentum theory, `T = (FM P)^(2/3) (2 rho A)^(1/3)` with figure of merit [staticFigureOfMerit]). Power
     * falls with density as `sigma` (an unsupercharged engine; supercharging is not modelled).
     */
    data class Propeller(
        val count: Int,
        val powerW: Double,
        val wepPowerW: Double?,
        val efficiency: Double,
        val diameterM: Double,
        val staticFigureOfMerit: Double,
        override val spoolTimeS: Double,
    ) : Powerplant {
        init {
            require(count >= 1 && powerW > 0.0 && diameterM > 0.0) { "propeller needs power and a diameter" }
            require(wepPowerW == null || wepPowerW >= powerW) { "WEP power must not be below normal power" }
            require(efficiency in 0.3..0.95 && staticFigureOfMerit in 0.3..0.95 && spoolTimeS > 0.0) { "bad propeller efficiency/spool values" }
        }

        override fun scaled(factor: Double): Powerplant = copy(powerW = powerW * factor, wepPowerW = wepPowerW?.times(factor))

        override fun thrustN(throttle: Double, wep: Double, speedMps: Double, densityKgM3: Double, mach: Double): Double {
            val power = (powerW * throttle.coerceIn(0.0, 1.0) + ((wepPowerW ?: powerW) - powerW) * wep.coerceIn(0.0, 1.0)) *
                densityKgM3 / SEA_LEVEL_DENSITY
            if (power <= 0.0) return 0.0
            val area = PI / 4.0 * diameterM * diameterM
            val static = (staticFigureOfMerit * power).pow(2.0 / 3.0) * (2.0 * densityKgM3 * area).pow(1.0 / 3.0)
            val flight = efficiency * power / speedMps.coerceAtLeast(1.0)
            return count * minOf(static, flight)
        }
    }

    companion object {
        const val SEA_LEVEL_DENSITY = 1.225
    }
}

/**
 * How well the control surfaces work at an indicated airspeed: proportional to dynamic pressure up to [fullAuthorityIasMps], full in
 * between, then falling linearly to [compressionFloor] at [compressionEndIasMps] (control stiffening at high speed, War Thunder's
 * "effective speed" idea), never below [minimum] (so a parked aircraft can still be pointed).
 */
data class ControlAuthority(
    val fullAuthorityIasMps: Double,
    val compressionStartIasMps: Double,
    val compressionEndIasMps: Double,
    val compressionFloor: Double,
    val minimum: Double = 0.05,
) {
    init {
        require(fullAuthorityIasMps > 0.0 && compressionStartIasMps >= fullAuthorityIasMps && compressionEndIasMps > compressionStartIasMps) {
            "control speeds must rise: full authority <= compression start < compression end"
        }
        require(compressionFloor in 0.0..1.0) { "compression floor must be within 0..1" }
    }

    fun at(iasMps: Double): Double {
        val low = (iasMps / fullAuthorityIasMps).pow(2).coerceAtMost(1.0)
        val high = if (iasMps <= compressionStartIasMps) 1.0 else {
            val t = ((iasMps - compressionStartIasMps) / (compressionEndIasMps - compressionStartIasMps)).coerceIn(0.0, 1.0)
            1.0 - (1.0 - compressionFloor) * t
        }
        return (low * high).coerceAtLeast(minimum)
    }
}

/** Structural limits. Failure is at [limitLoadPos] / [limitLoadNeg] times [ultimateFactor] (limit vs ultimate load, 1.5 in MIL-A-8860 and 14 CFR 25.303). */
data class StructuralLimits(
    val limitLoadPos: Double,
    val limitLoadNeg: Double,
    val ultimateFactor: Double,
    val vneIasMps: Double,
    val flapsMaxIasMps: Double?,
    val gearMaxIasMps: Double?,
    /** Mach number limit (War Thunder's "VneMach" idea), null when only the indicated airspeed limits it. */
    val vneMach: Double? = null,
) {
    init {
        require(limitLoadPos > 1.0 && limitLoadNeg < 0.0) { "load limits must be > 1 and < 0" }
        require(ultimateFactor >= 1.0 && vneIasMps > 0.0) { "ultimate factor >= 1 and a positive Vne" }
        require(vneMach == null || vneMach > 0.0) { "Mach limit must be positive" }
    }

    val failureLoadPos: Double get() = limitLoadPos * ultimateFactor
    val failureLoadNeg: Double get() = limitLoadNeg * ultimateFactor
}
