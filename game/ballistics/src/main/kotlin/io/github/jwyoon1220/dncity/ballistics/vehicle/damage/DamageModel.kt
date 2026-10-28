// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.ballistics.vehicle.damage

import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallFragments
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallSource
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorSpec
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Explosive filler of a round, kg TNT, estimated from calibre where the data gives none. */
object Warhead {
    /**
     * kg TNT per mm^3 of calibre, fitted to two standard HE shells: M107 155 mm, 6.86 kg (1.84e-6; Wikipedia "M107 projectile")
     * and M1 105 mm, 2.18 kg TNT normal cavity (1.88e-6; bulletpicker.com, US Army data). Gives 120 mm ~ 3.2 kg, 30 mm ~ 50 g.
     */
    private const val HE_FILL_PER_MM3 = 1.86e-6
    /** A kinetic round that carries some explosive (APHE, HE-I sharing an AP entity) holds a quarter of that. */
    private const val APHE_SHARE = 0.25

    fun fillerKg(spec: PenetratorSpec, caliberMm: Double, explosive: Boolean): Double = when {
        spec.fillerKg > 0 -> spec.fillerKg
        spec.kind == PenetratorKind.HE -> HE_FILL_PER_MM3 * caliberMm.pow(3)
        spec.kind == PenetratorKind.KE && explosive && caliberMm >= 20 -> APHE_SHARE * HE_FILL_PER_MM3 * caliberMm.pow(3)
        else -> 0.0
    }
}

/** One penetration, as the damage model sees it. World space, metres (blocks). */
data class Penetration(
    val entry: V3,
    /** Unit direction of flight. */
    val dir: V3,
    val kind: PenetratorKind,
    /** Penetration left after the plate, mm RHA. */
    val residualMm: Double,
    val caliberMm: Double,
    /** kg TNT that goes off inside ([Warhead]); 0 = none. */
    val fillerKg: Double = 0.0,
    /** APHE: metres after the plate before it goes off. */
    val fuseDelayM: Double = 0.0,
    /** APHE: whether the plate was thick enough to arm the fuze. */
    val fuseArmed: Boolean = true,
    val spallLiner: Boolean = false,
    val overmatch: Boolean = false,
)

enum class RayKind { SHELL, JET, SPALL, BLAST_FRAGMENT }

/** One line drawn in the hit camera: the shell or jet, a spall fragment, a blast fragment. */
data class DamageRay(val from: V3, val to: V3, val kind: RayKind, val hitModule: Boolean)

data class ModuleHit(val id: String, val type: ModuleType, val damage: Double, val destroyedNow: Boolean)

enum class EventType {
    CREW_KNOCKED_OUT, AMMO_DETONATION, FIRE, COOK_OFF, FIRE_OUT,
    ENGINE_DESTROYED, TRANSMISSION_DESTROYED, BREECH_DESTROYED, TURRET_DRIVE_DESTROYED, FUEL_DESTROYED, AMMO_DESTROYED,
    VEHICLE_DESTROYED,
}

data class DamageEvent(val type: EventType, val moduleId: String? = null)

/** Why the vehicle is out. */
enum class DestroyCause { AMMO_DETONATION, CREW_KNOCKED_OUT }

data class Blast(val center: V3, val radiusM: Double, val fillerKg: Double)

data class DamageReport(
    val rays: List<DamageRay>,
    val hits: List<ModuleHit>,
    val events: List<DamageEvent>,
    val blast: Blast?,
    /** Fires started by this hit: module id -> seconds it will burn. */
    val newFires: Map<String, Double>,
    val destroyed: DestroyCause?,
) {
    companion object {
        val NONE = DamageReport(emptyList(), emptyList(), emptyList(), null, emptyMap(), null)
    }
}

/** The inside of the vehicle (hull and turret boxes): fragments fly until they leave it, i.e. hit the far wall. */
class Interior(val boxes: List<OrientedBox>) {
    /** How far along [dir] from [origin] the line stays inside one of the boxes, chaining boxes that touch. */
    fun exitDistance(origin: V3, dir: V3, maxM: Double): Double {
        val spans = boxes.mapNotNull { it.clip(origin, dir) }.sortedBy { it.first }
        var reach = 0.0
        var grew = true
        while (grew) {
            grew = false
            for ((tIn, tOut) in spans) if (tIn <= reach + TOUCH_M && tOut > reach) { reach = tOut; grew = true }
        }
        return min(if (reach > 0) reach else NO_INTERIOR_M, maxM)
    }

    private companion object {
        const val TOUCH_M = 0.08
        /** Origin not inside any box (a hit just on the surface of a thin one): let it reach this far in. */
        const val NO_INTERIOR_M = 0.6
    }
}

