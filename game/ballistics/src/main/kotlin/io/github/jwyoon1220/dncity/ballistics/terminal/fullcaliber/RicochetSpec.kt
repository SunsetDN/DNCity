// AGENT-DONE(claude): slope-ricochet-tables
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.terminal.V3

/**
 * Whether a full-caliber projectile glances off instead of biting in, as data: a probability over the angle from the plate normal,
 * checked *before* penetration. A projectile that ricochets leaves along the mirror direction with [speedRetention] of its speed.
 *
 * - The probability table is strictly ascending in angle, starts at 0 degrees, and its probabilities are within 0..1 and do not
 *   decrease with the angle. Between grid points it is linear; beyond the last angle nothing is answered (`null`).
 * - Where the probability is 1 the ricochet is certain, so the model answers RICOCHET even at angles its penetration branch does not
 *   cover. Where it is strictly between 0 and 1 the outcome is drawn from the impact's seed ([draw]), so the same shot always gives
 *   the same answer; the penetration branch must then cover the angle too, or the model declines.
 * - [speedRetention] (residual / striking speed, in (0, 1]) is a single number: no dependence on angle, speed or plate is claimed.
 *   Both it and the table need a named source; this class knows no values.
 */
data class RicochetSpec(
    val anglesRad: List<Double>,
    val probabilities: List<Double>,
    val source: String,
    val speedRetention: Double,
    val speedRetentionSource: String,
) {
    init {
        require(anglesRad.size >= 2 && anglesRad.size == probabilities.size) { "ricochet table needs at least two angles, one probability each" }
        require(anglesRad.first() == 0.0) { "ricochet table angles must start at 0 degrees" }
        require(anglesRad.zipWithNext().all { (a, b) -> b > a }) { "ricochet table angles must be strictly ascending" }
        require(anglesRad.last() < Math.PI / 2) { "ricochet table angles must be below 90 degrees" }
        require(probabilities.all { it.isFinite() && it in 0.0..1.0 }) { "ricochet probabilities must be within 0..1" }
        require(probabilities.zipWithNext().all { (a, b) -> b >= a }) { "ricochet probability must not decrease with the angle" }
        require(source.isNotBlank()) { "ricochet table needs a source" }
        require(speedRetention.isFinite() && speedRetention > 0.0 && speedRetention <= 1.0) { "ricochet speed retention must be in (0, 1]" }
        require(speedRetentionSource.isNotBlank()) { "ricochet speed retention needs a source" }
    }

    /** Probability of a ricochet at [angleRad], or null beyond the table. */
    fun probability(angleRad: Double): Double? {
        if (!angleRad.isFinite() || angleRad < 0.0 || angleRad > anglesRad.last()) return null
        val (i, t) = SlopeTable.bracket(anglesRad, angleRad)
        return probabilities[i] + (probabilities[i + 1] - probabilities[i]) * t
    }

    companion object {
        fun fromJson(json: JsonObject): RicochetSpec {
            val allowed = setOf("angles_deg", "probability", "source", "speed_retention", "speed_retention_source")
            require(json.keySet().all { it in allowed }) { "ricochet: unknown key(s) ${json.keySet() - allowed}" }
            fun array(k: String) = json.getAsJsonArray(k) ?: throw IllegalArgumentException("ricochet: missing $k")
            fun text(k: String) = json.get(k)?.asString ?: throw IllegalArgumentException("ricochet: missing $k")
            return RicochetSpec(
                anglesRad = array("angles_deg").map { Math.toRadians(it.asDouble) },
                probabilities = array("probability").map { it.asDouble },
                source = text("source"),
                speedRetention = json.get("speed_retention")?.asDouble ?: throw IllegalArgumentException("ricochet: missing speed_retention (there is no default)"),
                speedRetentionSource = text("speed_retention_source"),
            )
        }

        /**
         * A uniform number in [0, 1) for the ricochet decision of the impact with [seed]. The seed is mixed with a fixed tag, so this
         * stream never coincides with another use of the same impact seed (spall, fuze) and needs no call order.
         */
        fun draw(seed: Long): Double {
            var z = seed xor RICOCHET_TAG
            z += -0x61c8864680b583ebL // SplitMix64
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return (z ushr 11) * (1.0 / (1L shl 53))
        }

        /** Mirror of the flight [direction] on a surface with outward unit [normal]. */
        fun reflect(direction: V3, normal: V3): V3 = direction - normal.scale(2.0 * direction.dot(normal))

        private const val RICOCHET_TAG = 0x52494345L // "RICE"; any fixed constant, it only separates this stream
    }
}
