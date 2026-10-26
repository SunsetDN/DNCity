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
 * A rupture in the plates of one interaction package, in world space. Where an ERA tile is either there or spent, a NERA
 * sandwich is torn *locally*: the elastomer is squeezed out and the plates are petalled around the hole, but a hit a
 * hand's width away still meets a working sandwich. The tear is an ellipse on the plate surface (an oblique hit tears a slot
 * along the line of fire, not a circle) and runs through the whole package.
 *
 * @property center middle of the rupture on the face of the package
 * @property normal unit normal of the plates
 * @property majorAxis unit vector in the plate plane (perpendicular to [normal]) along the long side of the ellipse
 */
data class ArmorTear(
    val center: V3,
    val normal: V3,
    val majorAxis: V3,
    val semiMajorM: Double,
    val semiMinorM: Double,
) {
    init {
        require(center.isFinite && normal.isFinite && majorAxis.isFinite) { "tear vectors must be finite" }
        require(kotlin.math.abs(normal.length() - 1.0) < 1e-6 && kotlin.math.abs(majorAxis.length() - 1.0) < 1e-6) { "tear axes must be unit vectors" }
        require(kotlin.math.abs(normal.dot(majorAxis)) < 1e-6) { "the long axis of a tear lies in the plate plane" }
        require(semiMinorM > 0.0 && semiMajorM >= semiMinorM && semiMajorM.isFinite()) { "tear semi axes must be positive, major >= minor" }
    }

    /** 0 at the middle, 1 on the rim, more outside; measured in the plate plane (how deep the shot is does not matter). */
    fun normalizedDistance(point: V3): Double {
        val r = point - center
        val inPlane = r - normal.scale(r.dot(normal))
        val x = inPlane.dot(majorAxis) / semiMajorM
        val y = inPlane.dot(normal.cross(majorAxis)) / semiMinorM
        return kotlin.math.sqrt(x * x + y * y)
    }
}

/** A tear and the package it is in. */
data class SlottedTear(val slot: EffectSlot, val tear: ArmorTear)

/**
 * What the traversal may *read* of a vehicle's armor: which interaction tiles are still there and where the sandwiches are
 * torn. Two vehicles with the same [ArmorConstruction] have separate runtime states, so one ERA going off does not touch the
 * other.
 */
fun interface ArmorRuntimeView {
    fun effectState(slot: EffectSlot): EffectRuntimeState

    /** Ruptures in the package at [slot], oldest first. Most armor has none. */
    fun tearsOf(slot: EffectSlot): List<ArmorTear> = emptyList()

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
    private val tears = HashMap<EffectSlot, List<ArmorTear>>()

    override fun effectState(slot: EffectSlot): EffectRuntimeState = states[slot] ?: EffectRuntimeState.INTACT

    override fun tearsOf(slot: EffectSlot): List<ArmorTear> = tears[slot] ?: emptyList()

    fun commit(result: TraversalResult) {
        for (slot in result.state.consumedEffects) states[slot] = EffectRuntimeState.SPENT
        for ((slot, tear) in result.state.tears) {
            // Bounded: a tile shot to pieces has no use for the thirtieth hole; the oldest tears go first.
            tears[slot] = (tears[slot].orEmpty() + tear).takeLast(MAX_TEARS_PER_SLOT)
        }
    }

    fun set(slot: EffectSlot, state: EffectRuntimeState) {
        states[slot] = state
    }

    companion object {
        const val MAX_TEARS_PER_SLOT = 32
    }
}
