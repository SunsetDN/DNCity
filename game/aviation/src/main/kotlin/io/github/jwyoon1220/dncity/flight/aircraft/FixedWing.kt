// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.aircraft

import io.github.jwyoon1220.dncity.ballistics.flight.Atmosphere
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/** Body rates and stability terms of a fixed-wing aircraft. Roll and yaw rates are the most the controls command at full authority. */
data class FixedWingHandling(
    val maxRollRateDegS: Double,
    /** Fastest the nose moves relative to the flight path (how fast the angle of attack can change), deg/s. */
    val maxPitchRateDegS: Double,
    val maxYawRateDegS: Double,
    /** Nose-into-wind (fin) yaw rate per radian of sideslip, 1/s. */
    val yawStabilityPerS: Double,
    /** How fast the angle of attack closes on the one the stick commands, 1/s (pitch stiffness: fighters fast, transports slow). */
    val aoaResponsePerS: Double,
    /** How fast the nose falls when fully stalled, deg/s. */
    val stallNoseDropDegS: Double,
    /** Side force coefficient per radian of sideslip (fuselage + fin), referenced to the wing area. */
    val sideForcePerRad: Double,
    /** The instructor keeps the angle of attack below this share of the critical one. */
    val instructorAoaMargin: Double,
) {
    init {
        require(maxRollRateDegS > 0.0 && maxPitchRateDegS > 0.0 && maxYawRateDegS > 0.0) { "rates must be positive" }
        require(yawStabilityPerS >= 0.0 && aoaResponsePerS > 0.0 && stallNoseDropDegS >= 0.0 && sideForcePerRad >= 0.0) { "stability terms must not be negative" }
        require(instructorAoaMargin in 0.5..1.0) { "instructor AoA margin must be within 0.5..1" }
    }
}

/** Wheels on the ground: rolling friction coefficient, with the brakes on, and the most sideways grip (all times the normal force). */
data class GroundHandling(val rollingFriction: Double, val brakeFriction: Double, val lateralFriction: Double) {
    init {
        require(rollingFriction in 0.0..1.0 && brakeFriction in rollingFriction..1.5 && lateralFriction in 0.0..2.0) { "bad ground friction values" }
    }
}

data class FixedWingSpec(
    val massKg: Double,
    val wingAreaM2: Double,
    val polar: WingPolar,
    val flaps: FlapsEffect?,
    val mach: MachDrag,
    /** Extra drag coefficients (wing-area referenced) of the gear fully down and the airbrake out. */
    val gearCd: Double,
    val airbrakeCd: Double,
    val engine: Powerplant,
    val authority: ControlAuthority,
    val handling: FixedWingHandling,
    val limits: StructuralLimits,
    val ground: GroundHandling,
) {
    init {
        require(massKg > 0.0 && wingAreaM2 > 0.0) { "mass and wing area must be positive" }
        require(gearCd >= 0.0 && airbrakeCd >= 0.0) { "gear/airbrake drag must not be negative" }
    }
}

/** Pilot (or instructor) commands, each -1..1 where signed: pitch + nose up, roll + right wing down, yaw + nose right. */
data class FixedWingControls(
    val pitch: Double,
    val roll: Double,
    val yaw: Double,
    val throttle: Double,
    val wep: Double,
    val flaps: Double,
    val gear: Double,
    val airbrake: Boolean,
    val wheelBrake: Boolean,
    /** Mouse-aim instructor active: it keeps the angle of attack below the critical one. Manual stick has no limiter. */
    val instructor: Boolean,
)

/**
 * Where the aircraft is, as the simulation sees it. [forward], [up] and [right] are the body axes in world space (unit, orthogonal),
 * [velocityMps] the world velocity. [spool] is the engine's output fraction last tick (0..1 + WEP share), the only state the model keeps.
 */
data class AircraftState(
    val velocityMps: V3,
    val forward: V3,
    val up: V3,
    val right: V3,
    val altitudeM: Double,
    val onGround: Boolean,
    val spool: Double,
)

enum class StructuralFailure { NONE, WING_OVERLOAD, OVERSPEED }