/**
 * What happens behind the plate after a penetration, War Thunder style:
 *
 * 1. **The shell (or jet)** flies on along its line and goes through the modules in its way; every module costs it the module's
 *    internal thickness ([ModuleSpec.armorMm]). A shaped-charge jet also loses penetration with every metre of air.
 * 2. **Spall**: a cone of fragments around the line of flight (wider for a full-calibre shell, narrow for a jet, narrowed and
 *    thinned by a spall liner), each one its own 3D ray ([SpallFragments]: Mott masses, uniform over the cone), damaging and
 *    being slowed by the modules it crosses until it reaches the far wall.
 * 3. **Filler**: APHE goes off [Penetration.fuseDelayM] after the plate (if the plate armed the fuze), HE just inside. The blast
 *    hurts every module within its radius, and its casing fragments fly out in every direction.
 * 4. **Consequences**: a destroyed ammo rack detonates (vehicle gone) or cooks off into a fire, fuel and engines may burn, crew
 *    are knocked out, and the vehicle is out when fewer than two (or none of a smaller crew) are left.
 *
 * Every constant here is a DESIGN value chosen for play, not calibrated. Pure and deterministic for a given seed.
 */
object DamageModel {
    // shell / jet
    private const val SHELL_DAMAGE_BASE = 60.0
    private const val SHELL_DAMAGE_PER_CALIBER_MM = 0.8
    private const val SHELL_DAMAGE_PER_MM = 0.2
    private const val SHELL_MAX_RANGE_M = 12.0
    private const val JET_DAMAGE_BASE = 50.0
    private const val JET_DAMAGE_PER_MM = 0.15
    private const val JET_AIR_LOSS_MM_PER_M = 150.0
    private const val JET_MAX_RANGE_M = 3.0

    // spall
    /** Rounds below this make no spall worth tracing (rifle and machine-gun bullets). */
    private const val SPALL_MIN_CALIBER_MM = 14.0
    private const val SPALL_RANGE_M = 6.0
    /** Unlined spall cone "in excess of 90 deg" (full angle; patent EP0588212B1, Advanced spall liner system). */
    private const val SPALL_KE_HALF_ANGLE_DEG = 45.0
    /** A full-calibre shell overmatching the plate punches out a still wider cone. DESIGN (no source). */
    private const val SPALL_OVERMATCH_HALF_ANGLE_DEG = 55.0
    /** DESIGN (no source): a jet's spall stays close to its line. */
    private const val SPALL_JET_HALF_ANGLE_DEG = 12.0
    /** Lined: "approximately 30 deg" against more than 90 unlined (EP0588212B1): a third of the angle. */
    private const val SPALL_LINER_ANGLE = 30.0 / 90.0
    /** Liner makers claim "up to 95 %" less spall entering the vehicle (Permali, spall liner page): their best case is used. */
    private const val SPALL_LINER_COUNT = 0.05
    /** Damage a fragment does per mm of penetration it still has. */
    private const val FRAGMENT_DAMAGE_PER_MM = 2.5

    // blast
    /**
     * Scaled distance (m/kg^(1/3) TNT) at which peak overpressure falls to 100 kPa (~1 atm), from the Kinney-Graham (1985) free-air
     * relation `dP/P0 = 808 (1 + (Z/4.5)^2) / sqrt((1 + (Z/0.048)^2)(1 + (Z/0.32)^2)(1 + (Z/1.35)^2))`, P0 = 101.325 kPa. The 100 kPa
     * threshold for "a module is hurt" is DESIGN; the inside of a vehicle (confined, reflected) would be worse than free air.
     */
    private const val BLAST_RADIUS_PER_CBRT_KG = 2.75
    private const val BLAST_DAMAGE_PER_CBRT_KG = 600.0
    private const val HE_BURST_DEPTH_M = 0.15

    // consequences
    private const val AMMO_DETONATION_CHANCE = 0.7
    private const val AMMO_DETONATION_CHANCE_BLAST = 0.9
    private const val AMMO_FIRE_PER_DAMAGE_SHARE = 0.25
    private const val FUEL_FIRE_CHANCE = 0.45
    private const val FUEL_FIRE_CHANCE_HOT = 0.7
    private const val FUEL_FIRE_PER_DAMAGE_SHARE = 0.1
    private const val ENGINE_FIRE_CHANCE = 0.3
    private val FIRE_SECONDS = 10.0..22.0

    private val SPALL_MATERIAL = Ident("dncity", "spall")

