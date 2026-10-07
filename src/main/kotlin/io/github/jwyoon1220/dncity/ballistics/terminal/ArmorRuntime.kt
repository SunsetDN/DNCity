// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

/** One effect package of one armor construction. A vehicle has its own state per slot; the construction does not. */
data class EffectSlot(val constructionId: Ident, val elementIndex: Int)

/**
 * Only two states exist: a tile is there, or it has been used up. (A "triggered but not yet spent" state would only matter for
 * delayed reactions or animation; it is not added until something needs it and its transitions are defined.)
 */
enum class EffectRuntimeState { INTACT, SPENT }

/**
 * What the traversal may *read* of a vehicle's armor: which interaction tiles are still there. Two vehicles with the same
 * [ArmorConstruction] have separate runtime states, so one ERA going off does not touch the other.
 */
fun interface ArmorRuntimeView {
    fun effectState(slot: EffectSlot): EffectRuntimeState

    companion object {
        /** A vehicle whose interaction armor is all still there. */
        val PRISTINE = ArmorRuntimeView { EffectRuntimeState.INTACT }
    }
}

/**
 * The mutable per-vehicle part. The traversal never calls [commit]; the server does, with a result it has accepted, which
 * keeps the calculation free of side effects.
 */
class ArmorRuntimeState : ArmorRuntimeView {
    private val states = HashMap<EffectSlot, EffectRuntimeState>()

    override fun effectState(slot: EffectSlot): EffectRuntimeState = states[slot] ?: EffectRuntimeState.INTACT

    fun commit(result: TraversalResult) {
        for (slot in result.state.consumedEffects) states[slot] = EffectRuntimeState.SPENT
    }

    fun set(slot: EffectSlot, state: EffectRuntimeState) {
        states[slot] = state
    }
}
