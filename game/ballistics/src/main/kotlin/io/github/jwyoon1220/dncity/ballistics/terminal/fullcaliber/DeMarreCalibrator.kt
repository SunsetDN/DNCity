// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** One ballistic-limit observation at normal impact, SI. */
data class CalibrationPoint(val thicknessM: Double, val ballisticLimitMps: Double)

/**
 * Result of fitting the single coefficient. Residuals are `(model / data - 1) * 100` in percent.
 *
 * @property independentSamples null = unknown. A piecewise-linear specification table is not [rowCount] independent measurements.
 */
data class CalibrationFit(
    val coefficient: Double,
    val rowCount: Int,
    val independentSamples: Int?,
    val minResidualPct: Double,
    val maxResidualPct: Double,
    val rmsResidualPct: Double,
    val logRmse: Double,
)

object DeMarreCalibrator {
    /** Log-space least squares with fixed exponents: `ln K = mean(ln v_data - ln V_dM)`. Fits nothing but K. */
    fun fit(profile: DeMarreProfile, diameterM: Double, massKg: Double, points: List<CalibrationPoint>): CalibrationFit {
        require(points.isNotEmpty()) { "no calibration points" }
        val logs = points.map { ln(it.ballisticLimitMps) - ln(profile.baselineVelocityMps(diameterM, it.thicknessM, massKg)) }
        val k = exp(logs.average())
        val residuals = points.map { (k * profile.baselineVelocityMps(diameterM, it.thicknessM, massKg) / it.ballisticLimitMps - 1.0) * 100.0 }
        val lnK = ln(k)
        return CalibrationFit(
            coefficient = k,
            rowCount = points.size,
            independentSamples = null,
            minResidualPct = residuals.min(),
            maxResidualPct = residuals.max(),
            rmsResidualPct = sqrt(residuals.sumOf { it * it } / residuals.size),
            logRmse = sqrt(logs.sumOf { (it - lnK) * (it - lnK) } / logs.size),
        )
    }
}
