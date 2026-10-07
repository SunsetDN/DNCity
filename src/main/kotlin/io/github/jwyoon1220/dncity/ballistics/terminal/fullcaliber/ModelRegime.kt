// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult

/** Where an input sits relative to the data a model was calibrated on. Nothing is ever clamped into range. */
enum class ModelRegime { IN_RANGE, EXTRAPOLATED, OUT_OF_MODEL }

/** The calibrated axes that can be extrapolated. Today only thickness varies in the data; diameter, mass and obliquity are fixed calibration values. */
enum class ModelAxis { THICKNESS }

enum class ModelDiagnostic {
    /** The residual velocity is an energy upper bound, not a calibrated residual-velocity model. */
    RESIDUAL_MODEL_UNCALIBRATED,

    /** At least one calibrated axis was outside its data range but inside its extrapolation margin. */
    EXTRAPOLATED_AXIS,
}

data class AxisAssessment(val axis: ModelAxis, val value: Double, val regime: ModelRegime)

data class RegimeReport(val regime: ModelRegime, val axes: List<AxisAssessment>, val reasons: List<String>)

/**
 * What a model that may decline an input answers. [Unresolved] is an ordinary value, not an error: the resolver that owns
 * the solver chain decides what happens next (fallback solver, rejecting the shot); the model never chooses a fallback itself.
 */
sealed interface ModelEvaluation {
    val report: RegimeReport

    data class Resolved(
        val result: PenetrationResult,
        override val report: RegimeReport,
        val diagnostics: Set<ModelDiagnostic>,
        val ballisticLimitMps: Double,
        val limitDefinition: BallisticLimitDefinition,
    ) : ModelEvaluation

    data class Unresolved(override val report: RegimeReport) : ModelEvaluation
}
