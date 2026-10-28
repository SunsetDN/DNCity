// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Plain JVM core of the far-player proxies (no Minecraft classes, unit tested in `FarEntityMathTest`): how often a far
 * player is sent, angle packing, snapshot interpolation, distance compression past the far clip plane and the LOD
 * occlusion ray march.
 */
object FarEntityMath {
    /**
     * Server ticks between two snapshots of one far player for one viewer. Vanilla sends players every 2 ticks
     * (`EntityType.PLAYER.updateInterval(2)`); farther away a step of a few pixels is invisible, so the rate drops.
     * The distance bands are DESIGN.
     */
    fun updateIntervalTicks(distanceBlocks: Double): Int = when {
        distanceBlocks < 512.0 -> 2
        distanceBlocks < 1024.0 -> 4
        else -> 8
    }

    /** Degrees -> one byte, vanilla's 256-step angle encoding (`Mth.packDegrees`). */
    fun packDegrees(degrees: Float): Byte = floor(degrees * 256.0f / 360.0f).toInt().toByte()

    fun unpackDegrees(packed: Byte): Float = packed * 360.0f / 256.0f

    /** Shortest-way interpolation between two angles in degrees (vanilla `Mth.rotLerp`). */
    fun lerpDegrees(t: Float, from: Float, to: Float): Float {
        var delta = (to - from) % 360.0f
        if (delta >= 180.0f) delta -= 360.0f
        if (delta < -180.0f) delta += 360.0f
        return from + t * delta
    }

    /**
     * Scale for drawing something [distance] blocks away that lies beyond [limit]: drawn at `limit` and shrunk by the same
     * factor, it covers exactly the same pixels (a uniform scale about the eye), but stays inside the projection's far plane
     * (vanilla `GameRenderer.getDepthFar()` = 4 x render distance). 1 inside the limit.
     */
    fun compressionScale(distance: Double, limit: Double): Double =
        if (distance <= limit || distance <= 0.0) 1.0 else limit / distance

    /** One received pose of a far player; angles in degrees, time in nanoseconds of the client's monotonic clock. */
    data class Sample(
        val timeNanos: Long,
        val x: Double, val y: Double, val z: Double,
        val bodyYaw: Float, val headYaw: Float, val pitch: Float,
    )

    /**
     * Snapshot interpolation: keeps the last few samples and answers the pose at a render time that lags the newest sample
     * (callers use about two send intervals of lag), so motion is smooth even with 8-tick gaps. Before the oldest sample
     * it answers the oldest, after the newest the newest (no extrapolation: a player that stopped does not slide on).
     */
    class SampleBuffer(private val capacity: Int = 8) {
        private val samples = ArrayDeque<Sample>()

        val newest: Sample? get() = samples.lastOrNull()

        fun add(sample: Sample) {
            // out-of-order or duplicate times would break the search below
            val last = samples.lastOrNull()
            if (last != null && sample.timeNanos <= last.timeNanos) return
            samples.addLast(sample)
            while (samples.size > capacity) samples.removeFirst()
        }

        fun at(timeNanos: Long): Sample? {
            if (samples.isEmpty()) return null
            if (timeNanos <= samples.first().timeNanos) return samples.first()
            if (timeNanos >= samples.last().timeNanos) return samples.last()
            for (i in 1 until samples.size) {
                val b = samples[i]
                if (b.timeNanos >= timeNanos) {
                    val a = samples[i - 1]
                    val t = ((timeNanos - a.timeNanos).toDouble() / (b.timeNanos - a.timeNanos)).toFloat()
                    return Sample(
                        timeNanos,
                        a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                        lerpDegrees(t, a.bodyYaw, b.bodyYaw), lerpDegrees(t, a.headYaw, b.headYaw), a.pitch + (b.pitch - a.pitch) * t,
                    )
                }
            }
            return samples.last()
        }
    }

    /** Solid spans of one terrain column as `[bottom, top)` block heights; `null` = no data for that column. */
    fun interface ColumnSource {
        fun spans(blockX: Int, blockZ: Int): IntArray?
    }

