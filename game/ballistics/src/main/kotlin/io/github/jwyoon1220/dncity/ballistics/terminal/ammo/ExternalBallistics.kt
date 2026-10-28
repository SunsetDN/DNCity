// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/**
 * Speed against flight distance for the one thing a tabulated penetration needs it for: reading the table at the distance a
 * projectile has "flown", when the shot only knows its current speed. Quadratic drag in a uniform atmosphere gives an exact
 * exponential, `v(d) = v0 * exp(-k d)` with `k = rho * Cd * A / (2 m)`. Gravity and altitude are ignored (the table itself is a
 * horizontal-range statement). The drag coefficient is the ammo's own data; air density is the ISA sea-level value.
 */
object ExternalBallistics {
    const val AIR_DENSITY_KG_M3 = 1.225

    fun decayPerMetre(p: AmmoProjectile): Double =
        AIR_DENSITY_KG_M3 * p.dragCoefficient * (PI * p.diameterM * p.diameterM / 4.0) / (2.0 * p.massKg)

    fun speedAtDistance(p: AmmoProjectile, distanceM: Double): Double = p.muzzleVelocityMps * exp(-decayPerMetre(p) * distanceM)

    /**
     * Flight distance at which the speed has dropped to [speedMps]; null for a speed that is not positive or finite. A speed
     * above the muzzle velocity (within float noise) is distance 0; the caller's table range policy then decides, since the
     * projectile is faster than the ammo definition says it can be.
     */
    fun distanceForSpeed(p: AmmoProjectile, speedMps: Double): Double? {
        if (!speedMps.isFinite() || speedMps <= 0.0) return null
        val d = ln(p.muzzleVelocityMps / speedMps) / decayPerMetre(p)
        return if (d < 0.0 && d > -1e-6) 0.0 else d
    }
}
