// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.ballistics.vehicle.damage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a vehicle carries inside, War Thunder style: every module is a box that the shell, its spall and blast can reach.
 * Hit points and the internal thickness each module puts in a fragment's way are DESIGN values (chosen for play).
 */
enum class ModuleType(val defaultHp: Double, val defaultArmorMm: Double) {
    /** A crew member. Absorbs fragments well (a body stops a lot of spall). */
    CREW(100.0, 12.0),
    /** Ammunition stowage: destroyed, it detonates or cooks off. */
    AMMO(60.0, 4.0),
    /** Fuel tank: destroyed, it may burn. Full of liquid, so it eats fragments. */
    FUEL(150.0, 20.0),
    ENGINE(250.0, 30.0),
    /** Destroyed: the vehicle cannot drive. */
    TRANSMISSION(200.0, 25.0),
    /** Gun breech: destroyed, the gun cannot fire. */
    BREECH(220.0, 45.0),
    /** Turret traverse drive: destroyed, the turret cannot turn. */
    TURRET_DRIVE(80.0, 10.0);

    val jsonKey: String get() = name.lowercase()

    companion object {
        fun parse(text: String): ModuleType = valueOf(text.uppercase())
    }
}

/** The vehicle boxes modules are placed in: the main hull box, the turret box, the engine box. */
enum class Anchor {
    HULL, TURRET, ENGINE;

    val jsonKey: String get() = name.lowercase()

    companion object {
        fun parse(text: String): Anchor = valueOf(text.uppercase())
    }
}

/**
 * Where a module sits in its [anchor] box, in fractions of that box: [at] is the centre (-1..1 per box axis, box-local
 * +X = vehicle left, +Y up, +Z front), [size] the half-extents as a share of the box's (0..1).
 */
data class Placement(val anchor: Anchor, val at: V3, val size: V3) {
    init {
        require(listOf(at.x, at.y, at.z).all { it in -1.0..1.0 }) { "module 'at' must be within -1..1 of its box" }
        require(listOf(size.x, size.y, size.z).all { it > 0.0 && it <= 1.0 }) { "module 'size' must be in (0, 1]" }
    }

    /** The same spot when the vehicle has no turret box: the top part of the hull. */
    fun inHullInstead(): Placement = when (anchor) {
        Anchor.HULL -> this
        Anchor.TURRET -> Placement(Anchor.HULL, V3(at.x * 0.6, (0.55 + at.y * 0.4).coerceIn(-1.0, 1.0), at.z * 0.5), size.scale(0.5).clampUnit())
        Anchor.ENGINE -> Placement(Anchor.HULL, V3(0.0, -0.1, -0.65), V3(0.6, 0.45, 0.25))
    }

    private fun V3.clampUnit() = V3(max(x, 0.02), max(y, 0.02), max(z, 0.02))
}

data class ModuleSpec(
    val id: String,
    val type: ModuleType,
    val placement: Placement,
    val hp: Double = type.defaultHp,
    val armorMm: Double = type.defaultArmorMm,
    /** CREW only: the SuperbWarfare seat whose passenger this crew member is (they get hurt with it), null = no seat. */
    val seat: Int? = null,
    /** Where the module goes when the vehicle lacks [Placement.anchor]'s box; null = [Placement.inHullInstead]. */
    val fallback: Placement? = null,
) {
    init {
        require(id.isNotBlank()) { "module needs an id" }
        require(hp > 0 && armorMm >= 0) { "module '$id': hp must be > 0, armor_mm >= 0" }
        require(seat == null || (type == ModuleType.CREW && seat >= 0)) { "module '$id': only crew sit in seats" }
    }

    companion object {
        fun fromJson(obj: JsonObject): ModuleSpec {
            val type = ModuleType.parse(obj["type"].asString)
            fun vec(key: String, default: V3?): V3 {
                val a = obj.getAsJsonArray(key) ?: return default ?: throw IllegalArgumentException("module needs '$key'")
                require(a.size() == 3) { "'$key' needs 3 numbers" }
                return V3(a[0].asDouble, a[1].asDouble, a[2].asDouble)
            }
            return ModuleSpec(
                id = obj["id"].asString,
                type = type,
                placement = Placement(obj["anchor"]?.asString?.let(Anchor::parse) ?: Anchor.HULL, vec("at", null), vec("size", null)),
                hp = obj["hp"]?.asDouble ?: type.defaultHp,
                armorMm = obj["armor_mm"]?.asDouble ?: type.defaultArmorMm,
                seat = obj["seat"]?.asInt,
            )
        }
    }
}