data class FixedWingStep(
    val velocityMps: V3,
    /** Body rates to apply this tick, rad/s: pitch + nose up, roll + right wing down, yaw + nose right. */
    val pitchRate: Double,
    val rollRate: Double,
    val yawRate: Double,
    val spool: Double,
    val iasMps: Double,
    val mach: Double,
    val aoaRad: Double,
    val sideslipRad: Double,
    val loadFactor: Double,
    val stalled: Boolean,
    val failure: StructuralFailure,
    val flapsOverspeed: Boolean,
    val gearOverspeed: Boolean,
)

/**
 * One tick of a fixed-wing aircraft. Forces are integrated in [SUBSTEPS] sub-steps: thrust along the nose, lift perpendicular to the
 * airflow in the plane of the wing's up axis, drag against the airflow, a side force against sideslip, gravity, and wheel friction on
 * the ground. Pitch is angle-of-attack commanded: the stick picks a target between the 1 g angle (neutral) and the most it may ask
 * for, scaled by [ControlAuthority], and the nose follows the flight path plus the error to that target; lift, stall and load factor
 * follow from it. The instructor's target stops at the critical angle (with a margin) and at the limit load factor; the manual stick
 * goes past the critical angle and past the structure, so a hard manual pull at speed breaks the wing. Roll and yaw are rate commanded.
 */
object FixedWingSim {
    const val SUBSTEPS = 4
    private const val G = Atmosphere.Isa.G

    /** Top of the model atmosphere: ISA troposphere, then the isothermal lower stratosphere (216.65 K) up to here. */
    const val MAX_ALTITUDE_M = 20_000.0

    /**
     * ISA density. Up to the tropopause it is [Atmosphere.Isa]; above it the standard's isothermal layer,
     * `rho = rho11 exp(-g (h - 11 km) / (R T11))`.
     */
    fun density(altitudeM: Double): Double {
        val h = altitudeM.coerceIn(Atmosphere.Isa.MIN_ALTITUDE_M, MAX_ALTITUDE_M)
        if (h <= Atmosphere.Isa.MAX_ALTITUDE_M) return Atmosphere.Isa.densityKgM3(h)
        val top = Atmosphere.Isa.MAX_ALTITUDE_M
        val t11 = Atmosphere.Isa.temperatureK(top)
        return Atmosphere.Isa.densityKgM3(top) * exp(-G * (h - top) / (Atmosphere.Isa.R * t11))
    }

    fun speedOfSound(altitudeM: Double): Double =
        Atmosphere.Isa.speedOfSoundMps(altitudeM.coerceIn(Atmosphere.Isa.MIN_ALTITUDE_M, Atmosphere.Isa.MAX_ALTITUDE_M))

    /** The instructor stops at this share of the structural load limit, so it never breaks the wing by itself. DESIGN. */
    const val INSTRUCTOR_LOAD_MARGIN = 0.9

    /** Angle of attack and sideslip of the airflow [v] against the body axes, radians. */
    fun angles(v: V3, forward: V3, up: V3, right: V3): Pair<Double, Double> {
        val vf = v.dot(forward)
        if (v.lengthSqr() < 1e-6) return 0.0 to 0.0
        return atan2(-v.dot(up), vf) to atan2(v.dot(right), vf)
    }