    /**
     * Whether terrain hides the segment [from] -> [to]. Only the part from [startDistance] blocks (along the ray) to
     * [endMargin] blocks short of [to] is marched: closer terrain is vanilla chunks, which the depth buffer already
     * handles, and the last stretch is the target's own column. The march steps [stepBlocks] along the ray and asks
     * [columns] once per column it enters. A column without data never occludes: where Distant Horizons has no data
     * it draws nothing either, so the target really is seen against empty space there.
     */
    fun isOccluded(
        from: DoubleArray, to: DoubleArray,
        startDistance: Double, endMargin: Double,
        columns: ColumnSource,
        stepBlocks: Double = 1.0,
    ): Boolean {
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val dz = to[2] - from[2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val end = length - endMargin
        if (length <= 0.0 || end <= startDistance) return false
        val ux = dx / length
        val uy = dy / length
        val uz = dz / length
        var lastX = Int.MIN_VALUE
        var lastZ = Int.MIN_VALUE
        var spans: IntArray? = null
        var s = maxOf(0.0, startDistance)
        while (s <= end) {
            val x = from[0] + ux * s
            val y = from[1] + uy * s
            val z = from[2] + uz * s
            val bx = floor(x).toInt()
            val bz = floor(z).toInt()
            if (bx != lastX || bz != lastZ) {
                spans = columns.spans(bx, bz)
                lastX = bx
                lastZ = bz
            }
            val column = spans
            if (column != null) {
                var i = 0
                while (i + 1 < column.size) {
                    if (y >= column[i] && y < column[i + 1]) return true
                    i += 2
                }
            }
            s += stepBlocks
        }
        return false
    }

    /** How far a viewer may lean or step out from cover before the server has looked again, blocks. DESIGN. */
    const val PEEK_SIDEWAYS = 1.0

    /** How far a viewer's eye may rise (a jump, standing up) before the server has looked again, blocks. DESIGN. */
    const val PEEK_UP = 1.0

    /**
     * The eye positions a viewer could look from before the next visibility check: where it is, [sideways] to either side
     * across the line of sight (horizontal, so around a corner or a ridge's shoulder) and [up] above it. Checking all of
     * them makes a player that is about to come into view arrive a little early instead of popping in late.
     */
    fun peekEyes(eye: DoubleArray, target: DoubleArray, sideways: Double = PEEK_SIDEWAYS, up: Double = PEEK_UP): List<DoubleArray> {
        val dx = target[0] - eye[0]
        val dz = target[2] - eye[2]
        val length = sqrt(dx * dx + dz * dz)
        val eyes = ArrayList<DoubleArray>(4)
        eyes += eye
        if (length > 0.0 && sideways > 0.0) {
            // (-dz, dx) is the horizontal perpendicular of the sight line
            val px = -dz / length * sideways
            val pz = dx / length * sideways
            eyes += doubleArrayOf(eye[0] + px, eye[1], eye[2] + pz)
            eyes += doubleArrayOf(eye[0] - px, eye[1], eye[2] - pz)
        }
        if (up > 0.0) eyes += doubleArrayOf(eye[0], eye[1] + up, eye[2])
        return eyes
    }

    /** Whether at least one line from any of [eyes] to any of [targets] is clear ([isOccluded] with the same arguments). */
    fun isVisibleFromAny(
        eyes: List<DoubleArray>, targets: List<DoubleArray>,
        startDistance: Double, endMargin: Double,
        columns: ColumnSource,
    ): Boolean = eyes.any { eye -> targets.any { target -> !isOccluded(eye, target, startDistance, endMargin, columns) } }

    /** Horizontal distance (vanilla tracking measures players in x/z only). */
    fun horizontalDistance(ax: Double, az: Double, bx: Double, bz: Double): Double {
        val dx = ax - bx
        val dz = az - bz
        return sqrt(dx * dx + dz * dz)
    }
}
