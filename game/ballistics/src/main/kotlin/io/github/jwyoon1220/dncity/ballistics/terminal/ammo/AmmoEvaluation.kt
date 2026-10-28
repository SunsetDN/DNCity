// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.ModelRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationOutcome
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult
import io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber.FullCaliberApModel
import io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber.ModelEvaluation

enum class Verdict { STOPPED, PERFORATED }

/** Where an answer came from. */
enum class EvaluationSource { TABLE, PHYSICS, HYBRID }

/** Everything that qualifies an answer. Nothing here is a failure; an answer with diagnostics is still an answer. */
enum class AmmoDiagnostic {
    /** Flight distance was inferred from the impact speed and the ammo's own drag, not supplied by the shot. */
    DISTANCE_DERIVED_FROM_SPEED,
    DISTANCE_CLAMPED, ANGLE_CLAMPED, DISTANCE_EXTRAPOLATED, ANGLE_EXTRAPOLATED,
    /** The plate is within the table's stated relative uncertainty of the limit: the verdict is a coin flip in the data's own terms. */
    WITHIN_TABLE_UNCERTAINTY,
    /** The table has no criterion, reference spec or source: usable in development, not production data. */
    UNCALIBRATED_TABLE,
    /** A table gives a limit only. The residual (speed, energy) after a perforation is not derived from it. */
    RESIDUAL_NOT_DERIVED_FROM_TABLE,
    /** `ricochet_profile`/`shatter_profile`/`spall_profile` are stored data; no solver evaluates them yet, so the verdict ignores them. */
    TERMINAL_PROFILES_NOT_EVALUATED,
    /** HYBRID: the physics model could not answer this input, so the table's verdict has no cross-check. */
    PHYSICS_COULD_NOT_CROSS_CHECK,
    /** HYBRID: table and physics disagree, but within the stated tolerance of the limit. */
    NEAR_LIMIT_DISAGREEMENT,
    /** HYBRID: table and physics disagree beyond the tolerance. The table's verdict was used ([ConflictPolicy.USE_TABLE]). */
    VERDICT_CONFLICT_TABLE_USED,
}

enum class UnresolvedReason {
    NO_AMMO_DEFINITION, UNIMPLEMENTED_SOLVER, SOLVER_IS_NOT_PHYSICS,
    PROJECTILE_NOT_AS_TESTED, TABLE_HAS_NO_REFERENCE_MATERIAL, MATERIAL_NOT_REFERENCE,
    OUT_OF_TABLE_RANGE, OUT_OF_SOLVER_MODEL, HYBRID_CONFLICT, INVALID_INPUT,
}

/**
 * What the ammo system answers for one projectile meeting one solid layer. Unlike [io.github.jwyoon1220.dncity.ballistics.terminal.PenetratorModel],
 * which must always return a result, this can say "I cannot answer" ([Unresolved]) as an ordinary value; the caller decides what that
 * means (reject the shot, ask another source). Nothing in here ever substitutes another solver.
 */
sealed interface AmmoEvaluation {
    val diagnostics: Set<AmmoDiagnostic>

    /**
     * @property result the full traversal result when the answering model produced one (stopped, or a physics perforation with a
     *   residual); null for a table perforation, whose residual is deliberately not invented
     * @property limitThicknessM the table's limit at this distance and angle, null if no table was involved
     * @property flightDistanceM the distance the table was read at, null for pure physics
     */
    data class Resolved(
        val verdict: Verdict,
        val result: PenetrationResult?,
        val source: EvaluationSource,
        val limitThicknessM: Double?,
        val flightDistanceM: Double?,
        override val diagnostics: Set<AmmoDiagnostic>,
    ) : AmmoEvaluation

    data class Unresolved(
        val reason: UnresolvedReason,
        val detail: String,
        override val diagnostics: Set<AmmoDiagnostic> = emptySet(),
    ) : AmmoEvaluation
}

/** A physics model that can answer for an [AmmoDefinition] by id of `terminal.solver`. Registered in code, referenced by data. */
interface AmmoSolver {
    val id: Ident

    /** False for the table-only marker: it is a valid `terminal.solver` for a TABLE ammo but cannot compute anything itself. */
    val canSolvePhysics: Boolean

    fun evaluate(context: ImpactContext, layer: ArmorLayer): AmmoEvaluation
}

object AmmoSolvers {
    val registry = ModelRegistry<AmmoSolver>("ammo solver") { it.id }
    val TABLE_LIMIT = Ident("dncity", "table_limit")

    /** Safe to call more than once (reload of the mod's setup); only the first call of each id registers. */
    fun registerBuiltins(fullCaliberPresets: () -> Map<Ident, io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber.FullCaliberApPreset> = { emptyMap() }) {
        if (registry[TABLE_LIMIT] == null) registry.register(TableLimitSolver)
        if (registry[FullCaliberApModel.ID] == null) registry.register(FullCaliberApAmmoSolver(fullCaliberPresets))
    }
}

/** Marker for TABLE ammo whose author does not name a physics model. It computes nothing. */
object TableLimitSolver : AmmoSolver {
    override val id: Ident = AmmoSolvers.TABLE_LIMIT
    override val canSolvePhysics = false
    override fun evaluate(context: ImpactContext, layer: ArmorLayer): AmmoEvaluation =
        AmmoEvaluation.Unresolved(UnresolvedReason.SOLVER_IS_NOT_PHYSICS, "$id is a table-only marker and has no physics")
}

/**
 * [FullCaliberApModel] as an [AmmoSolver]. The presets are read on every call so a data reload takes effect; with none loaded
 * every answer is Unresolved (no calibration data is shipped, and none may be invented).
 */
class FullCaliberApAmmoSolver(private val presets: () -> Map<Ident, io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber.FullCaliberApPreset>) : AmmoSolver {
    override val id: Ident = FullCaliberApModel.ID
    override val canSolvePhysics = true

    override fun evaluate(context: ImpactContext, layer: ArmorLayer): AmmoEvaluation =
        when (val e = FullCaliberApModel(presets()).evaluate(context, layer)) {
            is ModelEvaluation.Unresolved -> AmmoEvaluation.Unresolved(UnresolvedReason.OUT_OF_SOLVER_MODEL, e.report.reasons.joinToString("; ").ifEmpty { "outside the model" })
            is ModelEvaluation.Resolved -> AmmoEvaluation.Resolved(
                verdict = if (e.result.outcome == PenetrationOutcome.PERFORATED) Verdict.PERFORATED else Verdict.STOPPED,
                result = e.result,
                source = EvaluationSource.PHYSICS,
                limitThicknessM = null,
                flightDistanceM = null,
                diagnostics = emptySet(),
            )
        }
}