    fun step(spec: FixedWingSpec, state: AircraftState, c: FixedWingControls, dtS: Double): FixedWingStep {
        val rho = density(state.altitudeM)
        val sound = speedOfSound(state.altitudeM)
        val polar = spec.polar.withFlaps(spec.flaps, c.flaps)

        val target = (c.throttle.coerceIn(0.0, 1.0) + c.wep.coerceIn(0.0, 1.0))
        val spool = target + (state.spool - target) * exp(-dtS / spec.engine.spoolTimeS)
        val spoolThrottle = spool.coerceIn(0.0, 1.0)
        val spoolWep = (spool - 1.0).coerceIn(0.0, 1.0)

        // Rates from the state at the start of the tick
        val v0 = state.velocityMps
        val speed0 = v0.length()
        val ias0 = speed0 * sqrt(rho / Powerplant.SEA_LEVEL_DENSITY)
        val (aoa0, beta0) = angles(v0, state.forward, state.up, state.right)
        val authority = spec.authority.at(ias0)
        val h = spec.handling
        val deg = PI / 180.0

        // Pitch: the stick commands an angle of attack (an elevator deflection trims the aircraft at one), the nose follows the
        // flight path plus the error to that target. At neutral stick the target is the 1 g angle, so the aircraft holds its path.
        val sub = dtS / SUBSTEPS
        val pitchCmd = c.pitch.coerceIn(-1.0, 1.0)
        val q0 = 0.5 * rho * speed0 * speed0
        var pitchRate = 0.0
        if (!state.onGround && speed0 > 1.0) {
            val clPerG = spec.massKg * G / (q0 * spec.wingAreaM2).coerceAtLeast(1e-6)
            val aoa1g = polar.aoaForCl(clPerG * state.up.y.coerceAtLeast(0.0)).coerceIn(polar.aoaCritLowRad * 0.9, polar.aoaCritHighRad * 0.9)
            val maxPos = if (c.instructor) minOf(polar.aoaCritHighRad * h.instructorAoaMargin, polar.aoaForCl(spec.limits.limitLoadPos * INSTRUCTOR_LOAD_MARGIN * clPerG))
            else polar.aoaCritHighRad * MANUAL_AOA_OVERSHOOT
            val maxNeg = if (c.instructor) maxOf(polar.aoaCritLowRad * h.instructorAoaMargin, polar.aoaForCl(spec.limits.limitLoadNeg * INSTRUCTOR_LOAD_MARGIN * clPerG))
            else polar.aoaCritLowRad * MANUAL_AOA_OVERSHOOT
            val reach = if (pitchCmd >= 0.0) maxPos - aoa1g else aoa1g - maxNeg
            val target = aoa1g + pitchCmd * reach.coerceAtLeast(0.0) * authority
            val (force0, _) = airForces(spec, polar, state, c, v0, rho, sound, spoolThrottle, spoolWep)
            val pathRate = force0.dot(state.up.perpendicularTo(v0.scale(1.0 / speed0)).normalize()) / (spec.massKg * speed0)
            val maxAoaRate = h.maxPitchRateDegS * deg
            pitchRate = pathRate + ((target - aoa0) * h.aoaResponsePerS).coerceIn(-maxAoaRate, maxAoaRate)
        } else if (state.onGround) {
            // On the wheels the elevator only lifts the nose once there is airflow over it (rotation)
            pitchRate = pitchCmd * h.maxPitchRateDegS * deg * authority
        }
        var rollRate = c.roll.coerceIn(-1.0, 1.0) * h.maxRollRateDegS * deg * authority
        var yawRate = c.yaw.coerceIn(-1.0, 1.0) * h.maxYawRateDegS * deg * authority
        val stalled = polar.isStalled(aoa0)
        if (!state.onGround && speed0 > 1.0) {
            val airflow = (ias0 / spec.authority.fullAuthorityIasMps).coerceIn(0.0, 1.0)
            yawRate -= h.yawStabilityPerS * beta0 * airflow
            if (stalled) {
                val s = polar.separation(aoa0)
                pitchRate -= h.stallNoseDropDegS * deg * s * (if (aoa0 > 0.0) 1.0 else -1.0)
                // An asymmetric stall drops the wing on the slipping side: the start of a spin
                rollRate += h.maxRollRateDegS * deg * 0.5 * s * beta0.coerceIn(-0.5, 0.5) / 0.5
            }
        }
        if (state.onGround) rollRate = 0.0

        // Forces
        var v = v0
        var lastLoad = 0.0
        for (i in 0 until SUBSTEPS) {
            var (force, lift) = airForces(spec, polar, state, c, v, rho, sound, spoolThrottle, spoolWep)
            if (state.onGround) force += groundForce(spec, state, c, v, force, sub)
            lastLoad = lift.dot(state.up) / (spec.massKg * G)
            v += force.scale(sub / spec.massKg)
        }

        val speed = v.length()
        val ias = speed * sqrt(rho / Powerplant.SEA_LEVEL_DENSITY)
        val (aoa, beta) = angles(v, state.forward, state.up, state.right)
        val limits = spec.limits
        val failure = when {
            lastLoad > limits.failureLoadPos || lastLoad < limits.failureLoadNeg -> StructuralFailure.WING_OVERLOAD
            ias > limits.vneIasMps * VNE_FAILURE_MARGIN -> StructuralFailure.OVERSPEED
            limits.vneMach != null && speed / sound > limits.vneMach * VNE_FAILURE_MARGIN -> StructuralFailure.OVERSPEED
            else -> StructuralFailure.NONE
        }
        return FixedWingStep(
            velocityMps = v,
            pitchRate = pitchRate,
            rollRate = rollRate,
            yawRate = yawRate,
            spool = spool,
            iasMps = ias,
            mach = speed / sound,
            aoaRad = aoa,
            sideslipRad = beta,
            loadFactor = lastLoad,
            stalled = stalled,
            failure = failure,
            flapsOverspeed = c.flaps > 0.0 && limits.flapsMaxIasMps != null && ias > limits.flapsMaxIasMps,
            gearOverspeed = c.gear > 0.0 && limits.gearMaxIasMps != null && ias > limits.gearMaxIasMps,
        )
    }

