// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult

/**
 * Ballistic limit and residual speed of a full-caliber, uncapped steel AP projectile against homogeneous RHA at **normal
 * incidence** (de Marre form, one calibration coefficient). Everything else (capped AP/APC/APCBC, shatter, partial penetration,
 * plug mass, another diameter or mass) is out of scope and answered with [ModelEvaluation.Unresolved].
 *
 * Only plate thickness has a calibrated range, so only thickness can be EXTRAPOLATED, unless the preset declares an
 * [ObliquitySpec]: then an inclined plate is judged by its effective normal thickness (`T / cos(theta)^n`, or a [SlopeTable] over
 * angle and plate thickness / projectile diameter) and the angle is a second axis with the author's own range.
 *
 * With a [RicochetSpec] the ricochet is decided first, the way a projectile meets a plate: certain (probability 1) answers RICOCHET
 * even beyond the penetration branch's angle range; a probability strictly between 0 and 1 is drawn from the impact seed and, if no
 * ricochet happens, the penetration branch answers (or declines). Without one, steep angles are declined, never guessed.
 *
 * Not a [io.github.jwyoon1220.dncity.ballistics.terminal.PenetratorModel] on purpose: that contract has no way to say "I cannot
 * answer" and must not throw. A resolver that owns the solver chain wraps this model and decides what an `Unresolved`
 * leads to; this class never picks a fallback itself.
 *
 * @param presets calibrations by id; a layer's material selects one through its `resistance` entry for [ID]
 */
class FullCaliberApModel(private val presets: Map<Ident, FullCaliberApPreset>) {

    fun supports(context: ImpactContext, layer: ArmorLayer): RegimeReport = when (val p = plan(context, layer)) {
        is Plan.Refused -> p.report
        is Plan.Ricochet -> p.report
        is Plan.Penetrate -> p.assessment.report
    }

    fun evaluate(context: ImpactContext, layer: ArmorLayer): ModelEvaluation = when (val plan = plan(context, layer)) {
        is Plan.Refused -> ModelEvaluation.Unresolved(plan.report)
        is Plan.Ricochet -> ricochet(context, plan)
        is Plan.Penetrate -> penetrate(context, plan)
    }

    private fun ricochet(context: ImpactContext, plan: Plan.Ricochet): ModelEvaluation.Resolved {
        val p = context.projectile
        val spec = plan.preset.ricochet!!
        val dir = RicochetSpec.reflect(p.velocity.normalize(), context.surfaceNormal)
        val residual = p.copy(velocity = dir.scale(p.speedMps * spec.speedRetention))
        val result = PenetrationResult.ricochet(residual, p.kineticEnergyJ - residual.kineticEnergyJ)
        return ModelEvaluation.Resolved(result, plan.report, plan.diagnostics + ModelDiagnostic.RICOCHET_SPECULAR_FIXED_RETENTION, null, plan.preset.limitDefinition)
    }

    private fun penetrate(context: ImpactContext, plan: Plan.Penetrate): ModelEvaluation.Resolved {
        val a = plan.assessment
        val preset = plan.preset
        val p = context.projectile
        val vi = p.speedMps
        val vbl = preset.ballisticLimitMps(context.definition.projectileDiameterM, a.effectiveThicknessM, p.massRemainingKg)
        val diagnostics = buildSet {
            addAll(plan.diagnostics)
            add(ModelDiagnostic.RESIDUAL_MODEL_UNCALIBRATED)
            if (a.report.regime == ModelRegime.EXTRAPOLATED) add(ModelDiagnostic.EXTRAPOLATED_AXIS)
            preset.obliquity?.let { o ->
                add(
                    when (o.basis) {
                        ObliquityBasis.GEOMETRIC_LOS -> ModelDiagnostic.OBLIQUITY_GEOMETRIC_ONLY
                        ObliquityBasis.COSINE_POWER -> ModelDiagnostic.OBLIQUITY_EMPIRICAL_EXPONENT
                        ObliquityBasis.TABULATED -> ModelDiagnostic.OBLIQUITY_TABULATED
                    },
                )
                add(ModelDiagnostic.NO_DEFLECTION_MODEL)
            }
        }
        // The ballistic-limit branch decides first; the residual formula is only evaluated above the limit.
        val result = if (vi <= vbl) {
            PenetrationResult.stopped(p.kineticEnergyJ)
        } else {
            val vr = preset.residual.residualSpeedMps(vi, vbl)
            val dir = p.velocity.normalize()
            val point = p.position + dir.scale(a.pathThroughPlateM) // along the flight line: thickness / cos(angle)
            val residual = p.copy(position = point, velocity = dir.scale(vr))
            PenetrationResult.perforated(residual, p.kineticEnergyJ - residual.kineticEnergyJ, point)
        }
        return ModelEvaluation.Resolved(result, a.report, diagnostics, vbl, preset.limitDefinition)
    }

    /** What [evaluate] will do with an input, decided once so that [supports] and [evaluate] cannot disagree. */
    private sealed interface Plan {
        data class Refused(val report: RegimeReport) : Plan
        data class Ricochet(val preset: FullCaliberApPreset, val report: RegimeReport, val diagnostics: Set<ModelDiagnostic>) : Plan
        data class Penetrate(val preset: FullCaliberApPreset, val assessment: Assessment, val diagnostics: Set<ModelDiagnostic>) : Plan
    }

    private class Assessment(
        val report: RegimeReport,
        /** Thickness of the equivalent normal plate the calibration is evaluated at. */
        val effectiveThicknessM: Double = 0.0,
        /** Length of the flight line inside the plate (geometry: thickness / cos(angle)). */
        val pathThroughPlateM: Double = 0.0,
        val accepted: Boolean = true,
    )