/** A box with its own axes, metres (blocks), world space. Pure geometry for rays against vehicle boxes and modules. */
data class OrientedBox(val center: V3, val axisX: V3, val axisY: V3, val axisZ: V3, val half: V3) {
    val axes: List<V3> get() = listOf(axisX, axisY, axisZ)
    private val halves: DoubleArray get() = doubleArrayOf(half.x, half.y, half.z)

    /** A point given in fractions of the box (-1..1 per axis). */
    fun pointAt(f: V3): V3 = center + axisX.scale(f.x * half.x) + axisY.scale(f.y * half.y) + axisZ.scale(f.z * half.z)

    /** The module box a [Placement] in this box describes. */
    fun sub(p: Placement): OrientedBox = OrientedBox(pointAt(p.at), axisX, axisY, axisZ, V3(half.x * p.size.x, half.y * p.size.y, half.z * p.size.z))

    /** Box-local coordinates (metres along each axis from the centre). */
    fun toLocal(p: V3): V3 { val r = p - center; return V3(r.dot(axisX), r.dot(axisY), r.dot(axisZ)) }

    fun fromLocal(l: V3): V3 = center + axisX.scale(l.x) + axisY.scale(l.y) + axisZ.scale(l.z)

    fun contains(p: V3, margin: Double = 0.0): Boolean {
        val l = toLocal(p)
        return abs(l.x) <= half.x + margin && abs(l.y) <= half.y + margin && abs(l.z) <= half.z + margin
    }

    /** Distance from [p] to the box (0 inside). */
    fun distanceTo(p: V3): Double {
        val l = toLocal(p)
        val dx = max(abs(l.x) - half.x, 0.0); val dy = max(abs(l.y) - half.y, 0.0); val dz = max(abs(l.z) - half.z, 0.0)
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Where the line `origin + t * dir` (unit dir) is inside the box: (tIn, tOut) with tOut >= 0, tIn possibly negative
     * when the origin is inside; null when the line misses or the box is behind.
     */
    fun clip(origin: V3, dir: V3): Pair<Double, Double>? {
        var tIn = Double.NEGATIVE_INFINITY
        var tOut = Double.POSITIVE_INFINITY
        val rel = origin - center
        val h = halves
        axes.forEachIndexed { i, axis ->
            val o = rel.dot(axis)
            val d = dir.dot(axis)
            if (abs(d) < 1e-12) {
                if (abs(o) > h[i]) return null
            } else {
                val t1 = (-h[i] - o) / d
                val t2 = (h[i] - o) / d
                tIn = max(tIn, min(t1, t2))
                tOut = min(tOut, max(t1, t2))
                if (tIn > tOut) return null
            }
        }
        return if (tOut < 0) null else tIn to tOut
    }

    companion object {
        /**
         * The smallest box with the axes of the biggest of [boxes] that holds them all: one hull box for a vehicle whose hull
         * SuperbWarfare splits into several (the M1A2 has a rear, a middle and a nose box), so a template's "front of the hull"
         * is the real front.
         */
        fun enclosing(boxes: List<OrientedBox>): OrientedBox {
            require(boxes.isNotEmpty()) { "nothing to enclose" }
            val frame = boxes.maxBy { it.half.x * it.half.y * it.half.z }
            val local = boxes.flatMap { it.corners() }.map(frame::toLocal)
            val lo = V3(local.minOf { it.x }, local.minOf { it.y }, local.minOf { it.z })
            val hi = V3(local.maxOf { it.x }, local.maxOf { it.y }, local.maxOf { it.z })
            return OrientedBox(frame.fromLocal((lo + hi).scale(0.5)), frame.axisX, frame.axisY, frame.axisZ, (hi - lo).scale(0.5))
        }
    }

    /** The 8 corners, in SuperbWarfare's `OBB.getVertices()` order (bottom face then top face). */
    fun corners(): List<V3> = listOf(
        V3(-1.0, -1.0, -1.0), V3(1.0, -1.0, -1.0), V3(1.0, 1.0, -1.0), V3(-1.0, 1.0, -1.0),
        V3(-1.0, -1.0, 1.0), V3(1.0, -1.0, 1.0), V3(1.0, 1.0, 1.0), V3(-1.0, 1.0, 1.0),
    ).map(::pointAt)
}

/** A module of one vehicle, placed in the world for the hit being resolved. */
data class PlacedModule(val spec: ModuleSpec, val box: OrientedBox)

/** Places a vehicle's modules in its current boxes. A module whose anchor box the vehicle lacks moves into the hull. */
fun placeModules(specs: List<ModuleSpec>, anchors: Map<Anchor, OrientedBox>): List<PlacedModule> {
    val hull = anchors[Anchor.HULL] ?: return emptyList()
    return specs.map { spec ->
        val p = spec.placement
        val box = anchors[p.anchor]?.sub(p) ?: hull.sub(spec.fallback ?: p.inHullInstead())
        PlacedModule(spec, box)
    }
}

/**
 * Module templates per kind of vehicle, in fractions of the hull/turret box so they fit any size. Generic, not any one
 * vehicle's real layout (DESIGN): a tank has its driver forward, three crew and the ready rack in the turret, hull ammo and
 * fuel beside the driver, the engine and transmission at the back. An explicit `"modules"` list replaces template entries by id.
 */
object ModuleLayouts {
    private fun m(id: String, type: ModuleType, anchor: Anchor, at: V3, size: V3, seat: Int? = null, fallback: Placement? = null) =
        ModuleSpec(id, type, Placement(anchor, at, size), seat = seat, fallback = fallback)