    /** Above Vne the airframe holds for a moment (flutter margins); this much over it fails. */
    const val VNE_FAILURE_MARGIN = 1.08

    /** Full manual stick asks for this much more than the critical angle of attack (design): the stick can stall the wing. */
    const val MANUAL_AOA_OVERSHOOT = 1.25

    /** Gravity, thrust, lift, drag and side force at velocity [v]; returns the total and the lift alone. */
    private fun airForces(
        spec: FixedWingSpec, polar: WingPolar, state: AircraftState, c: FixedWingControls, v: V3,
        rho: Double, sound: Double, throttle: Double, wep: Double,
    ): Pair<V3, V3> {
        val speed = v.length()
        val mach = speed / sound
        val (aoa, beta) = angles(v, state.forward, state.up, state.right)
        val q = 0.5 * rho * speed * speed
        var force = V3(0.0, -spec.massKg * G, 0.0)
        force += state.forward.scale(spec.engine.thrustN(throttle, wep, speed, rho, mach))
        var lift = V3.ZERO
        if (speed > 0.5) {
            val dir = v.scale(1.0 / speed)
            val cd = polar.cd(aoa) + spec.mach.cd(mach) + spec.gearCd * c.gear.coerceIn(0.0, 1.0) + (if (c.airbrake) spec.airbrakeCd else 0.0)
            lift = state.up.perpendicularTo(dir).normalize().scale(q * spec.wingAreaM2 * polar.cl(aoa))
            force += lift
            force += dir.scale(-q * spec.wingAreaM2 * cd)
            force += state.right.perpendicularTo(dir).normalize().scale(-q * spec.wingAreaM2 * spec.handling.sideForcePerRad * beta)
        }
        return force to lift
    }

    /**
     * Wheel forces while on the ground: rolling (or braking) friction against the motion along the nose and grip against sliding
     * sideways, both bounded by the normal force (weight minus lift). Never more than it takes to stop that motion in one sub-step.
     */
    private fun groundForce(spec: FixedWingSpec, state: AircraftState, c: FixedWingControls, v: V3, airForce: V3, dt: Double): V3 {
        val normal = (-airForce.y).coerceAtLeast(0.0)
        if (normal <= 0.0) return V3.ZERO
        val flatForward = V3(state.forward.x, 0.0, state.forward.z).normalize()
        val flatRight = V3(state.right.x, 0.0, state.right.z).normalize()
        val along = v.dot(flatForward)
        val side = v.dot(flatRight)
        val mu = if (c.wheelBrake) spec.ground.brakeFriction else spec.ground.rollingFriction
        val stopAlong = abs(along) * spec.massKg / dt
        val stopSide = abs(side) * spec.massKg / dt
        val fAlong = -Math.signum(along) * minOf(mu * normal, stopAlong)
        val fSide = -Math.signum(side) * minOf(spec.ground.lateralFriction * normal, stopSide)
        return flatForward.scale(fAlong) + flatRight.scale(fSide)
    }

