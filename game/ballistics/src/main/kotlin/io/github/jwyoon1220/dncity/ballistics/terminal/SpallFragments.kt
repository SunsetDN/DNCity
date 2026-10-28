// AGENT-DONE(claude): nera-bulge-model
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** One piece of spall in three dimensions: where it starts, which way and how fast it flies, how heavy it is. */
data class Fragment(val origin: V3, val velocity: V3, val massKg: Double, val materialId: Ident) {
    init {
        require(origin.isFinite && velocity.isFinite) { "fragment vectors must be finite" }
        require(massKg > 0.0 && massKg.isFinite()) { "fragment mass must be positive" }
    }

    val kineticEnergyJ: Double get() = 0.5 * massKg * velocity.lengthSqr()
}

/**
 * Turns the aggregate [SpallSource] cone into individual fragments with a 3D velocity vector each, which is what a
 * post-penetration tracer needs: every fragment is its own ray.
 *
 * - **Masses** follow Mott's fragment distribution, `N(>m) = N0 * exp(-sqrt(m / mu))`, sampled as `m = mu * ln(1/u)^2` and then
 *   normalised so the pieces add up to exactly the source's mass.
 * - **Directions** are uniform over the spherical cap of the cone (uniform in solid angle, not clustered at the axis).
 * - **Speed** is one common value, so the energy of the swarm is exactly the energy of the source: nothing is created, nothing
 *   is lost to the sampling. (A real swarm has a speed spread; that needs a model of where in the plate each piece came from.)
 *
 * Pure and deterministic: the same source and [seed] (use [SeedDomain.SPALL]) always give the same swarm.
 */
object SpallFragments {
    const val DEFAULT_COUNT = 24
    const val MAX_COUNT = 1024

    fun expand(source: SpallSource, seed: Long, count: Int = DEFAULT_COUNT): List<Fragment> {
        require(count in 1..MAX_COUNT) { "fragment count must be in 1..$MAX_COUNT" }
        if (source.fragmentMassKg <= 0.0 || source.energyJ <= 0.0) return emptyList()
        val rng = SplitMix64(seed)
        val speed = sqrt(2.0 * source.energyJ / source.fragmentMassKg)

        val weights = DoubleArray(count) { val t = ln(1.0 / rng.nextOpenUnit()); t * t }
        val total = weights.sum()
        val (t1, t2) = basisAround(source.coneAxis)
        val cosAlpha = cos(source.coneHalfAngleRad)

        return List(count) { i ->
            val cosTheta = 1.0 - rng.nextUnit() * (1.0 - cosAlpha)
            val sinTheta = sqrt((1.0 - cosTheta * cosTheta).coerceAtLeast(0.0))
            val phi = 2.0 * PI * rng.nextUnit()
            val dir = source.coneAxis.scale(cosTheta) + t1.scale(sinTheta * cos(phi)) + t2.scale(sinTheta * sin(phi))
            Fragment(source.origin, dir.normalize().scale(speed), source.fragmentMassKg * weights[i] / total, source.materialId)
        }
    }

    fun expandAll(sources: List<SpallSource>, seed: Long, countEach: Int = DEFAULT_COUNT): List<Fragment> =
        sources.flatMapIndexed { i, s -> expand(s, seed xor (i.toLong() * -0x61c8864680b583ebL), countEach) }

    /** Two unit vectors that, with [axis], make a right-handed orthonormal frame. */
    internal fun basisAround(axis: V3): Pair<V3, V3> {
        val helper = if (abs(axis.x) < 0.9) V3(1.0, 0.0, 0.0) else V3(0.0, 1.0, 0.0)
        val t1 = axis.cross(helper).normalize()
        return t1 to axis.cross(t1).normalize()
    }

    /** SplitMix64, the same finaliser [ImpactContext.seedFor] uses, as a stream. */
    private class SplitMix64(private var state: Long) {
        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** [0, 1) */
        fun nextUnit(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

        /** (0, 1], safe to take a logarithm of */
        fun nextOpenUnit(): Double = 1.0 - nextUnit()
    }
}
