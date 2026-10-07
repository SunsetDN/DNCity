// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

/**
 * When, within one [ArmorElement.EffectPackage], an effect acts. The traversal calls a model only for the phases it lists in
 * [ArmorEffectModel.phases], always in this order for a package with layers L0..Ln:
 *
 * `BEFORE_PACKAGE`, then for every layer `BEFORE_LAYER`, the layer's penetration, `AFTER_LAYER` (only if the layer was
 * perforated), and finally `AFTER_PACKAGE` (only if the last layer was perforated).
 *
 * A reactive tile that must work on contact uses `BEFORE_LAYER` or `BEFORE_PACKAGE`; one that disturbs what got through
 * uses `AFTER_*`. Small and fixed on purpose: this is ballistics' own hook, not a general event bus.
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
)

/**
 * What an effect did. Pure data: the effect does not touch the vehicle. Using up a tile, changing vehicle state and applying
 * damage are committed by the server from this result ([ArmorRuntimeState.commit]).
 *
 * @property updatedProjectile the projectile after the effect (a jet disrupted, a rod deflected), null if nothing is left
 * @property consumeRuntimeEffect the effect used itself up (ERA tile fired)
 * @property continueTraversal false ends the traversal here ([TraversalOutcome.DEFEATED_BY_EFFECT]); required when nothing is left
 */
data class EffectInteractionResult(
    val updatedProjectile: ProjectileState?,
    val consumeRuntimeEffect: Boolean,
    val generatedSpall: List<SpallSource> = emptyList(),
    val generatedBlast: BlastSource? = null,
    val continueTraversal: Boolean = true,
) {
    init {
        require(updatedProjectile != null || !continueTraversal) { "nothing left of the projectile: traversal cannot continue" }
    }
}

/** ERA, NERA and other interactions of an [ArmorElement.EffectPackage]. Pure, deterministic, see [ImpactContext]. */
interface ArmorEffectModel {
    val id: Ident

    /** The phases this model wants to be called in; others are skipped. */
    val phases: Set<EffectPhase>

    fun interact(invocation: EffectInvocation): EffectInteractionResult
}