    /**
     * Best sustained (thrust = drag) level 360-degree turn time at [altitudeM], full throttle (+ WEP if [withWep]), seconds; null when it
     * cannot hold any turn. The lift coefficient is the smaller of what thrust can pay for (parabolic polar) and `clMax`, the load
     * factor at most the limit load. Speeds are searched in 1 m/s steps up to Mach 1.5.
     */
    fun sustainedTurnTimeS(spec: FixedWingSpec, altitudeM: Double, withWep: Boolean): Double? {
        val rho = density(altitudeM)
        val sound = speedOfSound(altitudeM)
        val weight = spec.massKg * G
        val polar = spec.polar
        var best = Double.POSITIVE_INFINITY
        var v = 15.0
        while (v < sound * 1.5) {
            val mach = v / sound
            val qs = 0.5 * rho * v * v * spec.wingAreaM2
            val excess = spec.engine.thrustN(1.0, if (withWep) 1.0 else 0.0, v, rho, mach) / qs - polar.cdMin - spec.mach.cd(mach)
            if (excess > 0.0) {
                val cl = minOf(sqrt(excess * PI * polar.oswald * polar.aspectRatio), polar.clMax)
                val n = minOf(cl * qs / weight, spec.limits.limitLoadPos)
                if (n > 1.0) best = minOf(best, 2.0 * PI * v / (G * sqrt(n * n - 1.0)))
            }
            v += 1.0
        }
        return best.takeIf { it.isFinite() }
    }

    /** Best steady climb rate at [altitudeM], full throttle (+ WEP), m/s: the largest `(T - D) V / W` over speed (energy method). */
    fun maxClimbRateMps(spec: FixedWingSpec, altitudeM: Double, withWep: Boolean): Double {
        val rho = density(altitudeM)
        val sound = speedOfSound(altitudeM)
        val weight = spec.massKg * G
        var best = 0.0
        var v = 15.0
        while (v < sound * 1.5) {
            val mach = v / sound
            val qs = 0.5 * rho * v * v * spec.wingAreaM2
            val cl = weight / qs
            if (cl <= spec.polar.clMax) {
                val drag = qs * (spec.polar.cdMin + cl * cl / (PI * spec.polar.oswald * spec.polar.aspectRatio) + spec.mach.cd(mach))
                best = maxOf(best, (spec.engine.thrustN(1.0, if (withWep) 1.0 else 0.0, v, rho, mach) - drag) * v / weight)
            }
            v += 1.0
        }
        return best
    }

    /**
     * Fits the sustained turn time to [turnTimeS]. A sustained turn is mostly paid for in induced drag, so the Oswald efficiency
     * (within [OSWALD_FIT_RANGE]) is fitted first, keeping the given `clMax`; only if that range cannot reach the target is `clMax`
     * moved (within [CL_MAX_FIT_RANGE]) with Oswald at the end that came closest. Bisection: turn time only falls as either grows.
     */
    fun fitTurnTime(spec: FixedWingSpec, turnTimeS: Double, altitudeM: Double, withWep: Boolean): FixedWingSpec {
        fun time(s: FixedWingSpec) = sustainedTurnTimeS(s, altitudeM, withWep) ?: Double.POSITIVE_INFINITY
        fun bisect(lo: Double, hi: Double, make: (Double) -> FixedWingSpec): FixedWingSpec {
            var a = lo
            var b = hi
            repeat(50) {
                val mid = 0.5 * (a + b)
                if (time(make(mid)) > turnTimeS) a = mid else b = mid
            }
            return make(b)
        }
        fun withE(base: FixedWingSpec, e: Double) = base.copy(polar = base.polar.copy(oswald = e))
        fun withCl(base: FixedWingSpec, cl: Double) = base.copy(polar = base.polar.copy(clMax = cl))
        val eLo = OSWALD_FIT_RANGE.start
        val eHi = OSWALD_FIT_RANGE.endInclusive
        val fastest = time(withE(spec, eHi))
        val slowest = time(withE(spec, eLo))
        return when {
            turnTimeS in fastest..slowest -> bisect(eLo, eHi) { withE(spec, it) }
            // Still too slow with the least induced drag: more lift
            turnTimeS < fastest -> withE(spec, eHi).let { base -> bisect(maxOf(CL_MAX_FIT_RANGE.start, base.polar.cl0 + 0.1), CL_MAX_FIT_RANGE.endInclusive) { withCl(base, it) } }
            // Still too fast with the most induced drag: less lift
            else -> withE(spec, eLo).let { base -> bisect(maxOf(CL_MAX_FIT_RANGE.start, base.polar.cl0 + 0.1), base.polar.clMax) { withCl(base, it) } }
        }
    }

