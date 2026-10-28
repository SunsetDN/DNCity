// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorConstruction
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorEffectSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorMaterial
import io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsCatalog
import io.github.jwyoon1220.dncity.ballistics.terminal.ExternalBallisticsSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.ModelRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileDefinition
import io.github.jwyoon1220.dncity.ballistics.terminal.ResistancePreset
import kotlin.math.abs

/** The ammo catalog as the resolver needs it: lookup by id. A data reload replaces what this returns. */
fun interface AmmoSource {
    fun ammo(id: Ident): AmmoDefinition?
}

/** AmmoDefinition -> the frozen-contract [ProjectileDefinition] the traversal reads. Pure; the penetration block is not part of it. */
object AmmoCompiler {
    fun compile(a: AmmoDefinition): ProjectileDefinition = ProjectileDefinition(
        id = a.id,
        gunCaliberM = a.compat.gunCaliberM,
        projectileDiameterM = a.projectile.diameterM,
        massKg = a.projectile.massKg,
        geometry = a.projectile.geometry,
        stabilization = a.projectile.stabilization,
        penetrator = a.projectile.penetrator,
        payload = a.projectile.payload,
        external = ExternalBallisticsSpec(a.projectile.dragCoefficient, a.projectile.muzzleVelocityMps),
        terminalModel = a.terminal.solver,
    )
}

/**
 * A [BallisticsCatalog] that also knows every ammo as a projectile, falling back to [base] for everything else. This is how a
 * [io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileState] launched from an ammo id resolves its definition in
 * [ImpactContext.of] without the ammo being copied into the projectile registry.
 */
class AmmoAwareCatalog(private val base: BallisticsCatalog, private val ammo: AmmoSource) : BallisticsCatalog {
    override fun projectile(id: Ident): ProjectileDefinition = ammo.ammo(id)?.let(AmmoCompiler::compile) ?: base.projectile(id)
    override fun material(id: Ident): ArmorMaterial = base.material(id)
    override fun preset(id: Ident): ResistancePreset = base.preset(id)
    override fun effect(id: Ident): ArmorEffectSpec = base.effect(id)
    override fun construction(id: Ident): ArmorConstruction = base.construction(id)
}

/**
 * Answers "what does this ammo do to this layer" from the ammo's declared [PenetrationSpec].
 *
 * - **TABLE**: the tabulated limit is read at (flight distance, impact angle). The plate's normal thickness at or below the limit
 *   is PERFORATED, above is STOPPED. A limit is not damage and gives no residual; see [AmmoDiagnostic.RESIDUAL_NOT_DERIVED_FROM_TABLE].
 * - **PHYSICS**: the registered solver named by `terminal.solver` answers or declines.
 * - **HYBRID**: the table decides, the solver cross-checks, and every disagreement is reported, never hidden.
 *
 * Whatever cannot be answered is [AmmoEvaluation.Unresolved]. A solver that is not registered is never replaced by another one.
 * Pure and deterministic: same context, layer, ammo data and flight distance give the same answer.
 */
