// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.math.cos

/** Where the shot is: still inside the armor, or behind it where modules and crew are. (Before the stack comes the external hit.) */
enum class TraversalPhase { ARMOR_STACK, INTERNAL_SPACE }

/** Why the traversal of one [ArmorStack] ended. Whatever follows (damage, post-penetration tracing) starts from this. */
enum class TraversalOutcome {
    /** Did not get through (stopped, or only a crater/bulge). The last layer's outcome is in [TraversalResult.lastLayerOutcome]. */
    STOPPED_IN_ARMOR,
    /** Bounced off a layer; [TraversalResult.residual] flies on with the new direction. */
    RICOCHETED,
    /** The projectile broke up. Fragments, if any, are in [TraversalState.spall]. */
    SHATTERED,
    /** An interaction armor ended it (jet fully disrupted, ...). */
    DEFEATED_BY_EFFECT,
    /** Through every element and out the other side; there was no internal space. */
    EXITED_CONSTRUCTION,
    /** Through the armor into the internal space: from here on it is residual-projectile tracing, spall, modules, crew, fuze. */
    ENTERED_INTERNAL_SPACE,
}

/**
 * The state of *this passage through one armor construction*. The physical state of the projectile itself is
 * [projectile] ([ProjectileState]); they are kept apart on purpose: the projectile is what flies, this is what has happened
 * to it on the way. Position and direction are derived from the projectile, so the two cannot disagree.
 *
 * @property elementIndex the element being (or last) processed
 * @property accumulatedDistanceM path actually flown inside the construction, layers and gaps, line of sight, not thickness
 */
data class TraversalState(
    val projectile: ProjectileState,
    val elementIndex: Int,
    val accumulatedDistanceM: Double,
    val depositedEnergyJ: Double,
    val spall: List<SpallSource>,
    val blasts: List<BlastSource>,
    val consumedEffects: List<EffectSlot>,
    val phase: TraversalPhase,
) {
    val position: V3 get() = projectile.position
    val direction: V3 get() = projectile.velocity.normalize()
}

enum class EventKind {
    GAP_CROSSED, LAYER_SOLVED, EFFECT_CALLED,
    /** The effect was already spent on this vehicle before the traversal began. */
    EFFECT_SKIPPED_SPENT,
    /** The effect used itself up earlier in this traversal; a hook it would have run in was skipped. */
    EFFECT_SKIPPED_CONSUMED_THIS_TRAVERSAL,
    INTERNAL_SPACE_ENTERED,
}

/** One step of a traversal, in the order it happened. Mainly for tests and debugging, but also what a replay would show. */
data class TraversalEvent(
    val kind: EventKind,
    val elementIndex: Int,
    val layerIndex: Int?,
    val effectPhase: EffectPhase?,
    val layerOutcome: PenetrationOutcome?,
    /** Angle from the surface normal at this step, derived from the direction of flight right then. */
    val localAngleRad: Double?,
    val pathM: Double,
)

data class TraversalResult(
    val outcome: TraversalOutcome,
    val state: TraversalState,
    val events: List<TraversalEvent>,
    val lastLayerOutcome: PenetrationOutcome?,
) {
    /** The projectile that carries on after the construction (or bounced off), null if it did not. */
    val residual: ProjectileState?
        get() = when (outcome) {
            TraversalOutcome.RICOCHETED, TraversalOutcome.EXITED_CONSTRUCTION, TraversalOutcome.ENTERED_INTERNAL_SPACE -> state.projectile
            else -> null
        }
}

/**
 * Walks a projectile through an [ArmorStack] element by element. It owns no physics: layers go to the [PenetratorModel] named
 * by the projectile, effect packages to their [ArmorEffectModel]; it carries the changed [ProjectileState] from one element
 * to the next, derives the impact angle again at every layer, counts the real flown distance and enforces the rules every
 * model must obey:
 *
 * - **Canonical state belongs to the traversal.** After a perforation the residual is placed at `perforationPoint`, whatever
 *   position the solver wrote into it; the path through the layer (previous position to perforation point) must be finite and
 *   positive.
 * - **No energy from nowhere** ([EnergyBudget]): a penetrator model may only use the projectile's energy; an effect model may
 *   also use its [ArmorEffectSpec.storedEnergyJ], and only in the interaction in which it uses itself up.
 * - **Consumption is explicit and legal**: only a `singleUse` effect may report consumption, once.
 * - **A stopped projectile stays stopped**: an effect observing a layer that did not perforate cannot revive it.
 *
 * Pure: it reads the [ArmorRuntimeView] but never changes it. The server decides whether to accept a result and then
 * [ArmorRuntimeState.commit]s it. Same inputs (including [seed]) give the same result.
 */
