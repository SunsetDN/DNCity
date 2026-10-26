// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

import com.google.gson.JsonObject
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Small-arms penetration: a rifle or pistol bullet against a block (by material category) or against body armor. Plain JVM
 * (no Minecraft types), so it is unit tested without the game; `compat/TaczSmallArmsBridge` connects it to TACZ.
 *
 * The one physical idea used everywhere is **energy per presented area** (`E / A`, J/m^2): a target takes a fixed amount of energy
 * per unit area per unit depth (blocks, [BlockMaterialTable]) or a fixed amount per unit area in total (soft armor,
 * [BodyArmorTable]), and a bullet gets through when it brings more than that. It is a first-order model: no yaw, deformation,
 * break-up or core hardness (an AP core and a lead core with the same `E / A` are the same here), and the velocity dependence of
 * real penetration (FM 3-06.11 shows 5.56 mm penetrating *less* at 25 m than at 200 m) is not represented.
 */
enum class SourceKind {
    /** A military technical manual or specification (TM 43-0001-27, ...). */
    MILITARY_SPEC,

    /** A published test standard (NIJ 0101.06, ...). */
    STANDARD,

    /** The ammunition maker's own data. */
    MANUFACTURER,

    /** A retailer's product listing; weakest source, used only where nothing better was found. */
    RETAILER_LISTING,

    /** An encyclopedia article (Wikipedia, ...), itself citing other sources. */
    ENCYCLOPEDIA,

    /** Computed from sourced numbers (the computation is in the note). */
    DERIVED,
}

/**
 * One bullet as fired: mass and diameter of the projectile and the speed it leaves the weapon with. [referenceVelocityMps] is the
 * source's velocity, which is usually measured some distance from the muzzle ([velocityNote] says where); it is used as the
 * speed at the first tick of flight.
 */
data class BulletSpec(
    val massKg: Double,
    val diameterM: Double,
    val referenceVelocityMps: Double,
    val velocityNote: String,
    val source: String,
    val sourceKind: SourceKind,
) {
    init {
        require(massKg > 0.0 && massKg.isFinite()) { "bullet mass must be positive" }
        require(diameterM > 0.0 && diameterM.isFinite()) { "bullet diameter must be positive" }
        require(referenceVelocityMps > 0.0 && referenceVelocityMps.isFinite()) { "bullet velocity must be positive" }
        require(source.isNotBlank()) { "bullet data needs a source" }
    }

    /** Presented (cross-section) area, m^2. */
    val areaM2: Double get() = PI / 4.0 * diameterM * diameterM

    fun energyJ(speedMps: Double): Double = 0.5 * massKg * speedMps * speedMps

    fun energyDensity(speedMps: Double): Double = energyJ(speedMps) / areaM2

    fun speedForEnergy(energyJ: Double): Double = if (energyJ <= 0.0) 0.0 else sqrt(2.0 * energyJ / massKg)

    companion object {
        fun fromJson(json: JsonObject): BulletSpec {
            val allowed = setOf(
                "mass_g", "mass_gr", "diameter_mm", "diameter_in", "velocity_mps", "velocity_fps", "velocity_note", "source", "source_kind",
            )
            require(json.keySet().all { it in allowed }) { "small_arms: unknown key(s) ${json.keySet() - allowed}" }
            return BulletSpec(
                massKg = SiUnits.mass(json, "mass"),
                diameterM = SiUnits.length(json, "diameter"),
                referenceVelocityMps = SiUnits.velocity(json, "velocity"),
                velocityNote = json.get("velocity_note")?.asString ?: "",
                source = json.get("source")?.asString ?: throw IllegalArgumentException("small_arms: missing source"),
                sourceKind = json.get("source_kind")?.asString?.let { n -> SourceKind.entries.firstOrNull { it.name == n } ?: throw IllegalArgumentException("small_arms: unknown source_kind '$n'") }
                    ?: throw IllegalArgumentException("small_arms: missing source_kind"),
            )
        }
    }
}

/**
 * What became of a bullet at one target. [energyOutJ] is 0 when it was stopped. Energy is only ever lost, never created.
 */
data class ImpactResult(val perforated: Boolean, val energyInJ: Double, val energyOutJ: Double, val exitSpeedMps: Double) {
    init {
        require(energyOutJ in 0.0..energyInJ * (1.0 + 1e-12)) { "energy out must be within 0..energy in" }
        require(perforated || energyOutJ == 0.0) { "a stopped bullet keeps no energy" }
    }

    /** Share of the striking energy that is left behind the target. */
    val energyFraction: Double get() = if (energyInJ <= 0.0) 0.0 else energyOutJ / energyInJ

    companion object {
        fun stopped(energyInJ: Double) = ImpactResult(false, energyInJ, 0.0, 0.0)

        /**
         * Takes [absorbedJ] out of the bullet; stopped when nothing is left. A bullet exactly at the limit is stopped (a standard's
         * test threat at its reference velocity is one the armor stops), with a relative tolerance for the float error of computing
         * the same energy two ways.
         */
        fun absorb(bullet: BulletSpec, speedMps: Double, absorbedJ: Double): ImpactResult {
            val e = bullet.energyJ(speedMps)
            val out = e - absorbedJ
            return if (out <= e * LIMIT_REL_TOLERANCE) stopped(e) else ImpactResult(true, e, out, bullet.speedForEnergy(out))
        }

        private const val LIMIT_REL_TOLERANCE = 1e-9
    }
}

/** Reads a quantity in the unit its source used, so sourced numbers can be copied verbatim. */
internal object SiUnits {
    private const val GRAIN_KG = 6.479891e-5
    private const val INCH_M = 0.0254
    private const val FOOT_M = 0.3048

    fun mass(json: JsonObject, name: String): Double = when {
        json.has("${name}_g") -> json.get("${name}_g").asDouble / 1000.0
        json.has("${name}_gr") -> json.get("${name}_gr").asDouble * GRAIN_KG
        else -> throw IllegalArgumentException("missing ${name}_g or ${name}_gr")
    }

    fun length(json: JsonObject, name: String): Double = when {
        json.has("${name}_m") -> json.get("${name}_m").asDouble
        json.has("${name}_mm") -> json.get("${name}_mm").asDouble / 1000.0
        json.has("${name}_in") -> json.get("${name}_in").asDouble * INCH_M
        else -> throw IllegalArgumentException("missing ${name}_m, ${name}_mm or ${name}_in")
    }

    fun velocity(json: JsonObject, name: String): Double = when {
        json.has("${name}_mps") -> json.get("${name}_mps").asDouble
        json.has("${name}_fps") -> json.get("${name}_fps").asDouble * FOOT_M
        else -> throw IllegalArgumentException("missing ${name}_mps or ${name}_fps")
    }
}
