// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

import com.google.gson.JsonObject

/** One test threat of a protection level: the armor must stop this bullet at this speed. */
data class TestThreat(val name: String, val massKg: Double, val diameterM: Double, val velocityMps: Double) {
    val energyDensity: Double get() = 0.5 * massKg * velocityMps * velocityMps / (Math.PI / 4.0 * diameterM * diameterM)
}

/**
 * A protection level defined by the threats it must stop. Its threshold is the largest `E / A` among them: an armor of this level
 * stops at least that much by definition of the standard, and the model assumes it stops nothing more (a lower bound on the armor).
 */
data class ProtectionLevel(val id: String, val threats: List<TestThreat>) {
    init {
        require(threats.isNotEmpty()) { "protection level $id needs at least one test threat" }
    }

    val thresholdJPerM2: Double get() = threats.maxOf { it.energyDensity }
}

/**
 * Body armor worn in equipment slots, modelled as aramid (Kevlar) treated like a **weak NERA**: a passive layer that takes a fixed
 * amount of energy per unit area out of a bullet ([ProtectionLevel.thresholdJPerM2] times the enchantment multiplier) instead of a
 * thickness a penetrator must dig through. Below that the bullet is stopped (blunt trauma only); above it the bullet gets through
 * with the rest of its energy. There is no stored energy (no explosive), so nothing is ever added to the bullet.
 *
 * Which item is which level, and how much enchantments add, is **game design**, kept in the data file next to the sourced NIJ
 * threats so the two are never mistaken for each other:
 * - `levels`: NIJ Standard-0101.06 section 2 test threats (new, unworn armor velocities);
 * - `materials`: Minecraft armor material id -> level (design);
 * - `enchantment_bonus`: per enchantment level, added to a multiplier of 1 (design; the shipped values follow the vanilla ratio of
 *   Protection to Projectile Protection);
 * - `blunt_damage_fraction`: share of the gun's damage that still lands when the armor stops the bullet (design);
 * - `wear`: how a piece loses its protection from hits ([ArmorWear], design).
 *
 * File: `data/<ns>/dncity/small_arms/body_armor.json`.
 */
class BodyArmorTable(
    val levels: Map<String, ProtectionLevel>,
    private val materialLevels: Map<String, String>,
    val enchantmentBonus: Map<String, Double>,
    val bluntDamageFraction: Double,
    val wear: ArmorWear,
) {
    init {
        materialLevels.forEach { (m, l) -> require(l in levels) { "armor material $m names unknown level $l" } }
        require(enchantmentBonus.values.all { it >= 0.0 && it.isFinite() }) { "enchantment bonuses must not be negative" }
        require(bluntDamageFraction in 0.0..1.0) { "blunt damage fraction must be within 0..1" }
    }

    fun levelFor(materialId: String): ProtectionLevel? = materialLevels[materialId]?.let { levels.getValue(it) }

    /** 1 + the sum of each known enchantment's bonus times its level. Unknown enchantments add nothing. */
    fun multiplier(enchantments: Map<String, Int>): Double = 1.0 + enchantments.entries.sumOf { (id, lvl) -> (enchantmentBonus[id] ?: 0.0) * lvl }

    /** [integrity] (0..1) scales what the piece still takes out of a bullet; 0 is a piece that no longer stops anything. */
    fun solve(level: ProtectionLevel, multiplier: Double, bullet: BulletSpec, speedMps: Double, integrity: Double = 1.0): ImpactResult {
        require(multiplier >= 1.0 && multiplier.isFinite()) { "armor multiplier must be >= 1" }
        require(integrity in 0.0..1.0) { "armor integrity must be within 0..1" }
        return ImpactResult.absorb(bullet, speedMps, level.thresholdJPerM2 * multiplier * integrity * bullet.areaM2)
    }

    /**
     * Integrity of a piece after it took the hit that brings it to [hitsAfter] hits: it loses [ArmorWear.integrityLossAtThreshold] for
     * a bullet whose `E / A` equals the undamaged piece's threshold, in proportion to the bullet's `E / A` otherwise (a rifle round
     * wears a pistol vest out much faster than a pistol round wears a plate), and nothing is left after [ArmorWear.maxHits] hits.
     */
    fun wornIntegrity(level: ProtectionLevel, multiplier: Double, bullet: BulletSpec, speedMps: Double, integrity: Double, hitsAfter: Int): Double {
        if (hitsAfter >= wear.maxHits) return 0.0
        val ratio = bullet.energyDensity(speedMps) / (level.thresholdJPerM2 * multiplier)
        return (integrity - wear.integrityLossAtThreshold * ratio).coerceIn(0.0, 1.0)
    }

    companion object {
        fun fromJson(json: JsonObject): BodyArmorTable {
            val allowed = setOf("levels", "materials", "enchantment_bonus", "blunt_damage_fraction", "wear", "source", "design_note")
            require(json.keySet().all { it in allowed }) { "body armor: unknown key(s) ${json.keySet() - allowed}" }
            val levels = (json.getAsJsonObject("levels") ?: throw IllegalArgumentException("body armor: missing levels")).entrySet().associate { (id, arr) ->
                id to ProtectionLevel(id, arr.asJsonArray.map { t ->
                    val o = t.asJsonObject
                    TestThreat(
                        name = o.get("name")?.asString ?: throw IllegalArgumentException("body armor $id: threat without name"),
                        massKg = SiUnits.mass(o, "mass"),
                        diameterM = SiUnits.length(o, "diameter"),
                        velocityMps = SiUnits.velocity(o, "velocity"),
                    )
                })
            }
            return BodyArmorTable(
                levels = levels,
                materialLevels = (json.getAsJsonObject("materials") ?: throw IllegalArgumentException("body armor: missing materials")).entrySet().associate { it.key to it.value.asString },
                enchantmentBonus = json.getAsJsonObject("enchantment_bonus")?.entrySet()?.associate { it.key to it.value.asDouble } ?: emptyMap(),
                bluntDamageFraction = json.get("blunt_damage_fraction")?.asDouble ?: throw IllegalArgumentException("body armor: missing blunt_damage_fraction (there is no default)"),
                wear = ArmorWear.fromJson(json.getAsJsonObject("wear") ?: throw IllegalArgumentException("body armor: missing wear (there is no default)")),
            )
        }
    }
}

/**
 * How a ballistic armor piece wears out (game design, not a measurement): every bullet takes [integrityLossAtThreshold] of its
 * protection per unit of `E / A` relative to the piece's threshold, and after [maxHits] hits the piece stops nothing at all.
 */
data class ArmorWear(val maxHits: Int, val integrityLossAtThreshold: Double) {
    init {
        require(maxHits >= 1) { "armor wear: max_hits must be at least 1" }
        require(integrityLossAtThreshold >= 0.0 && integrityLossAtThreshold.isFinite()) { "armor wear: integrity loss must not be negative" }
    }

    companion object {
        fun fromJson(json: JsonObject): ArmorWear {
            val allowed = setOf("max_hits", "integrity_loss_at_threshold")
            require(json.keySet().all { it in allowed }) { "armor wear: unknown key(s) ${json.keySet() - allowed}" }
            return ArmorWear(
                maxHits = json.get("max_hits")?.asInt ?: throw IllegalArgumentException("armor wear: missing max_hits"),
                integrityLossAtThreshold = json.get("integrity_loss_at_threshold")?.asDouble
                    ?: throw IllegalArgumentException("armor wear: missing integrity_loss_at_threshold"),
            )
        }
    }
}