class ArmorTraverser(
    private val catalog: BallisticsCatalog,
    private val penetrators: ModelRegistry<PenetratorModel> = PenetratorModels.registry,
    private val effects: ModelRegistry<ArmorEffectModel> = ArmorEffectModels.registry,
) {
    fun traverse(
        stack: ArmorStack,
        projectile: ProjectileState,
        runtime: ArmorRuntimeView = ArmorRuntimeView.PRISTINE,
        seed: Long = 0L,
    ): TraversalResult = Run(stack, runtime, seed, projectile).execute()

    private inner class Run(
        val stack: ArmorStack,
        val runtime: ArmorRuntimeView,
        val seed: Long,
        start: ProjectileState,
    ) {
        var state = TraversalState(start, 0, 0.0, 0.0, emptyList(), emptyList(), emptyList(), TraversalPhase.ARMOR_STACK)
        val events = ArrayList<TraversalEvent>()
        var lastLayer: PenetrationOutcome? = null
        val fuzeSpec: FuzeSpec? = catalog.projectile(start.definitionId).payload?.fuze

        fun end(outcome: TraversalOutcome) = TraversalResult(outcome, state, events.toList(), lastLayer)

        fun execute(): TraversalResult {
            for ((index, element) in stack.elements.withIndex()) {
                state = state.copy(elementIndex = index)
                val stopped: TraversalOutcome? = when (element) {
                    is ArmorElement.Solid -> terminalOf(solveLayer(index, element.layer, null))
                    is ArmorElement.Gap -> { crossGap(index, element); null }
                    is ArmorElement.EffectPackage -> runPackage(index, element)
                    is ArmorElement.InternalSpace -> {
                        state = state.copy(phase = TraversalPhase.INTERNAL_SPACE)
                        events += TraversalEvent(EventKind.INTERNAL_SPACE_ENTERED, index, null, null, null, null, 0.0)
                        return end(TraversalOutcome.ENTERED_INTERNAL_SPACE)
                    }
                }
                if (stopped != null) return end(stopped)
            }
            return end(TraversalOutcome.EXITED_CONSTRUCTION)
        }

        fun terminalOf(r: PenetrationResult): TraversalOutcome? = when (r.outcome) {
            PenetrationOutcome.STOPPED, PenetrationOutcome.PARTIAL -> TraversalOutcome.STOPPED_IN_ARMOR
            PenetrationOutcome.RICOCHET -> TraversalOutcome.RICOCHETED
            PenetrationOutcome.SHATTERED -> TraversalOutcome.SHATTERED
            PenetrationOutcome.PERFORATED -> null
        }

        fun contextAt(index: Int, layerIndex: Int?, domain: SeedDomain): ImpactContext {
            val normal = stack.surface.normalAt(index, state.position)
            return ImpactContext.of(state.projectile, catalog, state.position, normal, ImpactContext.seedFor(seed, stack.constructionId, index, layerIndex ?: -1, domain))
        }

        fun solveLayer(index: Int, layer: ArmorLayer, layerIndex: Int?): PenetrationResult {
            val ctx = contextAt(index, layerIndex, SeedDomain.PENETRATION)
            val model = penetrators.getOrThrow(ctx.definition.terminalModel)
            val result = model.solve(ctx, layer)
            val budget = EnergyBudget.ofLayer(state.projectile.kineticEnergyJ, result)
            check(budget.isSound) { "penetrator model ${model.id} created energy: $budget" }
            lastLayer = result.outcome
            var path = 0.0
            var projectile = state.projectile
            if (result.outcome == PenetrationOutcome.PERFORATED) {
                val point = result.perforationPoint!!
                path = state.position.distanceTo(point)
                check(path.isFinite() && path > 0.0) { "penetrator model ${model.id} returned an impossible path through the layer: $path m" }
                projectile = result.residual!!.let { it.copy(position = point, fuze = it.fuze.flown(path, fuzeSpec)) }
            } else if (result.outcome == PenetrationOutcome.RICOCHET) {
                projectile = result.residual!!
            }
            state = state.copy(
                projectile = projectile,
                accumulatedDistanceM = state.accumulatedDistanceM + path,
                depositedEnergyJ = state.depositedEnergyJ + result.depositedEnergyJ,
                spall = if (result.spall != null) state.spall + result.spall else state.spall,
            )
            events += TraversalEvent(EventKind.LAYER_SOLVED, index, layerIndex, null, result.outcome, ctx.angleFromNormalRad, path)
            return result
        }

        fun crossGap(index: Int, gap: ArmorElement.Gap) {
            val normal = stack.surface.normalAt(index, state.position)
            val angle = ImpactAngle.fromNormal(state.direction, normal)
            // A ray almost parallel to the surfaces would take an unbounded path; cap the obliquity at ~89.9 degrees.
            val path = gap.distanceM / cos(angle).coerceAtLeast(1e-3)
            val p = state.projectile
            state = state.copy(
                projectile = p.copy(position = p.position + state.direction.scale(path), fuze = p.fuze.flown(path, fuzeSpec)),
                accumulatedDistanceM = state.accumulatedDistanceM + path,
            )
            events += TraversalEvent(EventKind.GAP_CROSSED, index, null, null, null, angle, path)
        }

        fun domainOf(phase: EffectPhase) = when (phase) {
            EffectPhase.BEFORE_PACKAGE -> SeedDomain.EFFECT_BEFORE_PACKAGE
            EffectPhase.BEFORE_LAYER -> SeedDomain.EFFECT_BEFORE_LAYER
            EffectPhase.AFTER_LAYER -> SeedDomain.EFFECT_AFTER_LAYER
            EffectPhase.AFTER_PACKAGE -> SeedDomain.EFFECT_AFTER_PACKAGE
        }

        fun runPackage(index: Int, pack: ArmorElement.EffectPackage): TraversalOutcome? {
            val slot = EffectSlot(stack.constructionId, index)
            val spec = catalog.effect(pack.effectId)
            val spentBefore = runtime.effectState(slot) == EffectRuntimeState.SPENT
            val model: ArmorEffectModel? = if (spentBefore) null else effects.getOrThrow(spec.solver)
            var available = !spentBefore // false once the effect has used itself up here
            if (spentBefore) events += TraversalEvent(EventKind.EFFECT_SKIPPED_SPENT, index, null, null, null, null, 0.0)

            fun hook(phase: EffectPhase, layerIndex: Int?, layerResult: PenetrationResult?): TraversalOutcome? {
                if (model == null || phase !in model.phases) return null
                if (!available) {
                    events += TraversalEvent(EventKind.EFFECT_SKIPPED_CONSUMED_THIS_TRAVERSAL, index, layerIndex, phase, null, null, 0.0)
                    return null
                }
                val ctx = contextAt(index, layerIndex, domainOf(phase))
                val invocation = EffectInvocation(phase, ctx, pack, spec, slot, layerIndex, layerResult)
                val r = model.interact(invocation)

                // contract: consumption is only for single-use effects
                check(!r.consumeRuntimeEffect || spec.singleUse) { "effect model ${model.id} reported consumption of effect ${spec.id}, which is not single use" }
                // contract: an observer of a layer that already ended the projectile cannot end, change or revive it
                check(!invocation.isObservationOnly || r.projectile == ProjectileChange.Untouched) {
                    "effect model ${model.id} tried to revive or alter a projectile that ${layerResult!!.outcome} already ended"
                }

                // energy: an observer has none of the projectile's; otherwise the projectile's own. Plus the effect's stored
                // energy, only in the interaction in which it uses itself up.
                val inputJ = if (invocation.isObservationOnly) 0.0 else state.projectile.kineticEnergyJ
                val budget = EnergyBudget.ofEffect(inputJ, if (r.consumeRuntimeEffect) spec.storedEnergyJ else 0.0, r)
                check(budget.isSound) { "effect model ${model.id} created energy: $budget" }

                state = state.copy(
                    projectile = (r.projectile as? ProjectileChange.Replaced)?.projectile ?: state.projectile,
                    spall = state.spall + r.generatedSpall,
                    blasts = if (r.generatedBlast != null) state.blasts + r.generatedBlast else state.blasts,
                    consumedEffects = if (r.consumeRuntimeEffect) state.consumedEffects + slot else state.consumedEffects,
                )
                if (r.consumeRuntimeEffect) available = false
                events += TraversalEvent(EventKind.EFFECT_CALLED, index, layerIndex, phase, null, ctx.angleFromNormalRad, 0.0)
                return if (r.projectile == ProjectileChange.Destroyed) TraversalOutcome.DEFEATED_BY_EFFECT else null
            }

            hook(EffectPhase.BEFORE_PACKAGE, null, null)?.let { return it }
            var last: PenetrationResult? = null
            for ((li, layer) in pack.layers.withIndex()) {
                hook(EffectPhase.BEFORE_LAYER, li, null)?.let { return it }
                val r = solveLayer(index, layer, li)
                val byEffect = hook(EffectPhase.AFTER_LAYER, li, r) // always: the layer was solved, whatever it did
                terminalOf(r)?.let { return it } // the layer's own verdict outranks an observer
                byEffect?.let { return it }
                last = r
            }
            return hook(EffectPhase.AFTER_PACKAGE, null, last)
        }
    }
}
