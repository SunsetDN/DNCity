// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult

/**
 * Ballistic limit and residual speed of a full-caliber, uncapped steel AP projectile against homogeneous RHA at **normal
 * incidence** (de Marre form, one calibration coefficient). Everything else (oblique impact, capped AP/APC/APCBC,
 * ricochet, shatter, partial penetration, plug mass, another diameter or mass) is out of scope and answered with
 * [ModelEvaluation.Unresolved].
 *
 * Only plate thickness has a calibrated range, so only thickness can be EXTRAPOLATED.
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
        // The ballistic-limit branch decides first; the residual formula is only evaluated above the limit.
        val result = if (vi <= vbl) {
            PenetrationResult.stopped(p.kineticEnergyJ)
        } else {
            val vr = preset.residual.residualSpeedMps(vi, vbl)
            val dir = p.velocity.normalize()
            val point = p.position + dir.scale(layer.thicknessM) // normal incidence: line of sight = thickness
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

        // Obliquity is calibrated at 0 degrees only; the tolerance is float noise. A projectile at rest has no direction, so no angle.
        val p = context.projectile
        if (p.speedMps > 0.0 && (!context.angleFromNormalRad.isFinite() || context.angleFromNormalRad > preset.normalIncidenceToleranceRad)) {
            return refuse("impact is not normal incidence: ${Math.toDegrees(context.angleFromNormalRad)} degrees from the normal")
        }

        val d = def.projectileDiameterM
        val m = p.massRemainingKg
        if (!preset.isCalibratedDiameter(d)) return refuse("diameter $d m is not the calibrated ${preset.calibratedDiameterM} m")
        if (!preset.isCalibratedMass(m)) return refuse("mass $m kg is not the calibrated ${preset.calibratedMassKg} kg")

        val t = layer.thicknessM
        val thickness = AxisAssessment(ModelAxis.THICKNESS, t, preset.thickness.assess(t))
        val reasons = if (thickness.regime == ModelRegime.IN_RANGE) emptyList() else listOf("THICKNESS = $t m is ${thickness.regime}")
        return Assessment(RegimeReport(thickness.regime, listOf(thickness), reasons), preset)
    }

    companion object {
        val ID = Ident("dncity", "full_caliber_ap_demarre")
    }
}
