// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.compat

import com.tacz.guns.api.event.common.EntityHurtByGunEvent
import com.tacz.guns.api.event.common.GunDamageSourcePart
import com.tacz.guns.api.event.server.AmmoHitBlockEvent
import com.tacz.guns.entity.EntityKineticBullet
import io.github.jwyoon1220.dncity.ballistics.ShellRegistry
import io.github.jwyoon1220.dncity.ballistics.smallarms.BallisticBlockCategories
import io.github.jwyoon1220.dncity.ballistics.smallarms.BodyArmorTable
import io.github.jwyoon1220.dncity.ballistics.smallarms.BulletSpec
import io.github.jwyoon1220.dncity.ballistics.smallarms.ProtectionLevel
import io.github.jwyoon1220.dncity.ballistics.smallarms.SmallArmsData
import io.github.jwyoon1220.dncity.ballistics.smallarms.SteelPenetration
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorSpec
import io.github.jwyoon1220.dncity.item.component.BallisticArmorState
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent

/**
 * Small-arms penetration for TACZ bullets ([io.github.jwyoon1220.dncity.ballistics.smallarms]):
 *
 * - **Blocks** ([AmmoHitBlockEvent]): the block's material category decides; RESIST materials take energy over the bullet's path
 *   through the block's collision shape. If the bullet gets through, TACZ is told where it left the block and how much speed it
 *   kept (`setPenetrated`), and keeps tracing from there (the TACZ fork's segmented tick).
 * - **Living targets** ([EntityHurtByGunEvent.Pre]): the armor piece in the slot that was hit (helmet for head shots, otherwise by
 *   hit height) is soft armor of an NIJ level; stopped bullets do blunt damage only, perforating ones are scaled by the energy left.
 *   Damage is also scaled by the energy blocks already took. Both damage parts are then dealt as TACZ's armor-piercing source, so
 *   vanilla armor and First Aid's per-part armor do not reduce the same bullet a second time.
 * - **Armor wear**: every hit on a ballistic piece lowers its integrity ([BallisticArmorState], in proportion to the bullet's `E / A`
 *   over the piece's threshold, [BodyArmorTable.wornIntegrity]); a piece hit `wear.max_hits` times stops nothing.
 *
 * The bullet's speed is the shell's sourced velocity times how much TACZ's own flight (friction, blocks) has slowed it
 * (`currentSpeed / initialSpeed`): TACZ's block-per-tick speeds are a gameplay value, the real speed comes from the data.
 * Bullets whose shell has no `small_arms` data, and explosive rounds, are left entirely to TACZ.
 */
object TaczSmallArmsBridge {
    /** Share of the bullet's energy left after the blocks it went through (persistent data, so it rides with the entity). */
    private const val ENERGY_FRACTION_KEY = "dncity:energy_fraction"

