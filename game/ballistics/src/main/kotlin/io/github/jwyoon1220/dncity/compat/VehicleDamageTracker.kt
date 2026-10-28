// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.compat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmorRegistry
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.DestroyCause
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.ReactiveHit
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.VehicleDamageState
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Collections
import java.util.WeakHashMap
import kotlin.random.Random

/**
 * The server's [VehicleDamageState] per SuperbWarfare vehicle: kept in memory while the vehicle is loaded, saved in its
 * persistent data (`dncity_damage`) so a damaged tank stays damaged across a restart, and ticked while something burns.
 *
 * - A vehicle SuperbWarfare has repaired back to full health (and that is not burning or a wreck) gets its modules back.
 * - Fires burn the hull's health down, hurt the crew modules and the passengers sitting in those seats, smoke, and can cook
 *   off the ammunition ([VehicleDamageState.tickFires]).
 */
object VehicleDamageTracker {
    private const val NBT_KEY = "dncity_damage"
    private const val TICK_INTERVAL = 10
    private const val DT_SECONDS = TICK_INTERVAL / 20.0
    /** Player hit points per crew-module hit point: a 100 hp crew module is a 20 hp player. */
    const val CREW_TO_ENTITY_DAMAGE = 0.2f

    private val states = WeakHashMap<VehicleEntity, VehicleDamageState>()
    private val burning: MutableSet<VehicleEntity> = Collections.newSetFromMap(WeakHashMap())

    fun state(vehicle: VehicleEntity): VehicleDamageState {
        val state = states.getOrPut(vehicle) { load(vehicle) }
        if (!state.isPristine && !state.isBurning && state.destroyed == null && !vehicle.isWreck && vehicle.health >= vehicle.getMaxHealth()) {
            state.repairAll()
            save(vehicle, state)
        }
        return state
    }

    fun changed(vehicle: VehicleEntity, state: VehicleDamageState) {
        if (state.isBurning) burning += vehicle
        save(vehicle, state)
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        if (event.server.tickCount % TICK_INTERVAL != 0 || burning.isEmpty()) return
        for (vehicle in burning.toList()) {
            val state = states[vehicle]
            if (vehicle.isRemoved || state == null || !state.isBurning) { burning -= vehicle; continue }
            val armor = VehicleArmorRegistry[BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type)] ?: run { burning -= vehicle; continue }
            val rng = Random(vehicle.id.toLong() * 31 + vehicle.level().gameTime)
            val tick = state.tickFires(armor.modules, DT_SECONDS, rng)

            vehicle.onHurt(tick.hullShare.toFloat() * vehicle.getMaxHealth(), vehicle.lastAttacker, false)
            for ((id, dmg) in tick.crewDamage) {
                val seat = armor.modules.firstOrNull { it.id == id }?.seat ?: continue
                (vehicle.getNthEntity(seat) as? LivingEntity)?.hurt(vehicle.damageSources().onFire(), dmg.toFloat() * CREW_TO_ENTITY_DAMAGE)
            }
            tick.destroyed?.let { SbwVehicleArmorBridge.destroy(vehicle, it) }
            smoke(vehicle, state)
            if (!state.isBurning) burning -= vehicle
            save(vehicle, state)
        }
    }

    private fun smoke(vehicle: VehicleEntity, state: VehicleDamageState) {
        val level = vehicle.level() as? ServerLevel ?: return
        val c = vehicle.boundingBox.center
        val top = vehicle.boundingBox.maxY
        repeat(state.burning.size) {
            level.sendParticles(ParticleTypes.FLAME, c.x, top - 0.3, c.z, 6, 0.6, 0.2, 0.6, 0.02)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, top, c.z, 4, 0.5, 0.3, 0.5, 0.03)
        }
    }

    private fun load(vehicle: VehicleEntity): VehicleDamageState {
        val state = VehicleDamageState()
        val tag = vehicle.persistentData.getCompound(NBT_KEY)
        if (tag.isEmpty) return state
        runCatching {
            val damage = tag.getCompound("damage").let { d -> d.allKeys.associateWith { d.getDouble(it) } }
            val fires = tag.getCompound("fires").let { f -> f.allKeys.associateWith { f.getDouble(it) } }
            val reactive = tag.getList("reactive", Tag.TAG_COMPOUND.toInt()).map { e ->
                val r = e as CompoundTag
                ReactiveHit(r.getInt("box"), r.getInt("face"), V3(r.getDouble("x"), r.getDouble("y"), r.getDouble("z")), r.getDouble("r"))
            }
            val destroyed = tag.getString("destroyed").takeIf { it.isNotEmpty() }?.let { DestroyCause.valueOf(it) }
            state.restore(VehicleDamageState.Snapshot(damage, fires, reactive, destroyed))
        }
        if (state.isBurning) burning += vehicle
        return state
    }

    private fun save(vehicle: VehicleEntity, state: VehicleDamageState) {
        if (state.isPristine) { vehicle.persistentData.remove(NBT_KEY); return }
        val s = state.snapshot()
        val tag = CompoundTag()
        tag.put("damage", CompoundTag().also { d -> s.damage.forEach { (k, v) -> d.putDouble(k, v) } })
        tag.put("fires", CompoundTag().also { f -> s.fires.forEach { (k, v) -> f.putDouble(k, v) } })
        tag.put("reactive", ListTag().also { l ->
            s.reactive.forEach { r ->
                l.add(CompoundTag().apply {
                    putInt("box", r.box); putInt("face", r.face)
                    putDouble("x", r.local.x); putDouble("y", r.local.y); putDouble("z", r.local.z); putDouble("r", r.radiusM)
                })
            }
        })
        s.destroyed?.let { tag.putString("destroyed", it.name) }
        vehicle.persistentData.put(NBT_KEY, tag)
    }
}