class AmmoImpactResolver(
    private val ammo: AmmoSource,
    private val solvers: ModelRegistry<AmmoSolver> = AmmoSolvers.registry,
) {
    /**
     * @param flightDistanceM distance the shot has flown, if the shot knows it. When null it is derived from the impact speed
     *   and the ammo's drag ([ExternalBallistics]) and flagged [AmmoDiagnostic.DISTANCE_DERIVED_FROM_SPEED].
     */
    fun resolve(context: ImpactContext, layer: ArmorLayer, flightDistanceM: Double? = null): AmmoEvaluation {
        val def = ammo.ammo(context.definition.id)
            ?: return unresolved(UnresolvedReason.NO_AMMO_DEFINITION, "no ammo definition ${context.definition.id}")
        val solver = solvers[def.terminal.solver]
            ?: return unresolved(UnresolvedReason.UNIMPLEMENTED_SOLVER, "solver ${def.terminal.solver} is not implemented")
        if (context.projectile.speedMps <= 0.0) return unresolved(UnresolvedReason.INVALID_INPUT, "projectile is at rest")

        return when (val spec = def.penetration) {
            PenetrationSpec.Physics -> {
                if (!solver.canSolvePhysics) unresolved(UnresolvedReason.SOLVER_IS_NOT_PHYSICS, "${solver.id} computes no physics")
                else solver.evaluate(context, layer)
            }
            is PenetrationSpec.Table -> table(def, spec, context, layer, flightDistanceM)
            is PenetrationSpec.Hybrid -> hybrid(def, spec, solver, context, layer, flightDistanceM)
        }
    }

    private fun table(def: AmmoDefinition, spec: PenetrationSpec.Table, ctx: ImpactContext, layer: ArmorLayer, givenDistance: Double?): AmmoEvaluation {
        val meta = spec.meta
        val diag = HashSet<AmmoDiagnostic>()
        if (AmmoValidation.calibrationOf(def) != CalibrationStatus.PRODUCTION) diag += AmmoDiagnostic.UNCALIBRATED_TABLE
        if (def.terminal.ricochetProfile != null || def.terminal.shatterProfile != null || def.terminal.spallProfile != null) diag += AmmoDiagnostic.TERMINAL_PROFILES_NOT_EVALUATED

        val ref = meta.referenceMaterial
            ?: return unresolved(UnresolvedReason.TABLE_HAS_NO_REFERENCE_MATERIAL, "the table does not say which armor material it is a limit of", diag)
        // No RHA-equivalence for other materials: that would be a ResistancePreset's job, and none exists for table limits.
        if (layer.materialId != ref) return unresolved(UnresolvedReason.MATERIAL_NOT_REFERENCE, "the table is a limit against $ref, the layer is ${layer.materialId}; no conversion is defined", diag)

        val p = ctx.projectile
        if (!isAsTested(def, ctx)) return unresolved(UnresolvedReason.PROJECTILE_NOT_AS_TESTED, "the projectile is no longer the round the table was measured for (mass, integrity or shape changed)", diag)

        val distance = givenDistance ?: ExternalBallistics.distanceForSpeed(def.projectile, p.speedMps)?.also { diag += AmmoDiagnostic.DISTANCE_DERIVED_FROM_SPEED }
            ?: return unresolved(UnresolvedReason.INVALID_INPUT, "cannot derive a flight distance", diag)

        val answer = TableLookup.limit(spec.grid, meta, distance, ctx.angleFromNormalRad)
        val limit = when (answer) {
            is TableAnswer.OutOfRange -> return unresolved(UnresolvedReason.OUT_OF_TABLE_RANGE, answer.reason, diag)
            is TableAnswer.Limit -> answer
        }
        for (f in limit.flags) diag += when (f) {
            RangeFlag.DISTANCE_CLAMPED -> AmmoDiagnostic.DISTANCE_CLAMPED
            RangeFlag.ANGLE_CLAMPED -> AmmoDiagnostic.ANGLE_CLAMPED
            RangeFlag.DISTANCE_EXTRAPOLATED -> AmmoDiagnostic.DISTANCE_EXTRAPOLATED
            RangeFlag.ANGLE_EXTRAPOLATED -> AmmoDiagnostic.ANGLE_EXTRAPOLATED
        }

        val t = layer.thicknessM
        meta.uncertainty?.thicknessRelative?.let { rel -> if (abs(t - limit.thicknessM) <= rel * limit.thicknessM) diag += AmmoDiagnostic.WITHIN_TABLE_UNCERTAINTY }

        return if (t <= limit.thicknessM) {
            diag += AmmoDiagnostic.RESIDUAL_NOT_DERIVED_FROM_TABLE
            AmmoEvaluation.Resolved(Verdict.PERFORATED, null, EvaluationSource.TABLE, limit.thicknessM, distance, diag)
        } else {
            AmmoEvaluation.Resolved(Verdict.STOPPED, PenetrationResult.stopped(p.kineticEnergyJ), EvaluationSource.TABLE, limit.thicknessM, distance, diag)
        }
    }

    private fun hybrid(def: AmmoDefinition, spec: PenetrationSpec.Hybrid, solver: AmmoSolver, ctx: ImpactContext, layer: ArmorLayer, distance: Double?): AmmoEvaluation {
        if (!solver.canSolvePhysics) return unresolved(UnresolvedReason.SOLVER_IS_NOT_PHYSICS, "HYBRID needs a physics solver, ${solver.id} computes none")
        val tableEval = table(def, spec.table, ctx, layer, distance)
        val tab = tableEval as? AmmoEvaluation.Resolved ?: return tableEval
        val phys = solver.evaluate(ctx, layer)
        val hybridTab = tab.copy(source = EvaluationSource.HYBRID)
        if (phys !is AmmoEvaluation.Resolved) {
            return hybridTab.copy(diagnostics = tab.diagnostics + AmmoDiagnostic.PHYSICS_COULD_NOT_CROSS_CHECK)
        }
        if (phys.verdict == tab.verdict) {
            // They agree: the physics result is the more complete one (it can carry a residual).
            val residualNowKnown = if (phys.result != null) setOf(AmmoDiagnostic.RESIDUAL_NOT_DERIVED_FROM_TABLE) else emptySet()
            return hybridTab.copy(result = phys.result ?: tab.result, diagnostics = tab.diagnostics + phys.diagnostics - residualNowKnown)
        }
        val limit = tab.limitThicknessM!!
        val near = abs(layer.thicknessM - limit) <= spec.toleranceRelative * limit
        return when {
            near -> hybridTab.copy(diagnostics = tab.diagnostics + AmmoDiagnostic.NEAR_LIMIT_DISAGREEMENT)
            spec.onConflict == ConflictPolicy.FAIL -> unresolved(
                UnresolvedReason.HYBRID_CONFLICT,
                "table says ${tab.verdict}, physics says ${phys.verdict}, plate ${layer.thicknessM} m vs table limit $limit m (tolerance ${spec.toleranceRelative})",
                tab.diagnostics,
            )
            else -> hybridTab.copy(diagnostics = tab.diagnostics + AmmoDiagnostic.VERDICT_CONFLICT_TABLE_USED)
        }
    }

    /** The table was measured for the round as it left the gun; a worn rod, a lost cap or a deformed nose is a different projectile. */
    private fun isAsTested(def: AmmoDefinition, ctx: ImpactContext): Boolean {
        val p = ctx.projectile
        val d = def.projectile
        return abs(p.massRemainingKg - d.massKg) <= 1e-9 * d.massKg && p.integrity == 1.0 && p.deformation == 0.0 &&
            p.penetratorLengthRemainingM == d.penetrator?.lengthM
    }

    private fun unresolved(reason: UnresolvedReason, detail: String, diag: Set<AmmoDiagnostic> = emptySet()) =
        AmmoEvaluation.Unresolved(reason, detail, diag)
}
