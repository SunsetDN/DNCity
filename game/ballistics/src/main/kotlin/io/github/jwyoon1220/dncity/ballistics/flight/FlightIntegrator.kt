// AGENT-DONE(claude): external-ballistics-si
package io.github.jwyoon1220.dncity.ballistics.flight

import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.PI

/** Point-mass state in SI: position (m, +Y up), velocity (m/s), time (s), path length (m). */
data class FlightState(val position: V3, val velocity: V3, val timeS: Double, val pathM: Double) {
    val speedMps: Double get() = velocity.length()
}

sealed interface FlightStep {
    data class Ok(val state: FlightState) : FlightStep

    /** The drag model or atmosphere does not cover this state. Flight stops here; nothing is extrapolated. */
    data class OutOfModel(val last: FlightState, val reason: String) : FlightStep
}

/**
 * Point-mass flight under quadratic drag and gravity, integrated with fixed-step RK4 (deterministic: same inputs, same bits).
 * `a = -(rho(h) Cd(M) A / 2m) |v| v + g`. No wind, no Coriolis, no spin drift, no yaw of repose: this is the flat-fire
 * point-mass model, and it says so; those effects are separate models, not tuning knobs hidden in here.
 *
 * @property seaLevelY world Y that is altitude 0 for the atmosphere
 */
class FlightIntegrator(
    private val massKg: Double,
    diameterM: Double,
    private val drag: DragModel,
    private val atmosphere: Atmosphere = Atmosphere.Isa,
    private val gravityMps2: Double = Atmosphere.Isa.G,
    val seaLevelY: Double = 0.0,
) {
    private val areaM2 = PI * diameterM * diameterM / 4.0

    init {
        require(massKg > 0.0 && diameterM > 0.0 && gravityMps2 >= 0.0) { "mass and diameter must be positive, gravity not negative" }
    }

    private class OutOfModelException(message: String) : RuntimeException(message)

    private fun accel(p: V3, v: V3): V3 {
        val h = p.y - seaLevelY
        val speed = v.length()
        val rho = atmosphere.densityKgM3(h)
        val mach = speed / atmosphere.speedOfSoundMps(h)
        val cd = drag.cd(mach) ?: throw OutOfModelException("Mach $mach is outside the drag model")
        val k = rho * cd * areaM2 / (2.0 * massKg)
        return v.scale(-k * speed) + V3(0.0, -gravityMps2, 0.0)
    }

    fun step(s: FlightState, dtS: Double): FlightStep {
        require(dtS > 0.0 && dtS.isFinite()) { "time step must be positive" }
        try {
            val p0 = s.position
            val v0 = s.velocity
            val a1 = accel(p0, v0)
            val v2 = v0 + a1.scale(dtS / 2)
            val a2 = accel(p0 + v0.scale(dtS / 2), v2)
            val v3 = v0 + a2.scale(dtS / 2)
            val a3 = accel(p0 + v2.scale(dtS / 2), v3)
            val v4 = v0 + a3.scale(dtS)
            val a4 = accel(p0 + v3.scale(dtS), v4)
            val dp = (v0 + v2.scale(2.0) + v3.scale(2.0) + v4).scale(dtS / 6.0)
            val dv = (a1 + a2.scale(2.0) + a3.scale(2.0) + a4).scale(dtS / 6.0)
            return FlightStep.Ok(FlightState(p0 + dp, v0 + dv, s.timeS + dtS, s.pathM + dp.length()))
        } catch (e: OutOfModelException) {
            return FlightStep.OutOfModel(s, e.message ?: "outside the model")
        } catch (e: IllegalArgumentException) { // atmosphere altitude range
            return FlightStep.OutOfModel(s, e.message ?: "outside the atmosphere model")
        }
    }

    /** Advances for [durationS] in steps of at most [dtS]; stops early on [FlightStep.OutOfModel]. */
    fun advance(s: FlightState, durationS: Double, dtS: Double = 0.001): FlightStep {
        require(durationS >= 0.0) { "duration must not be negative" }
        var cur = s
        var left = durationS
        while (left > 1e-12) {
            val h = minOf(dtS, left)
            when (val r = step(cur, h)) {
                is FlightStep.Ok -> cur = r.state
                is FlightStep.OutOfModel -> return r
            }
            left -= h
        }
        return FlightStep.Ok(cur)
    }
}