    private fun engine(fallbackAt: V3, fallbackSize: V3) =
        m("engine", ModuleType.ENGINE, Anchor.ENGINE, V3.ZERO, V3(1.0, 1.0, 1.0), fallback = Placement(Anchor.HULL, fallbackAt, fallbackSize))

    private fun v(x: Double, y: Double, z: Double) = V3(x, y, z)

    val TANK: List<ModuleSpec> = listOf(
        m("driver", ModuleType.CREW, Anchor.HULL, v(0.0, -0.15, 0.68), v(0.2, 0.35, 0.16), seat = 0),
        m("hull_ammo", ModuleType.AMMO, Anchor.HULL, v(-0.6, -0.25, 0.62), v(0.25, 0.3, 0.2)),
        m("fuel_front", ModuleType.FUEL, Anchor.HULL, v(0.6, -0.25, 0.62), v(0.25, 0.3, 0.2)),
        m("fuel_left", ModuleType.FUEL, Anchor.HULL, v(0.8, -0.1, -0.3), v(0.15, 0.35, 0.25)),
        m("fuel_right", ModuleType.FUEL, Anchor.HULL, v(-0.8, -0.1, -0.3), v(0.15, 0.35, 0.25)),
        engine(v(0.0, -0.1, -0.65), v(0.6, 0.45, 0.25)),
        m("transmission", ModuleType.TRANSMISSION, Anchor.HULL, v(0.0, -0.35, -0.9), v(0.5, 0.25, 0.08)),
        m("gunner", ModuleType.CREW, Anchor.TURRET, v(-0.42, -0.35, 0.25), v(0.22, 0.4, 0.2), seat = 1),
        m("commander", ModuleType.CREW, Anchor.TURRET, v(-0.42, 0.1, -0.2), v(0.22, 0.5, 0.2), seat = 2),
        m("loader", ModuleType.CREW, Anchor.TURRET, v(0.45, -0.15, 0.0), v(0.22, 0.5, 0.2), seat = 3),
        m("breech", ModuleType.BREECH, Anchor.TURRET, v(0.0, -0.05, 0.4), v(0.16, 0.2, 0.3)),
        m("turret_ammo", ModuleType.AMMO, Anchor.TURRET, v(0.0, -0.05, -0.8), v(0.7, 0.35, 0.15)),
        m("turret_drive", ModuleType.TURRET_DRIVE, Anchor.TURRET, v(0.2, -0.75, 0.45), v(0.15, 0.15, 0.15)),
    )