    /** Fraction of the height of a living target below which a hit counts as feet / legs / torso (First Aid's player boxes). */
    private const val FEET_TOP = 0.15
    private const val LEGS_TOP = 0.45
    private const val CHEST_TOP = 0.8

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onHitBlock)
        NeoForge.EVENT_BUS.addListener(::onGunHurt)
        NeoForge.EVENT_BUS.addListener(::onEquipmentChange)
    }

    private fun bulletSpec(bullet: EntityKineticBullet): BulletSpec? {
        val shell = ShellRegistry.of(bullet) ?: return null
        if (shell.explosive) return null
        return shell.smallArms
    }

    /** Real speed of the bullet now, m/s. */
    private fun speedMps(bullet: EntityKineticBullet, spec: BulletSpec): Double {
        val initial = bullet.initialSpeed.toDouble()
        val ratio = if (initial > 0.0) bullet.currentSpeed / initial else 1.0
        return spec.referenceVelocityMps * ratio
    }

    private fun energyFraction(bullet: EntityKineticBullet): Double {
        val data = bullet.persistentData
        return if (data.contains(ENERGY_FRACTION_KEY)) data.getDouble(ENERGY_FRACTION_KEY) else 1.0
    }

    private fun onHitBlock(event: AmmoHitBlockEvent) {
        val table = SmallArmsData.blocks ?: return
        val bullet = event.ammo
        val spec = bulletSpec(bullet) ?: return
        val category = BallisticBlockCategories.of(event.state)
        val hit = event.hitResult
        val entry = hit.location
        val direction = bullet.deltaMovement.normalize()
        if (direction.lengthSqr() == 0.0) return
        val exit = exitPoint(event, entry, direction)
        val speedIn = speedMps(bullet, spec)
        val result = table.solve(category, spec, speedIn, entry.distanceTo(exit))
        if (!result.perforated) return
        val factor = (result.exitSpeedMps / speedIn).toFloat()
        if (!(factor > 0f)) return
        event.setPenetrated(exit, factor.coerceAtMost(1f))
        bullet.persistentData.putDouble(ENERGY_FRACTION_KEY, energyFraction(bullet) * result.energyFraction)
    }

    /**
     * Where the flight line leaves the block: the block's collision shape is clipped from a point beyond it back towards the entry,
     * so the first face found is the exit face. A block that is already gone (TACZ breaks glass on hit) or a shape the line only
     * grazes has no length inside it: the bullet leaves where it entered.
     */
    private fun exitPoint(event: AmmoHitBlockEvent, entry: Vec3, direction: Vec3): Vec3 {
        val pos = event.hitResult.blockPos
        val shape = event.level.getBlockState(pos).getCollisionShape(event.level, pos, CollisionContext.of(event.ammo))
        if (shape.isEmpty) return entry
        val far = entry.add(direction.scale(MAX_SHAPE_SPAN))
        return shape.clip(far, entry, pos)?.location ?: entry
    }

    /** Longer than any block collision shape's diagonal (a fence is 1.5 high), in blocks. */
    private const val MAX_SHAPE_SPAN = 3.0

    private fun onGunHurt(event: EntityHurtByGunEvent.Pre) {
        val bullet = event.bullet as? EntityKineticBullet ?: return
        val target = event.hurtEntity as? LivingEntity ?: return
        val spec = bulletSpec(bullet) ?: return
        var damage = event.baseAmount * energyFraction(bullet).toFloat()

        val armorTable = SmallArmsData.armor
        if (armorTable != null) {
            val slot = if (event.isHeadShot) EquipmentSlot.HEAD else slotAt(target, hitPoint(bullet, target))
            val armor = target.getItemBySlot(slot)
            val level = levelOf(armorTable, armor)
            if (level != null) {
                val multiplier = armorTable.multiplier(enchantments(armor))
                val speed = speedMps(bullet, spec)
                val state = armor.get(ModDataComponents.BALLISTIC_ARMOR) ?: BallisticArmorState.FRESH
                val integrity = state.integrity.toDouble()
                val result = armorTable.solve(level, multiplier, spec, speed, integrity)
                damage *= if (result.perforated) result.energyFraction.toFloat() else armorTable.bluntDamageFraction.toFloat()
                val hits = state.hits + 1
                armor.set(ModDataComponents.BALLISTIC_ARMOR, BallisticArmorState(armorTable.wornIntegrity(level, multiplier, spec, speed, integrity, hits).toFloat(), hits))
                armor.hurtAndBreak(1, target, slot)
            }
        }
        event.setBaseAmount(damage)
        // This model is the armor now: deal both parts as TACZ's armor-piercing source so no other armor reduces the bullet again
        event.setDamageSource(GunDamageSourcePart.NON_ARMOR_PIERCING, event.getDamageSource(GunDamageSourcePart.ARMOR_PIERCING))
    }

    private fun levelOf(table: BodyArmorTable, stack: ItemStack): ProtectionLevel? =
        (stack.item as? ArmorItem)?.material?.unwrapKey()?.map { it.location().toString() }?.orElse(null)?.let(table::levelFor)

    /**
     * Stamps a fresh [BallisticArmorState] onto a ballistic armor piece when it is put on, so the client (which has no armor table)
     * knows it counts for the red armor bar. A piece that was already hit keeps its state.
     */
    private fun onEquipmentChange(event: LivingEquipmentChangeEvent) {
        if (event.entity.level().isClientSide || event.slot.type != EquipmentSlot.Type.HUMANOID_ARMOR) return
        val stack = event.to
        if (stack.isEmpty || stack.has(ModDataComponents.BALLISTIC_ARMOR)) return
        val table = SmallArmsData.armor ?: return
        if (levelOf(table, stack) != null) stack.set(ModDataComponents.BALLISTIC_ARMOR, BallisticArmorState.FRESH)
    }

    /**
     * The bullet as a kinetic penetrator against sheet metal: the mild-steel depth of [SteelPenetration] at its current real speed
     * (after whatever blocks already slowed it). Null for bullets without small-arms data or without the block table.
     */
    fun steelPenetration(bullet: EntityKineticBullet): PenetratorSpec? {
        val table = SmallArmsData.blocks ?: return null
        val spec = bulletSpec(bullet) ?: return null
        return PenetratorSpec(PenetratorKind.KE, SteelPenetration.mildSteelMm(table, spec, speedMps(bullet, spec)))
    }

    /** Where the bullet's flight line enters the target's box this tick, or the bullet's position if it does not. */
    private fun hitPoint(bullet: EntityKineticBullet, target: LivingEntity): Vec3 {
        val start = bullet.position()
        return target.boundingBox.clip(start, start.add(bullet.deltaMovement)).orElse(start)
    }

    private fun slotAt(target: LivingEntity, point: Vec3): EquipmentSlot {
        val box = target.boundingBox
        val h = ((point.y - box.minY) / box.ysize).coerceIn(0.0, 1.0)
        return when {
            h < FEET_TOP -> EquipmentSlot.FEET
            h < LEGS_TOP -> EquipmentSlot.LEGS
            h < CHEST_TOP -> EquipmentSlot.CHEST
            else -> EquipmentSlot.HEAD
        }
    }

    private fun enchantments(stack: ItemStack): Map<String, Int> =
        stack.tagEnchantments.entrySet().mapNotNull { e ->
            e.key.unwrapKey().map { it.location().toString() to e.intValue }.orElse(null)
        }.toMap()
}
