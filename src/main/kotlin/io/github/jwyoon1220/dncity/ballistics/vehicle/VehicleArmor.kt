// AGENT-DONE(claude): vehicle-armor-penetration
package io.github.jwyoon1220.dncity.ballistics.vehicle

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.ModuleLayouts
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.ModuleSpec
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Vehicle armor penetration, War Thunder-style hit outcome (penetrated / not penetrated / ricochet) for SuperbWarfare vehicles.
 * Plain JVM: no Minecraft types, so it is unit tested without the game ([SbwVehicleArmorBridge][io.github.jwyoon1220.dncity.compat.SbwVehicleArmorBridge]
 * does the geometry against the vehicle's OBBs and applies the result).
 *
 * Deliberately a first-order line-of-sight model, separate from the `terminal/` traversal pipeline (which has no solver
 * data yet): every armor value is one RHA-equivalent number per box face, as protection against a hit square to that
 * face; obliquity multiplies it by 1/cos. All numbers are community ESTIMATEs or DESIGN values, see each data file.
 */

enum class PenetratorKind {
    /** Kinetic (APFSDS, AP, small arms): uses the plate's [PlateArmor.ke]; loses penetration with distance. */
    KE,
    /** Shaped charge (HEAT, ATGM, RPG): uses [PlateArmor.ce]; penetration independent of distance. */
    HEAT,
    /** Blast/fragments: uses [PlateArmor.ke]; only defeats thin armor. */
    HE,
}

/** One plate's protection, mm RHA-equivalent for a hit square to the face, plus an optional reactive layer on top. */
data class PlateArmor(val ke: Double, val ce: Double, val reactive: ReactiveArmor? = null) {
    fun against(kind: PenetratorKind): Double = if (kind == PenetratorKind.HEAT) ce else ke

    companion object {
        fun fromJson(obj: JsonObject): PlateArmor {
            val ke = obj["ke"].asDouble
            val ce = obj["ce"]?.asDouble ?: ke
            require(ke >= 0 && ce >= 0) { "armor must be >= 0 mm" }
            require(!(obj.has("era") && obj.has("nera"))) { "a plate has either 'era' or 'nera', not both" }
            val reactive = obj.getAsJsonObject("era")?.let { ReactiveArmor.fromJson(ReactiveType.ERA, it) }
                ?: obj.getAsJsonObject("nera")?.let { ReactiveArmor.fromJson(ReactiveType.NERA, it) }
            return PlateArmor(ke, ce, reactive)
        }
    }
}

enum class ReactiveType {
    /** Explosive bricks: each one fires once, the spot is bare afterwards. */
    ERA,
    /** Plate/elastomer/plate sandwiches (see `terminal/nera/NeraBulgeModel`): no explosive, survives hits, but tears where hit. */
    NERA,
}

/**
 * A reactive layer on a plate (`"era": {...}` or `"nera": {...}` in a face) while the spot hit is still intact: [ke]/[ce] mm on top
 * of the plate's own value (how Kontakt-5 is rated: 200-250 mm vs APFSDS, 600 mm vs HEAT), and/or [keReduction]/[ceReduction], the
 * share of the incoming penetration it takes away (how Kontakt-1 is rated: -52 % for tank HEAT rounds). A hit that sets it off
 * ([triggeredBy]) uses up an ERA brick or tears the NERA sandwich in a circle of [tileM] around the hit, which the vehicle's damage
 * state remembers.
 */
data class ReactiveArmor(
    val type: ReactiveType,
    val ke: Double,
    val ce: Double,
    val tileM: Double,
    val keReduction: Double = 0.0,
    val ceReduction: Double = 0.0,
) {
    init {
        require(ke >= 0 && ce >= 0) { "reactive armor must be >= 0 mm" }
        require(keReduction in 0.0..0.95 && ceReduction in 0.0..0.95) { "reactive reductions must be 0..0.95" }
        require(tileM > 0) { "reactive tile size must be > 0" }
    }

    /**
     * Equivalent extra armor against a projectile of [penetrationMm]: a share r taken off the penetration is the same as r * pen
     * of armor added, since pen * (1 - r) > armor exactly when pen > armor + r * pen.
     */
    fun equivalentMm(kind: PenetratorKind, penetrationMm: Double): Double = against(kind) + penetrationMm * when (kind) {
        PenetratorKind.HEAT -> ceReduction
        PenetratorKind.KE -> keReduction
        PenetratorKind.HE -> 0.0
    }

    fun against(kind: PenetratorKind): Double = when (kind) {
        PenetratorKind.HEAT -> ce
        PenetratorKind.KE -> ke
        PenetratorKind.HE -> 0.0
    }

    /** Shaped charges and kinetic rounds from autocannon calibre up set it off; rifle bullets and blast do not. */
    fun triggeredBy(kind: PenetratorKind, caliberMm: Double): Boolean =
        kind == PenetratorKind.HEAT || (kind == PenetratorKind.KE && caliberMm >= MIN_TRIGGER_CALIBER_MM)

    /**
     * NERA works by its plates moving across the line of flight, so it is weakest square on (the terminal model gives zero there;
     * a real array keeps some from its thickness). ERA bricks are already mounted at an angle, so they do not care.
     */
    fun obliquityShape(angleDeg: Double): Double = when (type) {
        ReactiveType.ERA -> 1.0
        ReactiveType.NERA -> NERA_SQUARE_SHARE + (1 - NERA_SQUARE_SHARE) * min(1.0, sin(Math.toRadians(angleDeg)) / sin(Math.toRadians(30.0)))
    }

    companion object {
        const val MIN_TRIGGER_CALIBER_MM = 20.0
        private const val NERA_SQUARE_SHARE = 0.4

        fun fromJson(type: ReactiveType, obj: JsonObject) = ReactiveArmor(
            type,
            ke = obj["ke"]?.asDouble ?: 0.0,
            ce = obj["ce"]?.asDouble ?: 0.0,
            tileM = obj["tile_m"]?.asDouble ?: if (type == ReactiveType.ERA) 0.35 else 0.5,
            keReduction = obj["ke_reduction"]?.asDouble ?: 0.0,
            ceReduction = obj["ce_reduction"]?.asDouble ?: 0.0,
        )
    }
}

/** What a projectile can penetrate. JSON block `armor_penetration` in a shell file or cartridge round. */
data class PenetratorSpec(
    val kind: PenetratorKind,
    /** mm RHA at [rangeM] (KE) or at any range (HEAT/HE), square impact. */
    val mm: Double,
    val rangeM: Double = 0.0,
    /** KE only: mm lost per km of flight (gained when closer than [rangeM]). */
    val lossPerKmMm: Double = 0.0,
    /** At or above this impact angle (from the plate normal) the projectile glances off; from [ArmorSolver.RICOCHET_RAMP_DEG] below it, it may. */
    val ricochetDeg: Double = defaultRicochetDeg(kind),
    /** KE only: how much a blunt or capped nose turns into the plate on impact, taken off the angle before the slope counts. */
    val normalizationDeg: Double = 0.0,
    /** Explosive filler, kg TNT; 0 = estimate from the shell ([io.github.jwyoon1220.dncity.ballistics.vehicle.damage.Warhead]). */
    val fillerKg: Double = 0.0,
    /** APHE: flight after the plate before the filler goes off, metres. */
    val fuseDelayM: Double = 0.0,
    /** APHE: thinnest plate (mm) that arms the fuze; a thinner one is passed through without it. */
    val fuseSensitivityMm: Double = 0.0,
    /** HEAT only: a precursor charge sets the ERA off first, so explosive bricks add nothing (NERA still counts). */
    val tandem: Boolean = false,
) {
    fun penetrationAt(distanceM: Double): Double = when (kind) {
        PenetratorKind.KE -> max(0.0, mm + lossPerKmMm * (rangeM - distanceM) / 1000.0)
        else -> mm
    }

    companion object {
        /** APFSDS/AP keeps biting up to ~75 deg; a shaped-charge fuze stops working near grazing; blast never glances. */
        fun defaultRicochetDeg(kind: PenetratorKind) = when (kind) {
            PenetratorKind.KE -> 75.0
            PenetratorKind.HEAT -> 80.0
            PenetratorKind.HE -> 90.0
        }

        fun fromJson(obj: JsonObject): PenetratorSpec {
            val kind = PenetratorKind.valueOf(obj["kind"].asString)
            val spec = PenetratorSpec(
                kind = kind,
                mm = obj["mm"].asDouble,
                rangeM = obj["range_m"]?.asDouble ?: 0.0,
                lossPerKmMm = obj["loss_per_km_mm"]?.asDouble ?: 0.0,
                ricochetDeg = obj["ricochet_deg"]?.asDouble ?: defaultRicochetDeg(kind),
                normalizationDeg = obj["normalization_deg"]?.asDouble ?: 0.0,
                fillerKg = obj["filler_kg"]?.asDouble ?: 0.0,
                fuseDelayM = obj["fuse_delay_m"]?.asDouble ?: 0.0,
                fuseSensitivityMm = obj["fuse_sensitivity_mm"]?.asDouble ?: 0.0,
                tandem = obj["tandem"]?.asBoolean ?: false,
            )
            require(spec.mm >= 0 && spec.lossPerKmMm >= 0) { "penetration must be >= 0" }
            require(spec.normalizationDeg in 0.0..30.0) { "normalization_deg must be 0..30" }
            require(spec.fillerKg >= 0 && spec.fuseDelayM >= 0 && spec.fuseSensitivityMm >= 0) { "fuze/filler values must be >= 0" }
            require(spec.ricochetDeg in 0.0..90.0) { "ricochet_deg must be 0..90" }
            return spec
        }
    }
}

enum class Face(val jsonKey: String) {
    FRONT("front"), REAR("rear"), LEFT("side"), RIGHT("side"), TOP("top"), BOTTOM("bottom");
}

/** One armored zone (hull, turret, tracks): a plate per face, and whether a spall liner inside narrows what gets through. */
data class ZoneArmor(val faces: Map<Face, PlateArmor>, val spallLiner: Boolean = false) {
    fun plate(face: Face): PlateArmor = faces.getValue(face)

    companion object {
        /** `{"front": {...}, "side": {...}, "rear": {...}, "top": {...}, "bottom": {...}}` or `{"all": {...}}` as a default. */
        fun fromJson(obj: JsonObject): ZoneArmor {
            val all = obj.getAsJsonObject("all")?.let { PlateArmor.fromJson(it) }
            val faces = Face.entries.associateWith { face ->
                obj.getAsJsonObject(face.jsonKey)?.let { PlateArmor.fromJson(it) }
                    ?: all ?: throw IllegalArgumentException("zone has no '${face.jsonKey}' plate and no 'all' default")
            }
            return ZoneArmor(faces, obj["spall_liner"]?.asBoolean ?: false)
        }
    }
}

/**
 * A vehicle's armor: zones, and which OBB (by index in the vehicle's SuperbWarfare `OBB` list) belongs to which zone. One file can
 * cover several vehicles (`"vehicles": [...]` instead of `"vehicle"`), e.g. every unarmored car sharing one sheet-metal default.
 */
data class VehicleArmor(
    val vehicles: List<String>,
    val zones: Map<String, ZoneArmor>,
    /** Explicit OBB index -> zone; OBBs not listed use [defaultZoneFor]. */
    val obbZones: Map<Int, String>,
    /**
     * Sheet metal, not armor plate: small-arms rounds without an `armor_penetration` value are resolved against it with the
     * penetration the small-arms model derives for them (`ballistics/smallarms/SteelPenetration`), instead of being stopped.
     */
    val soft: Boolean = false,
    /**
     * What is inside: crew, ammunition, fuel, engine... placed relative to the hull/turret/engine boxes, from a template
     * (`"layout": "tank"`, see [ModuleLayouts]) and/or listed (`"modules": [...]`, replacing template modules of the same id).
     * Empty: nothing inside to hit, the plate decides alone.
     */
    val modules: List<ModuleSpec> = emptyList(),
    /**
     * The crew sit in the open (pickup bed, boat, MLRS crew): a warhead bursting on the vehicle reaches them in free air whether
     * or not it gets through the plate ([io.github.jwyoon1220.dncity.ballistics.vehicle.damage.BlastInjury]).
     */
    val openTop: Boolean = false,
) {
    val vehicle: String get() = vehicles.first()

    fun zoneFor(obbIndex: Int, part: String): String? {
        obbZones[obbIndex]?.let { return it }
        val zone = defaultZoneFor(part)
        return when {
            zone in zones -> zone
            // Engine boxes of a vehicle whose file has no engine zone (the tanks) stay part of the hull
            zone == "engine" && "hull" in zones -> "hull"
            else -> null
        }
    }

    companion object {
        /** SuperbWarfare OBB part name -> zone: turret box, track/wheel boxes, engine boxes, the rest hull. */
        fun defaultZoneFor(part: String): String = when (part.uppercase()) {
            "TURRET" -> "turret"
            "WHEEL_LEFT", "WHEEL_RIGHT" -> "tracks"
            "MAIN_ENGINE", "SUB_ENGINE" -> "engine"
            else -> "hull"
        }

        fun fromJson(obj: JsonObject): VehicleArmor {
            val zones = obj.getAsJsonObject("zones").entrySet().associate { (k, v) -> k to ZoneArmor.fromJson(v.asJsonObject) }
            require("hull" in zones) { "a vehicle needs at least a 'hull' zone" }
            val obbZones = obj.getAsJsonObject("obb_zones")?.entrySet()?.associate { (k, v) -> k.toInt() to v.asString }.orEmpty()
            obbZones.values.forEach { require(it in zones) { "obb_zones names unknown zone '$it'" } }
            val vehicles = obj.getAsJsonArray("vehicles")?.map { it.asString } ?: listOfNotNull(obj["vehicle"]?.asString)
            require(vehicles.isNotEmpty()) { "vehicle armor needs 'vehicle' or a non-empty 'vehicles'" }
            require(!(obj.has("vehicle") && obj.has("vehicles"))) { "give either 'vehicle' or 'vehicles', not both" }
            val modules = ModuleLayouts.resolve(obj["layout"]?.asString, obj.getAsJsonArray("modules"))
            return VehicleArmor(vehicles, zones, obbZones, obj["soft"]?.asBoolean ?: false, modules, obj["open_top"]?.asBoolean ?: false)
        }
    }
}

enum class HitOutcome { PENETRATED, NOT_PENETRATED, RICOCHET }

data class HitSolution(
    val outcome: HitOutcome,
    val kind: PenetratorKind,
    val penetrationMm: Double,
    val armorMm: Double,
    val effectiveArmorMm: Double,
    val angleDeg: Double,
    /** Part of [effectiveArmorMm] that came from an intact ERA/NERA layer (0 when there is none or it was used up). */
    val reactiveMm: Double = 0.0,
    /** The reactive layer this hit set off (it is used up / torn there now), null when none fired. */
    val reactiveFired: ReactiveType? = null,
    /** The calibre is at least [ArmorSolver.OVERMATCH_RATIO] times the plate: no ricochet, slope counts less. */
    val overmatch: Boolean = false,
    val ricochetChance: Double = 0.0,
) {
    /** What was left after the plate; 0 unless penetrated. */
    val residualMm: Double get() = if (outcome == HitOutcome.PENETRATED) penetrationMm - effectiveArmorMm else 0.0

    /**
     * How far (blocks) the shell or jet carries on inside after the plate: what decides which modules it reaches.
     * DESIGN constants (not a physical spall model): a long rod keeps going, a jet dies quickly, blast stays near the hole.
     */
    val depthBlocks: Double
        get() = if (outcome != HitOutcome.PENETRATED) 0.0 else when (kind) {
            PenetratorKind.KE -> (1.0 + residualMm / 150.0).coerceIn(1.0, 8.0)
            PenetratorKind.HEAT -> (0.6 + residualMm / 250.0).coerceIn(0.6, 3.0)
            PenetratorKind.HE -> 0.8
        }
}

object ArmorSolver {
    /** Beyond this, 1/cos stops being meaningful for a box face; the ricochet check catches real grazing hits first. */
    private const val MAX_OBLIQUITY_FACTOR = 10.0
    /** Calibre / plate thickness at which the shell overmatches the plate: it cannot glance off, and the slope barely matters. */
    const val OVERMATCH_RATIO = 3.0
    /** Slope exponent left at full overmatch: effective = plate / cos^k. DESIGN. */
    private const val OVERMATCH_SLOPE_EXPONENT = 0.5
    /** Ricochet becomes possible this many degrees before [PenetratorSpec.ricochetDeg] and certain at it. */
    const val RICOCHET_RAMP_DEG = 10.0

    /**
     * @param caliberMm the projectile's calibre, for overmatch and for what sets reactive armor off; 0 = unknown (no overmatch)
     * @param reactiveIntact whether the plate's ERA/NERA is still there at the spot hit
     * @param roll uniform [0, 1) draw for the ricochet chance; the default never glances below [PenetratorSpec.ricochetDeg]
     */
    fun solve(
        spec: PenetratorSpec, plate: PlateArmor, angleDeg: Double, distanceM: Double,
        caliberMm: Double = 0.0, reactiveIntact: Boolean = true, roll: Double = 1.0,
    ): HitSolution {
        val angle = angleDeg.coerceIn(0.0, 90.0)
        val armor = plate.against(spec.kind)
        val ratio = if (spec.kind == PenetratorKind.KE && caliberMm > 0 && armor > 0) caliberMm / armor else 0.0
        val overmatch = ratio >= OVERMATCH_RATIO
        val slope = if (spec.kind == PenetratorKind.KE) max(0.0, angle - spec.normalizationDeg) else angle
        val factor = (1.0 / cos(Math.toRadians(slope))).pow(slopeExponent(ratio)).coerceAtMost(MAX_OBLIQUITY_FACTOR)

        val pen = spec.penetrationAt(distanceM)
        val reactive = plate.reactive?.takeIf { reactiveIntact && it.triggeredBy(spec.kind, caliberMm) }
        val stripped = spec.tandem && spec.kind == PenetratorKind.HEAT && reactive?.type == ReactiveType.ERA
        val reactiveMm = if (stripped) 0.0 else reactive?.let { it.equivalentMm(spec.kind, pen) * it.obliquityShape(angle) } ?: 0.0
        val effective = armor * factor + reactiveMm

        val chance = ricochetChance(spec, angle, overmatch)
        val outcome = when {
            chance >= 1.0 || roll < chance -> HitOutcome.RICOCHET
            pen > effective -> HitOutcome.PENETRATED
            else -> HitOutcome.NOT_PENETRATED
        }
        // a glancing shell does not set off the bricks; anything that bites into the plate does
        val fired = reactive?.type?.takeIf { outcome != HitOutcome.RICOCHET }
        return HitSolution(outcome, spec.kind, pen, armor, effective, angle, reactiveMm, fired, overmatch, chance)
    }

    /** 1 up to a calibre equal to the plate, falling to [OVERMATCH_SLOPE_EXPONENT] at [OVERMATCH_RATIO]. */
    internal fun slopeExponent(ratio: Double): Double =
        if (ratio <= 1.0) 1.0
        else (1.0 - (1.0 - OVERMATCH_SLOPE_EXPONENT) * (ratio - 1.0) / (OVERMATCH_RATIO - 1.0)).coerceAtLeast(OVERMATCH_SLOPE_EXPONENT)

    fun ricochetChance(spec: PenetratorSpec, angleDeg: Double, overmatch: Boolean): Double {
        if (angleDeg >= spec.ricochetDeg && !overmatch) return 1.0
        if (overmatch || spec.kind == PenetratorKind.HE) return 0.0
        val start = spec.ricochetDeg - RICOCHET_RAMP_DEG
        return if (angleDeg <= start) 0.0 else (angleDeg - start) / RICOCHET_RAMP_DEG
    }
}

/** Ray against an oriented box; pure math so the face/angle logic is testable. */
object RayBox {
    /**
     * @param axes the box's three unit axes (local X, Y, Z) in world space
     * @return distance along [dir] (unit) to the entry point and the entered face, or null when the ray misses or starts inside
     */
    fun enter(origin: DoubleArray, dir: DoubleArray, center: DoubleArray, axes: Array<DoubleArray>, extents: DoubleArray): Pair<Double, Face>? {
        var tEnter = Double.NEGATIVE_INFINITY
        var tExit = Double.POSITIVE_INFINITY
        var enterAxis = -1
        var enterSign = 0.0
        val rel = DoubleArray(3) { origin[it] - center[it] }
        for (i in 0..2) {
            val o = dot(rel, axes[i])
            val d = dot(dir, axes[i])
            if (kotlin.math.abs(d) < 1e-12) {
                if (o < -extents[i] || o > extents[i]) return null
                continue
            }
            var t1 = (-extents[i] - o) / d
            var t2 = (extents[i] - o) / d
            // the face we enter through is on the side the ray comes from
            val sign = if (d > 0) -1.0 else 1.0
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tEnter) { tEnter = t1; enterAxis = i; enterSign = sign }
            if (t2 < tExit) tExit = t2
            if (tEnter > tExit) return null
        }
        if (enterAxis < 0 || tEnter < 0) return null
        return tEnter to faceOf(enterAxis, enterSign)
    }

    /** Box-local axis/sign -> face; SuperbWarfare vehicles face +Z, +Y is up, +X is the vehicle's left. */
    fun faceOf(axis: Int, sign: Double): Face = when (axis) {
        0 -> if (sign > 0) Face.LEFT else Face.RIGHT
        1 -> if (sign > 0) Face.TOP else Face.BOTTOM
        else -> if (sign > 0) Face.FRONT else Face.REAR
    }

    /** World-space outward normal of [face] for a box with these [axes]. */
    fun normal(face: Face, axes: Array<DoubleArray>): DoubleArray = when (face) {
        Face.LEFT -> axes[0].copyOf()
        Face.RIGHT -> DoubleArray(3) { -axes[0][it] }
        Face.TOP -> axes[1].copyOf()
        Face.BOTTOM -> DoubleArray(3) { -axes[1][it] }
        Face.FRONT -> axes[2].copyOf()
        Face.REAR -> DoubleArray(3) { -axes[2][it] }
    }

    /** Impact angle from the plate normal, degrees (0 = square hit). */
    fun impactAngleDeg(dir: DoubleArray, normal: DoubleArray): Double {
        val c = (-dot(dir, normal)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(c))
    }

    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
