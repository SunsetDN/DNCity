// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult

/**
 * Ballistic limit and residual speed of a full-caliber, uncapped steel AP projectile against homogeneous RHA, **normal
 * impact only** (de Marre form, one calibration coefficient). Everything else (oblique impact, capped AP/APC/APCBC,
 * ricochet, shatter, partial penetration, plug mass) is out of scope and answered with [ModelEvaluation.Unresolved].
 *
 * Not a [io.github.jwyoon1220.dncity.ballistics.terminal.PenetratorModel] on purpose: that contract has no way to say "I cannot
 * answer" and must not throw. A resolver that owns the solver chain wraps this model and decides what an `Unresolved`
 * leads to; this class never picks a fallback itself.
 *
 * @param presets calibrations by id; a layer's material selects one through its `resistance` entry for [ID]
 */
class FullCaliberApModel(private val presets: Map<Ident, FullCaliberApPreset>) {

    fun supports(context: ImpactContext, layer: ArmorLayer): RegimeReport = assess(context, layer).report

    fun evaluate(context: ImpactContext, layer: ArmorLayer): ModelEvaluation {
        val a = assess(context, layer)
        val preset = a.preset
        if (a.report.regime == ModelRegime.OUT_OF_MODEL || preset == null) return ModelEvaluation.Unresolved(a.report)

        val p = context.projectile
        val vi = p.speedMps
        val vbl = preset.ballisticLimitMps(context.definition.projectileDiameterM, layer.thicknessM, p.massRemainingKg)
        val diagnostics = buildSet {
            add(ModelDiagnostic.RESIDUAL_MODEL_UNCALIBRATED)
            if (a.report.regime == ModelRegime.EXTRAPOLATED) add(ModelDiagnostic.EXTRAPOLATED_AXIS)
        }
        val vr = preset.residual.residualSpeedMps(vi, vbl)
        val result = if (vr <= 0.0) {
            PenetrationResult.stopped(p.kineticEnergyJ)
        } else {
            val dir = p.velocity.normalize()
            val point = p.position + dir.scale(layer.thicknessM) // normal impact: line of sight = thickness
            val residual = p.copy(position = point, velocity = dir.scale(vr))
            PenetrationResult.perforated(residual, p.kineticEnergyJ - residual.kineticEnergyJ, point)
        }
        return ModelEvaluation.Resolved(result, a.report, diagnostics, vbl, preset.limitDefinition)
    }

    private class Assessment(val report: RegimeReport, val preset: FullCaliberApPreset?)

    private fun assess(context: ImpactContext, layer: ArmorLayer): Assessment {
        fun refuse(reason: String) = Assessment(RegimeReport(ModelRegime.OUT_OF_MODEL, emptyList(), listOf(reason)), null)

        val presetId = context.catalog.material(layer.materialId).presetFor(ID) ?: return refuse("material ${layer.materialId} has no preset for $ID")
        val preset = presets[presetId] ?: return refuse("unknown preset $presetId")
        val def = context.definition
        if (def.id !in preset.allowedProjectiles) return refuse("projectile ${def.id} is not one the calibration was made for")
        if (def.geometry == Geometry.LONG_ROD || def.geometry == Geometry.SHAPED_CHARGE_CONE) return refuse("geometry ${def.geometry} is not a full-caliber AP projectile")
        val payload = def.payload
        if (payload != null && (payload.explosiveKgTnt > 0.0 || payload.coneDiameterM != null)) return refuse("projectile carries a payload")
        if (!context.angleFromNormalRad.isFinite() || context.angleFromNormalRad > preset.normalToleranceRad) {
            return refuse("impact is not normal: ${Math.toDegrees(context.angleFromNormalRad)} degrees from the normal")
        }

        val d = def.projectileDiameterM
        val t = layer.thicknessM
        val m = context.projectile.massRemainingKg
        val axes = listOf(
            AxisAssessment(ModelAxis.THICKNESS, t, preset.thickness.assess(t)),
            AxisAssessment(ModelAxis.DIAMETER, d, preset.diameter.assess(d)),
            AxisAssessment(ModelAxis.MASS, m, preset.mass.assess(m)),
            AxisAssessment(ModelAxis.DIAMETER_OVER_THICKNESS, d / t, preset.diameterOverThickness.assess(d / t)),
        )
        val worst = axes.maxOf { it.regime }
        val reasons = axes.filter { it.regime != ModelRegime.IN_RANGE }.map { "${it.axis} = ${it.value} is ${it.regime}" }
        return Assessment(RegimeReport(worst, axes, reasons), preset)
    }

    companion object {
        val ID = Ident("dncity", "full_caliber_ap_demarre")
    }
}