    fun simulate(pen: Penetration, modules: List<PlacedModule>, interior: Interior, state: VehicleDamageState, seed: Long): DamageReport {
        val rng = Random(seed)
        val damage = LinkedHashMap<String, Double>()
        val rays = ArrayList<DamageRay>()
        fun hurt(m: PlacedModule, amount: Double) { if (amount > 0) damage.merge(m.spec.id, amount, Double::plus) }

        // 1. the shell or jet itself
        val isJet = pen.kind == PenetratorKind.HEAT
        val shellRange = interior.exitDistance(pen.entry, pen.dir, if (isJet) JET_MAX_RANGE_M else SHELL_MAX_RANGE_M)
        var shellStop = shellRange
        var shellHit = false
        if (pen.kind != PenetratorKind.HE) {
            // what the modules passed so far have eaten; a jet also loses to the air it has crossed
            var eaten = 0.0
            fun powerAt(t: Double) = pen.residualMm - eaten - if (isJet) JET_AIR_LOSS_MM_PER_M * t else 0.0
            for ((m, t) in along(modules, pen.entry, pen.dir, shellRange)) {
                val power = powerAt(t)
                if (power <= 0) { shellStop = t; break }
                hurt(m, if (isJet) JET_DAMAGE_BASE + JET_DAMAGE_PER_MM * power
                else SHELL_DAMAGE_BASE + SHELL_DAMAGE_PER_CALIBER_MM * pen.caliberMm + SHELL_DAMAGE_PER_MM * power)
                shellHit = true
                eaten += m.spec.armorMm
                if (powerAt(t) <= 0) { shellStop = t; break }
            }
            if (isJet) shellStop = min(shellStop, pen.residualMm / JET_AIR_LOSS_MM_PER_M)
            rays += DamageRay(pen.entry, pen.entry + pen.dir.scale(shellStop), if (isJet) RayKind.JET else RayKind.SHELL, shellHit)
        }

        // 2. spall off the back of the plate
        val (count, halfAngleDeg, powerMm) = spallOf(pen)
        if (count > 0) {
            val source = SpallSource(pen.entry, pen.dir, Math.toRadians(halfAngleDeg), 1.0, 1.0, SPALL_MATERIAL)
            for (f in SpallFragments.expand(source, seed xor SPALL_SALT, count)) {
                rays += fragment(f.velocity.normalize(), pen.entry, powerMm * f.massKg * count, RayKind.SPALL, modules, interior, ::hurt)
            }
        }

        // 3. the filler
        var blast: Blast? = null
        val burstAt = when {
            pen.fillerKg <= 0 -> null
            pen.kind == PenetratorKind.HE -> HE_BURST_DEPTH_M
            pen.kind == PenetratorKind.KE && pen.fuseArmed -> min(max(pen.fuseDelayM, HE_BURST_DEPTH_M), shellStop)
            else -> null
        }
        if (burstAt != null) {
            val center = pen.entry + pen.dir.scale(burstAt)
            val k = cbrt(pen.fillerKg)
            val radius = BLAST_RADIUS_PER_CBRT_KG * k
            blast = Blast(center, radius, pen.fillerKg)
            for (m in modules) {
                val d = m.box.distanceTo(center)
                if (d < radius) hurt(m, BLAST_DAMAGE_PER_CBRT_KG * k * (1 - d / radius).pow(1.5))
            }
            val n = (8 + 60 * pen.fillerKg).toInt().coerceIn(8, 60)
            val source = SpallSource(center, pen.dir, PI, 1.0, 1.0, SPALL_MATERIAL)
            for (f in SpallFragments.expand(source, seed xor BLAST_SALT, n)) {
                rays += fragment(f.velocity.normalize(), center, (6 + 20 * k) * f.massKg * n, RayKind.BLAST_FRAGMENT, modules, interior, ::hurt)
            }
        }

        // 4. what the damage does
        val hits = ArrayList<ModuleHit>()
        val events = ArrayList<DamageEvent>()
        val fires = LinkedHashMap<String, Double>()
        var detonated = false
        val hot = blast != null || isJet
        for ((id, amount) in damage) {
            val m = modules.first { it.spec.id == id }.spec
            val before = state.damageOf(id)
            val destroyedNow = before < m.hp && before + amount >= m.hp
            hits += ModuleHit(id, m.type, amount, destroyedNow)
            val share = min(1.0, amount / m.hp)
            fun fire(chance: Double) { if (id !in state.burning && rng.nextDouble() < chance) fires[id] = rng.nextDouble(FIRE_SECONDS.start, FIRE_SECONDS.endInclusive) }
            when (m.type) {
                ModuleType.AMMO -> if (destroyedNow) {
                    events += DamageEvent(EventType.AMMO_DESTROYED, id)
                    if (rng.nextDouble() < if (blast != null) AMMO_DETONATION_CHANCE_BLAST else AMMO_DETONATION_CHANCE) {
                        detonated = true
                        events += DamageEvent(EventType.AMMO_DETONATION, id)
                    } else fire(1.0)
                } else fire(AMMO_FIRE_PER_DAMAGE_SHARE * share)
                ModuleType.FUEL -> if (destroyedNow) {
                    events += DamageEvent(EventType.FUEL_DESTROYED, id)
                    fire(if (hot) FUEL_FIRE_CHANCE_HOT else FUEL_FIRE_CHANCE)
                } else fire(FUEL_FIRE_PER_DAMAGE_SHARE * share)
                ModuleType.ENGINE -> if (destroyedNow) {
                    events += DamageEvent(EventType.ENGINE_DESTROYED, id)
                    fire(ENGINE_FIRE_CHANCE)
                }
                ModuleType.CREW -> if (destroyedNow) events += DamageEvent(EventType.CREW_KNOCKED_OUT, id)
                ModuleType.TRANSMISSION -> if (destroyedNow) events += DamageEvent(EventType.TRANSMISSION_DESTROYED, id)
                ModuleType.BREECH -> if (destroyedNow) events += DamageEvent(EventType.BREECH_DESTROYED, id)
                ModuleType.TURRET_DRIVE -> if (destroyedNow) events += DamageEvent(EventType.TURRET_DRIVE_DESTROYED, id)
            }
        }
        fires.keys.forEach { events += DamageEvent(EventType.FIRE, it) }

        val cause = when {
            state.destroyed != null -> null
            detonated -> DestroyCause.AMMO_DETONATION
            crewOut(modules.map { it.spec }) { id -> state.damageOf(id) + (damage[id] ?: 0.0) } -> DestroyCause.CREW_KNOCKED_OUT
            else -> null
        }
        if (cause != null) events += DamageEvent(EventType.VEHICLE_DESTROYED)
        return DamageReport(rays, hits, events, blast, fires, cause)
    }

