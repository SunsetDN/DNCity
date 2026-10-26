// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.compat

import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import io.github.jwyoon1220.dncity.ballistics.ShellRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import io.github.jwyoon1220.dncity.ballistics.vehicle.ArmorSolver
import io.github.jwyoon1220.dncity.ballistics.vehicle.Face
import io.github.jwyoon1220.dncity.ballistics.vehicle.HitOutcome
import io.github.jwyoon1220.dncity.ballistics.vehicle.HitSolution
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorSpec
import io.github.jwyoon1220.dncity.ballistics.vehicle.RayBox
import io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmor
import io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmorRegistry
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.Anchor
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.BlastInjury
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.DamageModel
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.DamageReport
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.DestroyCause
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.Interior
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.ModuleType
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.OrientedBox
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.Penetration
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.PlacedModule
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.RayKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.ReactiveHit
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.VehicleDamageState
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.Warhead
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.placeModules
import io.github.jwyoon1220.dncity.network.VehicleHitReportPayload
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.entity.PartEntity
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Armor penetration and War Thunder-style damage on SuperbWarfare vehicles. A hit is resolved when the vehicle has a
 * `dncity/vehicle_armor` file and the projectile's shell (or TACZ round, [TaczVehicleArmorBridge]) has `armor_penetration`;
 * otherwise SuperbWarfare's own damage runs unchanged.
 *
 * - The shell's line is traced against the vehicle's OBBs (not the overall collision box): the first box entered gives the
 *   zone (hull/turret/tracks), the face it went in through gives the plate, the angle to that face's normal the obliquity.
 *   The plate's ERA/NERA counts if that spot is still intact ([VehicleDamageState.reactiveIntact]); a hit that sets it off
 *   uses it up there. Ricochet is a chance over the last degrees before the shell's ricochet angle; a shell three times the
 *   plate's thickness overmatches it.
 * - Not penetrated: the hit is cancelled and the shell stops. Ricochet: cancelled, a kinetic shell glances off the plate, a
 *   shaped charge or HE duds. Penetrated: SuperbWarfare's damage runs, and [DamageModel] plays out behind the plate: the shell
 *   or jet, its spall cone and any filler go through the vehicle's modules ([VehicleArmor.modules], placed in its current
 *   hull/turret/engine boxes). Crew sitting in a hit crew seat get hurt, a destroyed engine/breech/turret drive switches the
 *   SuperbWarfare part off, an ammo detonation blows the vehicle up, losing the crew wrecks it, fires keep burning
 *   ([VehicleDamageTracker]).
 * - The shooter gets a [VehicleHitReportPayload] for the hit camera, spall and module states included.
 * - A vehicle without OBBs is traced against its collision box as one `hull` box turned with the vehicle.
 */
