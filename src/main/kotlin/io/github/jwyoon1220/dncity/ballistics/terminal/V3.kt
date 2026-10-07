// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.math.sqrt

/** A vector in metres (positions) or metres per second (velocities). Plain data, no Minecraft types, see [Ident]. */
data class V3(val x: Double, val y: Double, val z: Double) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    fun lengthSqr(): Double = x * x + y * y + z * z

    fun length(): Double = sqrt(lengthSqr())

    fun dot(o: V3): Double = x * o.x + y * o.y + z * o.z

    fun scale(f: Double): V3 = V3(x * f, y * f, z * f)

    operator fun plus(o: V3): V3 = V3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: V3): V3 = V3(x - o.x, y - o.y, z - o.z)

    /** Unit vector, or [ZERO] for a vector too short to have a direction. */
    fun normalize(): V3 {
        val l = length()
        return if (l < 1e-9) ZERO else V3(x / l, y / l, z / l)
    }

    fun distanceTo(o: V3): Double = (this - o).length()

    companion object {
        val ZERO = V3(0.0, 0.0, 0.0)
    }
}
