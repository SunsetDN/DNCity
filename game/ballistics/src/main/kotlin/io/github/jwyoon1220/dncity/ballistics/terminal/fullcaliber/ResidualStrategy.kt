// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import kotlin.math.sqrt

/**
 * How the speed after a perforated plate is derived from the ballistic limit.
 *
 * [NO_PLUG_ENERGY_UPPER_BOUND] is `v_r = sqrt(v_i^2 - v_bl^2)`: the projectile loses a constant `1/2 m v_bl^2` and keeps
 * the rest. It is an upper bound on residual kinetic energy (no plug, no mass loss, no deformation), **not** a
 * validated residual-velocity model. Recht-Ipson and Lambert-Jonas are deliberately absent until their sources
 * have been read and residual-velocity data exists to calibrate them.
 */
enum class ResidualStrategy {
    NO_PLUG_ENERGY_UPPER_BOUND;

    /** Speed after the plate. Only defined above the ballistic limit: the caller decides stopped/perforated first, nothing is clamped here. */
    fun residualSpeedMps(strikingSpeedMps: Double, ballisticLimitMps: Double): Double {
        require(strikingSpeedMps > ballisticLimitMps) { "residual speed is only defined above the ballistic limit" }
        return when (this) {
            NO_PLUG_ENERGY_UPPER_BOUND -> sqrt(strikingSpeedMps * strikingSpeedMps - ballisticLimitMps * ballisticLimitMps)
        }
    }
}