    /** Infantry fighting vehicle / APC: engine forward beside the driver, two in the turret, the troop bay at the back. */
    val IFV: List<ModuleSpec> = listOf(
        m("driver", ModuleType.CREW, Anchor.HULL, v(0.5, -0.1, 0.62), v(0.2, 0.35, 0.16), seat = 0),
        engine(v(-0.45, -0.15, 0.6), v(0.4, 0.4, 0.25)),
        m("transmission", ModuleType.TRANSMISSION, Anchor.HULL, v(-0.45, -0.45, 0.88), v(0.35, 0.25, 0.08)),
        m("hull_ammo", ModuleType.AMMO, Anchor.HULL, v(0.0, -0.45, 0.15), v(0.3, 0.15, 0.2)),
        m("fuel_rear", ModuleType.FUEL, Anchor.HULL, v(0.0, -0.3, -0.85), v(0.6, 0.3, 0.1)),
        m("troop_1", ModuleType.CREW, Anchor.HULL, v(0.45, -0.1, -0.45), v(0.2, 0.35, 0.15), seat = 3),
        m("troop_2", ModuleType.CREW, Anchor.HULL, v(-0.45, -0.1, -0.45), v(0.2, 0.35, 0.15), seat = 4),
        m("troop_3", ModuleType.CREW, Anchor.HULL, v(0.45, -0.1, -0.15), v(0.2, 0.35, 0.15), seat = 5),
        m("gunner", ModuleType.CREW, Anchor.TURRET, v(0.35, -0.3, 0.0), v(0.25, 0.45, 0.25), seat = 1),
        m("commander", ModuleType.CREW, Anchor.TURRET, v(-0.35, -0.3, 0.0), v(0.25, 0.45, 0.25), seat = 2),
        m("breech", ModuleType.BREECH, Anchor.TURRET, v(0.0, 0.0, 0.5), v(0.12, 0.15, 0.3)),
        m("turret_ammo", ModuleType.AMMO, Anchor.TURRET, v(0.0, -0.6, -0.2), v(0.3, 0.3, 0.3)),
        m("turret_drive", ModuleType.TURRET_DRIVE, Anchor.TURRET, v(0.0, -0.8, 0.4), v(0.15, 0.15, 0.15)),
    )

    /** Car / truck: engine in front, fuel tank at the back under the floor. */
    val CAR: List<ModuleSpec> = listOf(
        m("driver", ModuleType.CREW, Anchor.HULL, v(0.35, -0.1, 0.2), v(0.2, 0.35, 0.15), seat = 0),
        m("passenger", ModuleType.CREW, Anchor.HULL, v(-0.35, -0.1, 0.2), v(0.2, 0.35, 0.15), seat = 1),
        m("rear_left", ModuleType.CREW, Anchor.HULL, v(0.35, -0.1, -0.3), v(0.2, 0.35, 0.15), seat = 2),
        m("rear_right", ModuleType.CREW, Anchor.HULL, v(-0.35, -0.1, -0.3), v(0.2, 0.35, 0.15), seat = 3),
        engine(v(0.0, -0.1, 0.75), v(0.6, 0.4, 0.2)),
        m("fuel", ModuleType.FUEL, Anchor.HULL, v(0.0, -0.7, -0.8), v(0.5, 0.15, 0.12)),
    )

    /** Aircraft: pilot forward, fuel in the middle, engine behind it. */
    val AIRCRAFT: List<ModuleSpec> = listOf(
        m("pilot", ModuleType.CREW, Anchor.HULL, v(0.0, 0.1, 0.45), v(0.2, 0.3, 0.1), seat = 0),
        m("fuel", ModuleType.FUEL, Anchor.HULL, v(0.0, 0.0, 0.0), v(0.4, 0.3, 0.2)),
        engine(v(0.0, 0.0, -0.4), v(0.3, 0.3, 0.25)),
        m("ammo", ModuleType.AMMO, Anchor.HULL, v(0.0, -0.3, 0.25), v(0.25, 0.2, 0.1)),
    )

    val TEMPLATES: Map<String, List<ModuleSpec>> = mapOf("tank" to TANK, "ifv" to IFV, "car" to CAR, "aircraft" to AIRCRAFT)

    /** Template (may be null) plus explicit modules; an explicit module replaces the template's module with its id. */
    fun resolve(layout: String?, explicit: JsonArray?): List<ModuleSpec> {
        val base = layout?.let { TEMPLATES[it] ?: throw IllegalArgumentException("unknown layout '$it' (${TEMPLATES.keys})") }.orEmpty()
        val extra = explicit?.map { ModuleSpec.fromJson(it.asJsonObject) }.orEmpty()
        val ids = extra.map { it.id }
        require(ids.toSet().size == ids.size) { "module ids must be unique" }
        val replaced = extra.associateBy { it.id }
        return base.map { replaced[it.id] ?: it } + extra.filter { e -> base.none { it.id == e.id } }
    }
}
