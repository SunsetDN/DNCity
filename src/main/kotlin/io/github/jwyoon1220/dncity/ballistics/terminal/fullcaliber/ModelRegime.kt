// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult

/** Where an input sits relative to the data a model was calibrated on. Nothing is ever clamped into range. */
enum class ModelRegime { IN_RANGE, EXTRAPOLATED, OUT_OF_MODEL }

/**
 * The axes an answer is judged on. Thickness varies in the calibration data; angle and plate thickness / projectile diameter are the
 * axes of the obliquity data (a slope table has both). Diameter and mass are fixed calibration values.
 */
enum class ModelAxis { THICKNESS, ANGLE, THICKNESS_TO_DIAMETER }

enum class ModelDiagnostic {
    /** The residual velocity is an energy upper bound, not a calibrated residual-velocity model. */
    RESIDUAL_MODEL_UNCALIBRATED,

    /** At least one calibrated axis was outside its data range but inside its extrapolation margin. */
    EXTRAPOLATED_AXIS,

    /** Inclined plate treated as its line-of-sight thickness (exponent 1): geometry only, optimistic for the plate at high angles. */
    OBLIQUITY_GEOMETRIC_ONLY,

    /** Inclined plate scaled by an empirical cosine exponent taken from the preset's named source. */
    OBLIQUITY_EMPIRICAL_EXPONENT,

    /** Inclined plate scaled by a slope table over angle and plate thickness / projectile diameter, from the preset's named source. */
    OBLIQUITY_TABULATED,

    /** The residual keeps flying along its incoming direction: no deflection, tumbling or yaw is modelled. */
    NO_DEFLECTION_MODEL,

    /** The ricochet table gives probability 1 at this angle: the projectile glanced off, penetration was not evaluated. */
    RICOCHET_CERTAIN,

    /** The ricochet table gives a probability strictly between 0 and 1; the outcome was drawn from the impact seed. */
    RICOCHET_DRAWN,

    /** A ricochet leaves along the mirror direction with a fixed speed ratio: no angle- or speed-dependent loss, no tumbling. */
    RICOCHET_SPECULAR_FIXED_RETENTION,
}

data class AxisAssessment(val axis: ModelAxis, val value: Double, val regime: ModelRegime)

data class RegimeReport(val regime: ModelRegime, val axes: List<AxisAssessment>, val reasons: List<String>)

/**
 * What a model that may decline an input answers. [Unresolved] is an ordinary value, not an error: the resolver that owns
 * the solver chain decides what happens next (fallback solver, rejecting the shot); the model never chooses a fallback itself.
 */
sealed interface ModelEvaluation {
    val report: RegimeReport

    /** @property ballisticLimitMps null when the projectile ricocheted: penetration was never evaluated */
    data class Resolved(
        val result: PenetrationResult,
        override val report: RegimeReport,
        val diagnostics: Set<ModelDiagnostic>,
        val ballisticLimitMps: Double?,
        val limitDefinition: BallisticLimitDefinition,
    ) : ModelEvaluation

    data class Unresolved(override val report: RegimeReport) : ModelEvaluation
}