    /** Fewer than two crew left (or none, for a one-man vehicle): it cannot fight. No crew modules: never. */
    fun crewOut(specs: List<ModuleSpec>, damageOf: (String) -> Double): Boolean {
        val crew = specs.filter { it.type == ModuleType.CREW }
        if (crew.isEmpty()) return false
        val alive = crew.count { damageOf(it.id) < it.hp }
        return alive < min(2, crew.size)
    }

    /** Fragment count, cone half angle (deg) and mean penetration (mm) of the spall a penetration throws. */
    internal fun spallOf(pen: Penetration): Triple<Int, Double, Double> {
        if (pen.caliberMm < SPALL_MIN_CALIBER_MM && pen.kind != PenetratorKind.HEAT) return Triple(0, 0.0, 0.0)
        val (n, angle, power) = when (pen.kind) {
            PenetratorKind.KE -> Triple(
                8 + pen.residualMm / 15 + pen.caliberMm / 10,
                if (pen.overmatch) SPALL_OVERMATCH_HALF_ANGLE_DEG else SPALL_KE_HALF_ANGLE_DEG,
                10 + 0.08 * pen.residualMm + 0.05 * pen.caliberMm,
            )
            PenetratorKind.HEAT -> Triple(5 + pen.residualMm / 40, SPALL_JET_HALF_ANGLE_DEG, 8 + 0.05 * pen.residualMm)
            PenetratorKind.HE -> Triple(4 + pen.caliberMm / 20, 45.0, 6 + 0.05 * pen.caliberMm)
        }
        val liner = pen.spallLiner
        val count = (n * if (liner) SPALL_LINER_COUNT else 1.0).toInt().coerceIn(if (liner) 2 else 4, 60)
        return Triple(count, angle * if (liner) SPALL_LINER_ANGLE else 1.0, power.coerceIn(4.0, 60.0))
    }

    /** Modules the line reaches before [maxT], with the distance at which it enters each, nearest first. */
    private fun along(modules: List<PlacedModule>, origin: V3, dir: V3, maxT: Double): List<Pair<PlacedModule, Double>> =
        modules.mapNotNull { m -> m.box.clip(origin, dir)?.takeIf { it.first < maxT }?.let { m to max(it.first, 0.0) } }.sortedBy { it.second }

    private inline fun fragment(
        dir: V3, origin: V3, startMm: Double, kind: RayKind, modules: List<PlacedModule>, interior: Interior,
        hurt: (PlacedModule, Double) -> Unit,
    ): DamageRay {
        val range = interior.exitDistance(origin, dir, SPALL_RANGE_M)
        var power = startMm
        var stop = range
        var hit = false
        for ((m, t) in along(modules, origin, dir, range)) {
            hurt(m, FRAGMENT_DAMAGE_PER_MM * power)
            hit = true
            power -= m.spec.armorMm
            if (power <= 0) { stop = t; break }
        }
        return DamageRay(origin, origin + dir.scale(stop), kind, hit)
    }

    private const val SPALL_SALT = 0x5BA11L
    private const val BLAST_SALT = 0xB1A57L
}