object SbwVehicleArmorBridge {
    private const val TRACE_BACK_BLOCKS = 6.0
    /** OBB index reported for a hit on the collision box of a vehicle without OBBs. */
    private const val BOUNDING_BOX_INDEX = -1
    /** Ricochet speed kept by a glancing kinetic shell. DESIGN. */
    private const val RICOCHET_SPEED_KEPT = 0.5
    /** Part damage after a penetration of a vehicle without modules: a base hit plus a share of the residual. DESIGN. */
    private const val MODULE_DAMAGE_BASE = 40f
    private const val MODULE_DAMAGE_PER_RESIDUAL_MM = 0.15f
    /** A charge that got through goes off this far behind the plate (DamageModel bursts HE at 0.15 m). DESIGN. */
    private const val BURST_DEPTH_BLOCKS = 0.15
    /** From this much explosive the burst gets the big explosion cloud (a 120/125 mm HE shell, ~3 kg). DESIGN. */
    private const val BIG_BURST_KG = 2.0

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onProjectileHitEntity)
        NeoForge.EVENT_BUS.addListener(VehicleDamageTracker::onServerTick)
        // a tank gun loaded with a TACZ-style round (`"AmmoType": "@dncity:m829"`) tags its shell with the ammo id, which is then
        // the key (`"key": "dncity:m829"`); untagged shells keep the vehicle they were fired from as their key
        ShellRegistry.registerKeyResolver(ResourceLocation.fromNamespaceAndPath("superbwarfare", "cannon_shell")) { entity ->
            entity.persistentData.getString(AMMO_ID_TAG).ifEmpty { null } ?: ShellRegistry.shooterVehicleKey(entity)
        }
    }

    /** Set by SuperbWarfare's GunItem.shootBullet on projectiles fired with TACZ calibre ammo. */
    private const val AMMO_ID_TAG = "AmmoId"

    private fun onProjectileHitEntity(event: ProjectileHitEvent.HitEntity) {
        val projectile = event.projectile
        if (projectile.level().isClientSide) return
        var target: Entity = event.target
        if (target is PartEntity<*>) target = target.parent
        val vehicle = target as? VehicleEntity ?: return
        val shell = ShellRegistry.of(projectile) ?: return
        val spec = shell.armorPenetration ?: return
        val result = resolve(vehicle, spec, event.hitVec, projectile.deltaMovement, projectile.owner, shell.caliberMm.toDouble(), shell.explosive)
            ?: return

        when (result.solution.outcome) {
            HitOutcome.PENETRATED -> applyPenetration(vehicle, result, projectile.owner, projectile)
            HitOutcome.NOT_PENETRATED -> {
                event.isCanceled = true
                projectile.discard()
                // the plate held, but the shell still bursts on it and the riders feel it
                applyBlast(vehicle, result, projectile.owner, projectile)
            }
            HitOutcome.RICOCHET -> {
                event.isCanceled = true
                if (spec.kind == PenetratorKind.KE) glance(projectile, result) else projectile.discard()
            }
        }
        report(projectile.owner, vehicle, result)
    }

    class Resolved(
        val solution: HitSolution,
        val obbIndex: Int,
        val zone: String,
        val face: Face,
        val entry: Vec3,
        val direction: Vec3,
        val normal: Vec3,
        /** Legacy part name (engine, turret, track_left, ...) -> world point, for vehicles without modules and for tracks. */
        val modules: Map<String, Vec3>,
        val stop: Vec3,
        /** What happened inside; [DamageReport.NONE] unless penetrated and the vehicle has modules. */
        val damage: DamageReport = DamageReport.NONE,
        val placed: List<PlacedModule> = emptyList(),
        val state: VehicleDamageState? = null,
        /** Explosive the warhead carries, kg ([Warhead.fillerKg]); 0 = none. */
        val fillerKg: Double = 0.0,
        val openTop: Boolean = false,
    )

    /**
     * Resolves the hit, records a fired reactive layer, and for a penetration simulates the damage inside (not yet applied:
     * [applyPenetration] does that). Returns null when the line misses every armored box.
     */
    fun resolve(
        vehicle: VehicleEntity, spec: PenetratorSpec, hitPos: Vec3, velocity: Vec3, shooter: Entity?,
        caliberMm: Double = 0.0, explosive: Boolean = false,
    ): Resolved? {
        val armor = VehicleArmorRegistry[BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type)] ?: return null
        if (velocity.lengthSqr() < 1e-8) return null
        val dir = velocity.normalize()
        val origin = hitPos.subtract(dir.scale(TRACE_BACK_BLOCKS))
        val o = doubleArrayOf(origin.x, origin.y, origin.z)
        val d = doubleArrayOf(dir.x, dir.y, dir.z)

        var best: Triple<Int, Double, Face>? = null
        vehicle.getOBBs().forEachIndexed { index, obb ->
            if (!isSolid(obb)) return@forEachIndexed
            if (armor.zoneFor(index, obb.part.name) == null) return@forEachIndexed
            val hit = RayBox.enter(o, d, vec(obb.center), axes(obb), vec(obb.extents)) ?: return@forEachIndexed
            if (best == null || hit.first < best!!.second) best = Triple(index, hit.first, hit.second)
        }
        val box: OrientedBox
        val zone: String
        if (best != null) {
            val obb = vehicle.getOBBs()[best!!.first]
            box = boxOf(obb)
            zone = armor.zoneFor(best!!.first, obb.part.name)!!
        } else {
            // A vehicle without OBBs (SuperbWarfare gives some aircraft none): its collision box stands in as one hull box
            if (vehicle.getOBBs().any(::isSolid)) return null
            box = boundingHull(vehicle)
            val hit = RayBox.enter(o, d, arr(box.center), box.axes.map(::arr).toTypedArray(), arr(box.half)) ?: return null
            best = Triple(BOUNDING_BOX_INDEX, hit.first, hit.second)
            zone = "hull"
        }
        val boxAxes = box.axes.map(::arr).toTypedArray()
        val (index, t, face) = best!!
        val normalArr = RayBox.normal(face, boxAxes)
        val normal = Vec3(normalArr[0], normalArr[1], normalArr[2])
        val entry = origin.add(dir.scale(t))
        val angle = RayBox.impactAngleDeg(d, normalArr)
        val distance = shooter?.position()?.distanceTo(entry) ?: 0.0
        val zoneArmor = armor.zones.getValue(zone)
        val plate = zoneArmor.plate(face)

        val state = VehicleDamageTracker.state(vehicle)
        val local = box.toLocal(v3(entry))
        val rng = Random(seedOf(vehicle, entry))
        val intact = plate.reactive == null || state.reactiveIntact(index, face.ordinal, local)
        val solution = ArmorSolver.solve(spec, plate, angle, distance, caliberMm, intact, rng.nextDouble())
        plate.reactive?.let { r ->
            if (solution.reactiveFired == null) return@let
            state.recordReactive(ReactiveHit(index, face.ordinal, local, r.tileM))
            VehicleDamageTracker.changed(vehicle, state)
            (vehicle.level() as? ServerLevel)?.sendParticles(ParticleTypes.EXPLOSION, entry.x, entry.y, entry.z, 1, 0.0, 0.0, 0.0, 0.0)
        }

        val modules = LinkedHashMap<String, Vec3>()
        var damage = DamageReport.NONE
        var placed = emptyList<PlacedModule>()
        val stop = when (solution.outcome) {
            HitOutcome.PENETRATED -> {
                val end = entry.add(dir.scale(solution.depthBlocks))
                collectModules(vehicle, entry, end, modules)
                if (armor.modules.isNotEmpty()) {
                    placed = placeModules(armor.modules, anchorsOf(vehicle, box))
                    val interior = Interior(interiorOf(vehicle).ifEmpty { listOf(box) })
                    val pen = Penetration(
                        entry = v3(entry), dir = v3(dir), kind = spec.kind,
                        residualMm = solution.residualMm, caliberMm = caliberMm,
                        fillerKg = Warhead.fillerKg(spec, caliberMm, explosive), fuseDelayM = spec.fuseDelayM,
                        fuseArmed = solution.effectiveArmorMm >= spec.fuseSensitivityMm,
                        spallLiner = zoneArmor.spallLiner, overmatch = solution.overmatch,
                    )
                    damage = DamageModel.simulate(pen, placed, interior, state, rng.nextLong())
                }
                damage.rays.firstOrNull { it.kind == RayKind.SHELL || it.kind == RayKind.JET }
                    ?.let { mc(it.to) } ?: end
            }
            HitOutcome.NOT_PENETRATED -> entry
            HitOutcome.RICOCHET -> entry.add(reflect(dir, normal).scale(2.0))
        }
        val filler = Warhead.fillerKg(spec, caliberMm, explosive)
        return Resolved(solution, index, zone, face, entry, dir, normal, modules, stop, damage, placed, state, filler, armor.openTop)
    }

    /** Applies a penetration's damage: module states, SuperbWarfare parts, the crew in their seats, fires, the verdict. */
    fun applyPenetration(vehicle: VehicleEntity, result: Resolved, attacker: Entity?, direct: Entity?) {
        val source = vehicle.damageSources().explosion(direct, attacker)
        // one hurt per rider: the worse of what the modules gave their seat and what the blast does where they sit
        val injuries = blastInjuries(vehicle, result).toMutableMap()
        if (result.openTop) blastEffect(vehicle, result)
        val state = result.state
        if (state == null || result.placed.isEmpty()) {
            damageParts(vehicle, result, result.modules.keys)
            injure(injuries, source)
            return
        }
        // tracks are their own boxes outside the modules: hit them the old way
        damageParts(vehicle, result, result.modules.keys.filter { it.startsWith("track_") })
        val report = result.damage
        state.apply(report)
        val specs = result.placed.map { it.spec }
        fun condition(type: ModuleType): Float {
            val ms = specs.filter { it.type == type }
            if (ms.isEmpty()) return 1f
            return ms.minOf { (1 - state.damageOf(it.id) / it.hp).coerceIn(0.0, 1.0) }.toFloat()
        }
        val hitTypes = report.hits.map { it.type }.toSet()
        if (ModuleType.ENGINE in hitTypes) {
            vehicle.mainEngineHealth = minOf(vehicle.mainEngineHealth, vehicle.getMainEngineMaxHealth() * condition(ModuleType.ENGINE))
        }
        if (ModuleType.TRANSMISSION in hitTypes) {
            vehicle.subEngineHealth = minOf(vehicle.subEngineHealth, vehicle.getSubEngineMaxHealth() * condition(ModuleType.TRANSMISSION))
        }
        if (ModuleType.BREECH in hitTypes || ModuleType.TURRET_DRIVE in hitTypes) {
            val worst = minOf(condition(ModuleType.BREECH), condition(ModuleType.TURRET_DRIVE))
            vehicle.turretHealth = minOf(vehicle.turretHealth, vehicle.getTurretMaxHealth() * worst)
        }

        for (hit in report.hits) {
            if (hit.type != ModuleType.CREW) continue
            val seat = specs.firstOrNull { it.id == hit.id }?.seat ?: continue
            val passenger = vehicle.getNthEntity(seat) as? LivingEntity ?: continue
            val amount = if (hit.destroyedNow) passenger.maxHealth * 2 else hit.damage.toFloat() * VehicleDamageTracker.CREW_TO_ENTITY_DAMAGE
            injuries.merge(passenger, amount, ::maxOf)
        }
        injure(injuries, source)
        report.destroyed?.let { destroy(vehicle, it) }
        VehicleDamageTracker.changed(vehicle, state)
    }

    /**
     * A warhead that did not get through still goes off on the plate (the projectile is cancelled, so this is its burst):
     * riders of an open-top vehicle take it in free air, a closed hull passes on a share of it ([BlastInjury.transmitted]).
     * Penetrations go through [applyPenetration], which includes the blast.
     */
    fun applyBlast(vehicle: VehicleEntity, result: Resolved, attacker: Entity?, direct: Entity?) {
        if (result.solution.outcome == HitOutcome.RICOCHET) return
        val injuries = blastInjuries(vehicle, result)
        blastEffect(vehicle, result)
        injure(injuries, vehicle.damageSources().explosion(direct, attacker))
    }

    /**
     * Overpressure at each rider from where the warhead goes off ([BlastInjury]): inside the hull after a penetration (doubled by
     * the walls unless the top is open), or on the plate when it held: in full for an open-top vehicle, through the plate
     * ([BlastInjury.transmitted]) for a closed one.
     */
    fun blastInjuries(vehicle: VehicleEntity, result: Resolved): Map<LivingEntity, Float> {
        if (result.fillerKg <= 0 || result.solution.outcome == HitOutcome.RICOCHET) return emptyMap()
        val inside = result.solution.outcome == HitOutcome.PENETRATED
        val burst = burstPoint(result)
        val confined = inside && !result.openTop
        val through = if (inside || result.openTop) 1.0 else BlastInjury.transmitted(result.solution.armorMm)
        val out = LinkedHashMap<LivingEntity, Float>()
        for (rider in vehicle.passengers) {
            val living = rider as? LivingEntity ?: continue
            val kpa = through * BlastInjury.overpressureKpa(living.boundingBox.center.distanceTo(burst), result.fillerKg, confined)
            val share = BlastInjury.healthShare(kpa)
            if (share > 0) out[living] = (share * living.maxHealth).toFloat()
        }
        return out
    }

    /** Where the filler goes off: the damage model's burst inside, just behind the plate, or on the plate. */
    private fun burstPoint(result: Resolved): Vec3 {
        result.damage.blast?.let { return mc(it.center) }
        return if (result.solution.outcome == HitOutcome.PENETRATED) result.entry.add(result.direction.scale(BURST_DEPTH_BLOCKS))
        else result.entry
    }

    /** The burst on or in the vehicle: a blast cloud the size of the charge and the bang. */
    private fun blastEffect(vehicle: VehicleEntity, result: Resolved) {
        if (result.fillerKg <= 0) return
        val level = vehicle.level() as? ServerLevel ?: return
        val at = burstPoint(result)
        val big = result.fillerKg >= BIG_BURST_KG
        level.sendParticles(if (big) ParticleTypes.EXPLOSION_EMITTER else ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0)
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.6, 0.4, 0.6, 0.02)
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, 0.9f)
    }

    private fun injure(injuries: Map<LivingEntity, Float>, source: DamageSource) {
        for ((rider, amount) in injuries) if (amount > 0) rider.hurt(source, amount)
    }

    /** Out of the fight: an ammo detonation blows the vehicle apart, a lost crew leaves a wreck. */
    fun destroy(vehicle: VehicleEntity, cause: DestroyCause) {
        when (cause) {
            // below -max health SuperbWarfare wrecks it, blows it up and drops the wreckage in the same tick
            DestroyCause.AMMO_DETONATION -> vehicle.health = -vehicle.getMaxHealth() - 1
            DestroyCause.CREW_KNOCKED_OUT -> if (vehicle.health > 0) vehicle.health = 0f
        }
    }

    private fun collectModules(vehicle: VehicleEntity, from: Vec3, to: Vec3, out: MutableMap<String, Vec3>) {
        val a = Vector3d(from.x, from.y, from.z)
        val b = Vector3d(to.x, to.y, to.z)
        val obbs = vehicle.getOBBs().filter(::isSolid)
        for (obb in obbs) {
            val module = moduleOf(obb.part) ?: continue
            val reached = obb.clip(a, b)
            if (reached.isPresent) out.putIfAbsent(module, reached.get().let { Vec3(it.x, it.y, it.z) })
        }
        // a block past the plate and still inside a hull box: the fighting compartment (crew) is reached
        val length = from.distanceTo(to)
        if (length >= 1.0) {
            val inside = from.add(to.subtract(from).scale(1.0 / length))
            if (obbs.any { moduleOf(it.part) == null && it.contains(inside) }) out.putIfAbsent("crew", inside)
        }
    }

    private fun moduleOf(part: OBB.Part): String? = when (part) {
        OBB.Part.MAIN_ENGINE, OBB.Part.SUB_ENGINE -> "engine"
        OBB.Part.TURRET -> "turret"
        OBB.Part.WHEEL_LEFT -> "track_left"
        OBB.Part.WHEEL_RIGHT -> "track_right"
        else -> null
    }

    private fun damageParts(vehicle: VehicleEntity, result: Resolved, parts: Collection<String>) {
        val damage = MODULE_DAMAGE_BASE + MODULE_DAMAGE_PER_RESIDUAL_MM * result.solution.residualMm.toFloat()
        for (module in parts) {
            when (module) {
                "engine" -> { vehicle.mainEngineHealth -= damage; vehicle.subEngineHealth -= damage }
                "turret" -> vehicle.turretHealth -= damage
                "track_left" -> vehicle.leftWheelHealth -= damage
                "track_right" -> vehicle.rightWheelHealth -= damage
            }
        }
    }

    /** The boxes modules are placed in: all hull boxes as one (or the box that was hit), the turret, the engine. */
    private fun anchorsOf(vehicle: VehicleEntity, fallback: OrientedBox): Map<Anchor, OrientedBox> {
        val solid = vehicle.getOBBs().filter(::isSolid)
        fun biggest(filter: (OBB.Part) -> Boolean) =
            solid.filter { filter(it.part) }.maxByOrNull { it.extents.x * it.extents.y * it.extents.z }?.let(::boxOf)
        val out = HashMap<Anchor, OrientedBox>()
        val hull = solid.filter { moduleOf(it.part) == null }.map(::boxOf)
        out[Anchor.HULL] = if (hull.isEmpty()) fallback else OrientedBox.enclosing(hull)
        biggest { it == OBB.Part.TURRET }?.let { out[Anchor.TURRET] = it }
        biggest { it == OBB.Part.MAIN_ENGINE }?.let { out[Anchor.ENGINE] = it }
        return out
    }

    /** Hull, turret and engine boxes: where fragments can fly before they hit the far wall. Wheels are outside. */
    private fun interiorOf(vehicle: VehicleEntity): List<OrientedBox> =
        vehicle.getOBBs().filter { isSolid(it) && it.part != OBB.Part.WHEEL_LEFT && it.part != OBB.Part.WHEEL_RIGHT }.map(::boxOf)

    /** The collision box as a hull box turned with the vehicle (+Z forward, +X left). */
    private fun boundingHull(vehicle: VehicleEntity): OrientedBox {
        val yaw = Math.toRadians(vehicle.yRot.toDouble())
        val forward = V3(-sin(yaw), 0.0, cos(yaw))
        val left = V3(cos(yaw), 0.0, sin(yaw))
        val c = vehicle.boundingBox.center
        val w = vehicle.bbWidth / 2.0
        return OrientedBox(V3(c.x, c.y, c.z), left, V3(0.0, 1.0, 0.0), forward, V3(w, vehicle.bbHeight / 2.0, w))
    }

    private fun seedOf(vehicle: VehicleEntity, entry: Vec3): Long =
        vehicle.uuid.leastSignificantBits xor (vehicle.level().gameTime * -0x61c8864680b583ebL) xor
            (entry.x * 1000).toLong() xor ((entry.y * 1000).toLong() shl 20) xor ((entry.z * 1000).toLong() shl 40)

    private fun glance(projectile: Projectile, result: Resolved) {
        val v = projectile.deltaMovement
        projectile.deltaMovement = reflect(v, result.normal).scale(RICOCHET_SPEED_KEPT)
        val out = result.entry.add(result.normal.scale(0.3))
        projectile.setPos(out.x, out.y, out.z)
    }

    private fun reflect(v: Vec3, n: Vec3): Vec3 = v.subtract(n.scale(2.0 * v.dot(n)))

    fun report(shooter: Entity?, vehicle: VehicleEntity, result: Resolved) {
        val player = shooter as? ServerPlayer ?: return
        val obbs = vehicle.getOBBs()
        val frame = obbs.firstOrNull { it.part == OBB.Part.COLLISION } ?: obbs.firstOrNull() ?: return
        val inverse = Quaterniond(frame.rotation).conjugate()
        val origin = Vector3d(frame.center)
        fun local(x: Double, y: Double, z: Double): FloatArray {
            val v = Vector3d(x, y, z).sub(origin)
            inverse.transform(v)
            return floatArrayOf(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
        }
        fun local(p: Vec3) = local(p.x, p.y, p.z)
        fun local(p: V3) = local(p.x, p.y, p.z)
        fun localDir(p: Vec3): FloatArray {
            val v = Vector3d(p.x, p.y, p.z)
            inverse.transform(v)
            return floatArrayOf(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
        }
        fun corners(points: List<V3>): FloatArray {
            val out = FloatArray(24)
            points.forEachIndexed { i, c -> local(c).copyInto(out, i * 3) }
            return out
        }
        val boxes = obbs.mapIndexedNotNull { index, obb ->
            if (!isSolid(obb)) return@mapIndexedNotNull null
            val partName = moduleOf(obb.part) ?: "hull"
            VehicleHitReportPayload.Box(partName, index == result.obbIndex || partName in result.modules, corners(obb.getVertices().map { V3(it.x, it.y, it.z) }))
        }
        val state = result.state
        val hitNow = result.damage.hits.map { it.id }.toSet()
        val moduleBoxes = result.placed.map { m ->
            VehicleHitReportPayload.ModuleBox(m.spec.id, m.spec.type.ordinal, state?.condition(m.spec) ?: 0, m.spec.id in hitNow, corners(m.box.corners()))
        }
        val rays = result.damage.rays.map { VehicleHitReportPayload.Ray(it.kind.ordinal, it.hitModule, local(it.from), local(it.to)) }
        val events = result.damage.events.map { e -> e.type.name.lowercase() + (e.moduleId?.let { ":$it" } ?: "") }
        val s = result.solution
        val damaged = if (result.placed.isEmpty()) result.modules.keys.toList() else result.damage.hits.map { it.id }
        PacketDistributor.sendToPlayer(
            player,
            VehicleHitReportPayload(
                vehicleType = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type).toString(),
                outcome = s.outcome.ordinal, kind = s.kind.ordinal,
                zone = result.zone, face = result.face.ordinal,
                armorMm = s.armorMm.toFloat(), effectiveMm = s.effectiveArmorMm.toFloat(), penetrationMm = s.penetrationMm.toFloat(),
                angleDeg = s.angleDeg.toFloat(), depthBlocks = s.depthBlocks.toFloat(),
                modules = damaged, boxes = boxes,
                entry = local(result.entry), direction = localDir(result.direction), stop = local(result.stop),
                reactiveType = s.reactiveFired?.ordinal ?: -1, reactiveMm = s.reactiveMm.toFloat(),
                overmatch = s.overmatch, ricochetChance = s.ricochetChance.toFloat(),
                moduleBoxes = moduleBoxes, rays = rays, events = events,
                blast = result.damage.blast?.let { local(it.center) + floatArrayOf(it.radiusM.toFloat()) } ?: FloatArray(4),
                destroyed = (state?.destroyed ?: result.damage.destroyed)?.ordinal ?: -1,
            ),
        )
    }

    private fun isSolid(obb: OBB) =
        obb.part != OBB.Part.COLLISION && obb.part != OBB.Part.INTERACTIVE &&
            obb.extents.x > 1e-4 && obb.extents.y > 1e-4 && obb.extents.z > 1e-4

    private fun boxOf(obb: OBB): OrientedBox {
        val a = obb.getAxes().map { V3(it.x, it.y, it.z) }
        return OrientedBox(V3(obb.center.x, obb.center.y, obb.center.z), a[0], a[1], a[2], V3(obb.extents.x, obb.extents.y, obb.extents.z))
    }

    private fun vec(v: Vector3d) = doubleArrayOf(v.x, v.y, v.z)

    private fun arr(v: V3) = doubleArrayOf(v.x, v.y, v.z)

    private fun v3(v: Vec3) = V3(v.x, v.y, v.z)

    private fun mc(v: V3) = Vec3(v.x, v.y, v.z)

    private fun axes(obb: OBB): Array<DoubleArray> = obb.getAxes().map { vec(it) }.toTypedArray()
}
