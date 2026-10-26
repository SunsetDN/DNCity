// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.ballistics.vehicle.damage

import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.random.Random

/** A spot where a reactive layer fired: the box hit (OBB index), the face, and the point in that box's own frame. */
data class ReactiveHit(val box: Int, val face: Int, val local: V3, val radiusM: Double)

/**
 * What one vehicle has suffered: damage per module, fires burning, reactive armor used up, and whether it is out.
 * Mutable, one per vehicle, owned by the server; [snapshot] / [restore] carry it through the entity's saved data.
 */
class VehicleDamageState {
    private val damage = HashMap<String, Double>()
    private val fires = LinkedHashMap<String, Double>()
    private val reactive = ArrayList<ReactiveHit>()

    var destroyed: DestroyCause? = null
        private set

    val burning: Set<String> get() = fires.keys
    val isBurning: Boolean get() = fires.isNotEmpty()
    val reactiveHits: List<ReactiveHit> get() = reactive
    val isPristine: Boolean get() = damage.isEmpty() && fires.isEmpty() && reactive.isEmpty() && destroyed == null

    fun damageOf(id: String): Double = damage[id] ?: 0.0

    fun isDestroyed(spec: ModuleSpec): Boolean = damageOf(spec.id) >= spec.hp

    /** 0 untouched, 1 damaged, 2 destroyed (crew: knocked out). */
    fun condition(spec: ModuleSpec): Int = when {
        isDestroyed(spec) -> 2
        damageOf(spec.id) > 0 -> 1
        else -> 0
    }

    /** Whether the reactive layer is still there at [local] on [box]'s [face]. */
    fun reactiveIntact(box: Int, face: Int, local: V3): Boolean =
        reactive.none { it.box == box && it.face == face && it.local.distanceTo(local) < it.radiusM }

    fun recordReactive(hit: ReactiveHit) {
        reactive += hit
        while (reactive.size > MAX_REACTIVE_HITS) reactive.removeAt(0)
    }

    fun apply(report: DamageReport) {
        for (h in report.hits) damage.merge(h.id, h.damage, Double::plus)
        for ((id, seconds) in report.newFires) fires.putIfAbsent(id, seconds)
        if (destroyed == null) destroyed = report.destroyed
    }

    /**
     * One step of the fires. Each fire burns down (faster while crew are left to fight it), hurts the crew still in the vehicle
     * and its own module, and while any fire burns the ammunition may cook off. DESIGN rates.
     */
    fun tickFires(specs: List<ModuleSpec>, dtSeconds: Double, rng: Random): FireTick {
        if (fires.isEmpty()) return FireTick.NONE
        val events = ArrayList<DamageEvent>()
        val crew = specs.filter { it.type == ModuleType.CREW }
        val crewHurt = LinkedHashMap<String, Double>()
        val aliveBefore = crew.filter { !isDestroyed(it) }
        val fighting = if (crew.isEmpty()) 0.0 else aliveBefore.size.toDouble() / crew.size

        for (c in aliveBefore) {
            val dmg = FIRE_CREW_DPS * dtSeconds * fires.size
            damage.merge(c.id, dmg, Double::plus)
            crewHurt[c.id] = dmg
            if (isDestroyed(c)) events += DamageEvent(EventType.CREW_KNOCKED_OUT, c.id)
        }
        val out = ArrayList<String>()
        for (id in fires.keys.toList()) {
            specs.firstOrNull { it.id == id }?.let { damage.merge(id, FIRE_MODULE_DPS * dtSeconds, Double::plus) }
            val left = fires.getValue(id) - dtSeconds * (1 + CREW_EXTINGUISH_SPEEDUP * fighting)
            if (left <= 0) out += id else fires[id] = left
        }
        out.forEach { fires.remove(it); events += DamageEvent(EventType.FIRE_OUT, it) }

        var cause: DestroyCause? = null
        val rack = specs.firstOrNull { it.type == ModuleType.AMMO && !isDestroyed(it) }
        if (destroyed == null && rack != null && fires.isNotEmpty() && rng.nextDouble() < COOK_OFF_PER_SECOND * dtSeconds) {
            damage[rack.id] = rack.hp
            events += DamageEvent(EventType.COOK_OFF, rack.id)
            cause = DestroyCause.AMMO_DETONATION
        }
        if (destroyed == null && cause == null && DamageModel.crewOut(specs, ::damageOf)) cause = DestroyCause.CREW_KNOCKED_OUT
        if (cause != null) {
            destroyed = cause
            events += DamageEvent(EventType.VEHICLE_DESTROYED)
        }
        return FireTick(fires.size * FIRE_HULL_SHARE_PER_SECOND * dtSeconds, crewHurt, events, cause)
    }

    /** Back to new: what a full repair does. */
    fun repairAll() {
        damage.clear(); fires.clear(); reactive.clear(); destroyed = null
    }

    fun snapshot(): Snapshot = Snapshot(HashMap(damage), LinkedHashMap(fires), ArrayList(reactive), destroyed)

    fun restore(s: Snapshot) {
        damage.clear(); damage.putAll(s.damage)
        fires.clear(); fires.putAll(s.fires)
        reactive.clear(); reactive.addAll(s.reactive.takeLast(MAX_REACTIVE_HITS))
        destroyed = s.destroyed
    }

    data class Snapshot(
        val damage: Map<String, Double>,
        val fires: Map<String, Double>,
        val reactive: List<ReactiveHit>,
        val destroyed: DestroyCause?,
    )

    companion object {
        const val MAX_REACTIVE_HITS = 64
        /** Damage per second each fire does to every crew member still in the vehicle. */
        const val FIRE_CREW_DPS = 3.0
        const val FIRE_MODULE_DPS = 10.0
        /** Share of the vehicle's own health one fire burns per second. */
        const val FIRE_HULL_SHARE_PER_SECOND = 0.012
        /** A full crew puts a fire out this much faster. */
        const val CREW_EXTINGUISH_SPEEDUP = 0.8
        const val COOK_OFF_PER_SECOND = 0.025
    }
}

data class FireTick(
    /** Share of the vehicle's maximum health the fires took this step. */
    val hullShare: Double,
    /** Crew module id -> damage taken this step. */
    val crewDamage: Map<String, Double>,
    val events: List<DamageEvent>,
    val destroyed: DestroyCause?,
) {
    companion object {
        val NONE = FireTick(0.0, emptyMap(), emptyList(), null)
    }
}
