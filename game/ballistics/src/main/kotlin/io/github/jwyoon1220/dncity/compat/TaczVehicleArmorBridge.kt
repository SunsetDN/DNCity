// AGENT-DONE(claude): vehicle-armor-penetration
package io.github.jwyoon1220.dncity.compat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.tacz.guns.api.event.common.EntityHurtByGunEvent
import com.tacz.guns.entity.EntityKineticBullet
import io.github.jwyoon1220.dncity.ballistics.ShellRegistry
import io.github.jwyoon1220.dncity.ballistics.vehicle.HitOutcome
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorSpec
import io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmorRegistry
import net.minecraft.core.registries.BuiltInRegistries
import net.neoforged.fml.LogicalSide
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.entity.PartEntity

/**
 * TACZ bullets on armored SuperbWarfare vehicles, same resolution as [SbwVehicleArmorBridge]. The bullet's penetration is
 * the fired round's own (`armor_penetration` in `dncity/cartridges.json`, found through the TACZ round id), else its shell's,
 * else none: a round with no penetration value cannot get through any armor plate (rifle ball does not chip a tank). Against a
 * `soft` vehicle (sheet metal) such a round gets the mild-steel penetration the small-arms model derives for it at its current
 * speed ([TaczSmallArmsBridge.steelPenetration]): rifles go through a car, a pistol may not.
 * Not penetrated or ricochet cancels the hurt; penetrated lets TACZ's damage through and plays out the damage inside
 * ([SbwVehicleArmorBridge.applyPenetration]).
 * Registered only when TACZ and SuperbWarfare are both loaded.
 */
object TaczVehicleArmorBridge {
    private val NO_PENETRATION = PenetratorSpec(PenetratorKind.KE, 0.0)

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onGunHurt)
    }

    private fun onGunHurt(event: EntityHurtByGunEvent.Pre) {
        if (event.logicalSide != LogicalSide.SERVER) return
        var target = event.hurtEntity ?: return
        if (target is PartEntity<*>) target = target.parent
        val vehicle = target as? VehicleEntity ?: return
        val bullet = event.bullet as? EntityKineticBullet ?: return
        val soft = VehicleArmorRegistry[BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type)]?.soft == true
        val spec = roundSpec(bullet) ?: ShellRegistry.of(bullet)?.armorPenetration
            ?: (if (soft) TaczSmallArmsBridge.steelPenetration(bullet) else null) ?: NO_PENETRATION
        val shell = ShellRegistry.of(bullet)
        val result = SbwVehicleArmorBridge.resolve(
            vehicle, spec, bullet.position(), bullet.deltaMovement, event.attacker,
            shell?.caliberMm?.toDouble() ?: 0.0, shell?.explosive ?: false,
        ) ?: return
        if (result.solution.outcome != HitOutcome.PENETRATED) {
            event.isCanceled = true
            // a grenade or rocket that the plate stopped still goes off on it, and the riders feel it
            SbwVehicleArmorBridge.applyBlast(vehicle, result, event.attacker, bullet)
        } else SbwVehicleArmorBridge.applyPenetration(vehicle, result, event.attacker, bullet)
        SbwVehicleArmorBridge.report(event.attacker, vehicle, result)
    }

    private fun roundSpec(bullet: EntityKineticBullet): PenetratorSpec? {
        val id = bullet.roundId ?: return null
        val item = BuiltInRegistries.ITEM.get(id) as? TaczCartridgeItem ?: return null
        return item.round.armorPenetration
    }
}
