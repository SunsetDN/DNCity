// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

/**
 * When, within one [ArmorElement.EffectPackage], an effect acts. The traversal calls a model only for the phases it lists in
 * [ArmorEffectModel.phases], always in this order for a package with layers L0..Ln:
 *
 * `BEFORE_PACKAGE`, then for every layer `BEFORE_LAYER`, the layer's penetration, `AFTER_LAYER` (whatever the layer did),
 * and finally `AFTER_PACKAGE` (only if every layer was perforated).
 *
 * `AFTER_LAYER` is an *observation* point when the layer did not perforate (STOPPED, PARTIAL, RICOCHET, SHATTERED): the layer
 * has already given its verdict and the effect only watches. It must return [ProjectileChange.Untouched], it has no energy to
 * work with except its own stored energy (see [EnergyBudget]), and it cannot end, bring back or alter the projectile. After a
 * perforation it may change the projectile.
 *
 * Once a single-use effect has used itself up, none of its later hooks run in that traversal; the layers of the package are
 * still solved, a spent tile does not remove the plates around it.
 *
 * Small and fixed on purpose: this is ballistics' own hook, not a general event bus.
 */
enum class EffectPhase { BEFORE_PACKAGE, BEFORE_LAYER, AFTER_LAYER, AFTER_PACKAGE }

/**
 * Everything an effect may look at. [context] is the impact on the current layer (or the first layer for package phases),
 * [layerIndex] is the layer inside the package (null for package phases), [layerResult] is what the layer did
 * (non-null in [EffectPhase.AFTER_LAYER] and [EffectPhase.AFTER_PACKAGE]).
 */
class EffectInvocation(
    val phase: EffectPhase,
    val context: ImpactContext,
    val pack: ArmorElement.EffectPackage,
    val spec: ArmorEffectSpec,
    val slot: EffectSlot,
    val layerIndex: Int?,
    val layerResult: PenetrationResult?,
) {
    /** True in [EffectPhase.AFTER_LAYER] when the layer did not perforate: the effect can only observe. */
    val isObservationOnly: Boolean
        get() = phase == EffectPhase.AFTER_LAYER && layerResult != null && layerResult.outcome != PenetrationOutcome.PERFORATED
}

/** What an effect did to the projectile. Three distinct things, so that "null" never has to mean two of them. */
sealed interface ProjectileChange {
    /** The effect did not touch the projectile; it carries on exactly as it was (also what an observer returns). */
    data object Untouched : ProjectileChange

    /** The projectile after the effect (a jet disrupted, a rod deflected, a cap stripped). It carries on. */
    data class Replaced(val projectile: ProjectileState) : ProjectileChange

    /** Nothing is left of the projectile: the traversal ends with [TraversalOutcome.DEFEATED_BY_EFFECT]. */
    data object Destroyed : ProjectileChange
}

/**
 * What an effect did. Pure data: the effect does not touch the vehicle. Using up a tile, changing vehicle state and applying
 * damage are committed by the server from this result ([ArmorRuntimeState.commit]).
 *
 * @property consumeRuntimeEffect the effect used itself up *in this interaction* (ERA tile fired). Only an effect whose
 *   [ArmorEffectSpec.singleUse] is true may do that. Its stored energy is available exactly then, and none of its later hooks
 *   run in the same traversal.
 */
data class EffectInteractionResult(
    val projectile: ProjectileChange,
    val consumeRuntimeEffect: Boolean,
    val generatedSpall: List<SpallSource> = emptyList(),
    val generatedBlast: BlastSource? = null,
) {
    companion object {
        fun untouched(consume: Boolean = false, spall: List<SpallSource> = emptyList(), blast: BlastSource? = null) =
            EffectInteractionResult(ProjectileChange.Untouched, consume, spall, blast)

        fun replaced(projectile: ProjectileState, consume: Boolean = false, spall: List<SpallSource> = emptyList(), blast: BlastSource? = null) =
            EffectInteractionResult(ProjectileChange.Replaced(projectile), consume, spall, blast)

        fun destroyed(consume: Boolean = false, spall: List<SpallSource> = emptyList(), blast: BlastSource? = null) =
            EffectInteractionResult(ProjectileChange.Destroyed, consume, spall, blast)
    }
}

/** ERA, NERA and other interactions of an [ArmorElement.EffectPackage]. Pure, deterministic, see [ImpactContext]. */
interface ArmorEffectModel {
    val id: Ident

    /** The phases this model wants to be called in; others are skipped. */
    val phases: Set<EffectPhase>

    fun interact(invocation: EffectInvocation): EffectInteractionResult
}