    private fun refusal(reason: String) = RegimeReport(ModelRegime.OUT_OF_MODEL, emptyList(), listOf(reason))

    private fun worse(a: ModelRegime, b: ModelRegime) = if (a.ordinal >= b.ordinal) a else b

    private fun plan(context: ImpactContext, layer: ArmorLayer): Plan {
        val presetId = context.catalog.material(layer.materialId).presetFor(ID) ?: return Plan.Refused(refusal("material ${layer.materialId} has no preset for $ID"))
        val preset = presets[presetId] ?: return Plan.Refused(refusal("unknown preset $presetId"))
        identityProblem(context, preset)?.let { return Plan.Refused(refusal(it)) }
        // A projectile at rest has no direction, so no angle and no ricochet.
        val moving = context.projectile.speedMps > 0.0
        val angle = if (moving) context.angleFromNormalRad else 0.0
        val diagnostics = mutableSetOf<ModelDiagnostic>()
        val spec = preset.ricochet
        if (spec != null && moving) {
            val probability = spec.probability(angle)
                ?: return Plan.Refused(refusal("impact angle ${Math.toDegrees(angle)} degrees is beyond the ricochet table's ${Math.toDegrees(spec.anglesRad.last())} degrees"))
            val report = RegimeReport(ModelRegime.IN_RANGE, listOf(AxisAssessment(ModelAxis.ANGLE, angle, ModelRegime.IN_RANGE)), emptyList())
            if (probability >= 1.0) return Plan.Ricochet(preset, report, setOf(ModelDiagnostic.RICOCHET_CERTAIN))
            if (probability > 0.0) {
                diagnostics += ModelDiagnostic.RICOCHET_DRAWN
                if (RicochetSpec.draw(context.seed) < probability) return Plan.Ricochet(preset, report, diagnostics)
            }
        }
        val a = assessPenetration(context, layer, preset, angle)
        return if (a.accepted) Plan.Penetrate(preset, a, diagnostics) else Plan.Refused(a.report)
    }

    /** Why [preset] cannot be applied to this projectile at all, or null (checks that do not depend on angle or thickness). */
    private fun identityProblem(context: ImpactContext, preset: FullCaliberApPreset): String? {
        val def = context.definition
        if (def.id !in preset.allowedProjectiles) return "projectile ${def.id} is not one the calibration was made for"
        if (def.geometry == Geometry.LONG_ROD || def.geometry == Geometry.SHAPED_CHARGE_CONE) return "geometry ${def.geometry} is not a full-caliber AP projectile"
        val payload = def.payload
        if (payload != null && (payload.explosiveKgTnt > 0.0 || payload.coneDiameterM != null)) return "projectile carries a payload"
        val d = def.projectileDiameterM
        val m = context.projectile.massRemainingKg
        if (!preset.isCalibratedDiameter(d)) return "diameter $d m is not the calibrated ${preset.calibratedDiameterM} m"
        if (!preset.isCalibratedMass(m)) return "mass $m kg is not the calibrated ${preset.calibratedMassKg} kg"
        return null
    }

    private fun assessPenetration(context: ImpactContext, layer: ArmorLayer, preset: FullCaliberApPreset, angle: Double): Assessment {
        fun refuse(reason: String) = Assessment(refusal(reason), accepted = false)

        // Obliquity is calibrated at 0 degrees only; the tolerance is float noise.
        val obliq = preset.obliquity
        if (obliq == null && context.projectile.speedMps > 0.0 && (!context.angleFromNormalRad.isFinite() || context.angleFromNormalRad > preset.normalIncidenceToleranceRad)) {
            return refuse("impact is not normal incidence: ${Math.toDegrees(context.angleFromNormalRad)} degrees from the normal")
        }
        val angleRegime = obliq?.assessAngle(angle) ?: ModelRegime.IN_RANGE
        if (obliq != null && angleRegime == ModelRegime.OUT_OF_MODEL) {
            return refuse("impact angle ${Math.toDegrees(angle)} degrees is beyond the model's ${Math.toDegrees(obliq.maxAngleRad + obliq.extrapolationMarginRad)} degrees (and no certain ricochet covers it)")
        }
        val d = context.definition.projectileDiameterM
        val ratio = layer.thicknessM / d
        if (obliq != null && !obliq.coversRatio(ratio)) {
            return refuse("plate thickness / projectile diameter $ratio is outside the slope table")
        }

        // The calibrated thickness axis is the thickness of a normal plate, so an inclined plate is judged by its effective thickness.
        val t = if (obliq == null) layer.thicknessM else obliq.effectiveThicknessM(layer.thicknessM, angle, d)
        val path = if (obliq == null) layer.thicknessM else layer.thicknessM / kotlin.math.cos(angle)
        val thickness = AxisAssessment(ModelAxis.THICKNESS, t, preset.thickness.assess(t))
        val axes = buildList {
            add(thickness)
            if (obliq != null) add(AxisAssessment(ModelAxis.ANGLE, angle, angleRegime))
            if (obliq?.basis == ObliquityBasis.TABULATED) add(AxisAssessment(ModelAxis.THICKNESS_TO_DIAMETER, ratio, ModelRegime.IN_RANGE))
        }
        val reasons = axes.filter { it.regime != ModelRegime.IN_RANGE }.map { "${it.axis} = ${it.value} is ${it.regime}" }
        val regime = axes.fold(ModelRegime.IN_RANGE) { acc, ax -> worse(acc, ax.regime) }
        if (regime == ModelRegime.OUT_OF_MODEL) return Assessment(RegimeReport(regime, axes, reasons), accepted = false)
        return Assessment(RegimeReport(regime, axes, reasons), t, path)
    }

    companion object {
        val ID = Ident("dncity", "full_caliber_ap_demarre")
    }
}
