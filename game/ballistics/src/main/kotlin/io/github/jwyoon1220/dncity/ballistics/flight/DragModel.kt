// AGENT-DONE(claude): external-ballistics-si
package io.github.jwyoon1220.dncity.ballistics.flight

/**
 * Drag coefficient as a function of Mach number. Data, never a built-in curve: no drag table is shipped with the mod and none may
 * be invented. [Constant] is the honest simplification (an author states one Cd); [Tabulated] takes a curve the author sourced.
 */
sealed interface DragModel {
    /** Cd at [mach], or null when the model does not cover it (the caller reports that, nothing is clamped). */
    fun cd(mach: Double): Double?

    data class Constant(val cd: Double) : DragModel {
        init {
            require(cd > 0.0 && cd.isFinite()) { "drag coefficient must be positive" }
        }

        override fun cd(mach: Double) = cd
    }

    /** Piecewise-linear Cd(Mach); queries outside the first and last Mach point are not covered. */
    class Tabulated(val mach: DoubleArray, val cds: DoubleArray) : DragModel {
        init {
            require(mach.size >= 2 && mach.size == cds.size) { "a drag curve needs at least two matching points" }
            require((1 until mach.size).all { mach[it] > mach[it - 1] }) { "Mach values must be strictly increasing" }
            require(mach.all { it >= 0.0 && it.isFinite() } && cds.all { it > 0.0 && it.isFinite() }) { "Mach >= 0 and Cd > 0 required" }
        }

        override fun cd(mach: Double): Double? {
            if (!mach.isFinite() || mach < this.mach.first() || mach > this.mach.last()) return null
            val i = this.mach.indexOfLast { it <= mach }.coerceIn(0, this.mach.size - 2)
            val t = (mach - this.mach[i]) / (this.mach[i + 1] - this.mach[i])
            return cds[i] + (cds[i + 1] - cds[i]) * t
        }
    }
}
