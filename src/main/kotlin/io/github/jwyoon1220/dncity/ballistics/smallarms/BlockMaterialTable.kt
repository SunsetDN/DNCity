// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

import com.google.gson.JsonObject

/** The coarse material a block is made of, for bullets. Which block is which is decided on the Minecraft side (tags, then sound type). */
enum class BlockCategory { GLASS, FOLIAGE, WOOD, SOIL, STONE, METAL, UNKNOWN }

enum class BlockBehavior {
    /** The block stands for something a bullet goes through without measurable loss (a window pane, leaves). */
    PASS,

    /** Real material over the bullet's path through the block's collision shape, with a calibrated resistance. */
    RESIST,

    /** Nothing is known; the block stops every bullet (the safe default: no wall is made shoot-through by guessing). */
    STOP,
}

/**
 * @property resistancePa energy the material takes per unit area per unit depth (J/m^3 = Pa); only for [BlockBehavior.RESIST]
 * @property depthIsLowerBound the calibration depth was a perforation, so the real depth is at least that: the material is made
 *   *harder* than reality, never softer
 */
data class MaterialSpec(
    val category: BlockCategory,
    val behavior: BlockBehavior,
    val resistancePa: Double?,
    val depthIsLowerBound: Boolean,
    val source: String,
) {
    init {
        require((behavior == BlockBehavior.RESIST) == (resistancePa != null)) { "$category: only RESIST has a resistance" }
        require(resistancePa == null || (resistancePa > 0.0 && resistancePa.isFinite())) { "$category: resistance must be positive" }
        require(source.isNotBlank()) { "$category needs a source" }
    }

    /** Depth a bullet with this energy density gets into the material. */
    fun depthM(energyDensity: Double): Double = when (behavior) {
        BlockBehavior.PASS -> Double.POSITIVE_INFINITY
        BlockBehavior.STOP -> 0.0
        BlockBehavior.RESIST -> energyDensity / resistancePa!!
    }
}

/**
 * Blocks by material category. Every RESIST material is calibrated from **one** sourced depth of **one** reference round: the
 * resistance is `(E / A of the reference round) / depth`. The model then says depth is proportional to `E / A` for any other bullet;
 * that proportionality is the model's assumption, not data.
 *
 * File: `data/<ns>/dncity/small_arms/block_materials.json` (see the shipped one for the sources).
 */
class BlockMaterialTable(val reference: BulletSpec, val referenceDepthNote: String, private val materials: Map<BlockCategory, MaterialSpec>) {
    init {
        require(BlockCategory.entries.all { it in materials }) { "block materials: every category needs an entry, missing ${BlockCategory.entries - materials.keys}" }
    }

    fun material(category: BlockCategory): MaterialSpec = materials.getValue(category)

    /**
     * A bullet at [speedMps] crossing [pathM] metres of [category] (the length of its flight line inside the block's shape).
     * PASS loses nothing; STOP stops; RESIST loses `R * A * path`.
     */
    fun solve(category: BlockCategory, bullet: BulletSpec, speedMps: Double, pathM: Double): ImpactResult {
        require(pathM >= 0.0 && pathM.isFinite()) { "path must be finite and not negative" }
        val m = material(category)
        val e = bullet.energyJ(speedMps)
        return when (m.behavior) {
            BlockBehavior.PASS -> ImpactResult(speedMps > 0.0, e, e, speedMps)
            BlockBehavior.STOP -> ImpactResult.stopped(e)
            BlockBehavior.RESIST -> ImpactResult.absorb(bullet, speedMps, m.resistancePa!! * bullet.areaM2 * pathM)
        }
    }

    companion object {
        fun fromJson(json: JsonObject): BlockMaterialTable {
            val allowed = setOf("reference_round", "reference_note", "materials")
            require(json.keySet().all { it in allowed }) { "block materials: unknown key(s) ${json.keySet() - allowed}" }
            val reference = BulletSpec.fromJson(json.getAsJsonObject("reference_round") ?: throw IllegalArgumentException("block materials: missing reference_round"))
            val referenceDensity = reference.energyDensity(reference.referenceVelocityMps)
            val entries = json.getAsJsonObject("materials") ?: throw IllegalArgumentException("block materials: missing materials")
            val materials = entries.entrySet().associate { (name, el) ->
                val category = BlockCategory.entries.firstOrNull { it.name == name.uppercase() } ?: throw IllegalArgumentException("block materials: unknown category '$name'")
                val o = el.asJsonObject
                val allowedM = setOf("behavior", "reference_depth_in", "reference_depth_m", "depth_is_lower_bound", "source")
                require(o.keySet().all { it in allowedM }) { "block materials $name: unknown key(s) ${o.keySet() - allowedM}" }
                val behavior = o.get("behavior")?.asString?.let { b -> BlockBehavior.entries.firstOrNull { it.name == b } ?: throw IllegalArgumentException("block materials $name: unknown behavior '$b'") }
                    ?: throw IllegalArgumentException("block materials $name: missing behavior")
                val resistance = if (behavior == BlockBehavior.RESIST) referenceDensity / SiUnits.length(o, "reference_depth") else null
                category to MaterialSpec(
                    category, behavior, resistance,
                    depthIsLowerBound = o.get("depth_is_lower_bound")?.asBoolean ?: false,
                    source = o.get("source")?.asString ?: throw IllegalArgumentException("block materials $name: missing source"),
                )
            }
            return BlockMaterialTable(reference, json.get("reference_note")?.asString ?: "", materials)
        }
    }
}