    /** Oswald efficiency `fitTurnTime` may use: a poor to an excellent planform. DESIGN. */
    val OSWALD_FIT_RANGE = 0.3..1.0

    /**
     * Fits thrust and drag together to a published top speed *and* climb rate: for each thrust scale ([THRUST_FIT_RANGE])
     * `cd_min` is re-derived so the top speed holds, and the scale is bisected on [maxClimbRateMps] (climb rises with it).
     */
    fun fitClimbAndTopSpeed(
        spec: FixedWingSpec, climbMps: Double, climbAltitudeM: Double, maxSpeedMps: Double, maxSpeedAltitudeM: Double, withWep: Boolean,
    ): FixedWingSpec {
        val base = spec.engine
        fun fitted(k: Double): FixedWingSpec {
            val s = spec.copy(engine = base.scaled(k))
            return s.copy(polar = s.polar.copy(cdMin = deriveCdMin(s, maxSpeedMps, maxSpeedAltitudeM, withWep)))
        }
        fun climb(k: Double) = runCatching { maxClimbRateMps(fitted(k), climbAltitudeM, withWep) }.getOrDefault(0.0)
        var a = THRUST_FIT_RANGE.start
        var b = THRUST_FIT_RANGE.endInclusive
        // Below some scale the top speed is out of reach altogether (cd_min would be negative): move the lower bound up to it
        while (runCatching { fitted(a) }.isFailure && a < b) a *= 1.1
        if (climb(a) >= climbMps) return fitted(a)
        if (climb(b) <= climbMps) return fitted(b)
        repeat(50) {
            val mid = 0.5 * (a + b)
            if (climb(mid) < climbMps) a = mid else b = mid
        }
        return fitted(b)
    }

    /** Thrust scale `fitClimbAndTopSpeed` may use. DESIGN. */
    val THRUST_FIT_RANGE = 0.2..3.0

    /** Range `fitTurnTime` may move `clMax` in: a plain wing up to a high-lift fighter with leading-edge extensions. DESIGN. */
    val CL_MAX_FIT_RANGE = 0.6..2.2

    /**
     * The minimum drag coefficient that makes the aircraft's top speed in level flight [maxSpeedMps] at [altitudeM] (mass, thrust at full
     * throttle (+ WEP if [withWep]) and the rest of the polar as given): thrust = drag with lift = weight. Used to derive `cd_min` from a
     * sourced top speed instead of guessing it.
     */
    fun deriveCdMin(spec: FixedWingSpec, maxSpeedMps: Double, altitudeM: Double, withWep: Boolean): Double {
        val rho = density(altitudeM)
        val mach = maxSpeedMps / speedOfSound(altitudeM)
        val q = 0.5 * rho * maxSpeedMps * maxSpeedMps
        val cl = spec.massKg * G / (q * spec.wingAreaM2)
        val thrust = spec.engine.thrustN(1.0, if (withWep) 1.0 else 0.0, maxSpeedMps, rho, mach)
        val cdTotal = thrust / (q * spec.wingAreaM2)
        val cd0 = cdTotal - cl * cl / (PI * spec.polar.oswald * spec.polar.aspectRatio) - spec.mach.cd(mach)
        require(cd0 > 0.0) { "top speed $maxSpeedMps m/s is not reachable with this thrust and polar (derived cd_min $cd0)" }
        return cd0
    }
}
