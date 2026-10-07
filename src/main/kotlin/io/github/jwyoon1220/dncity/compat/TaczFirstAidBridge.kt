// AGENT-DONE(claude): tacz-damage
package io.github.jwyoon1220.dncity.compat

import com.tacz.guns.api.event.common.EntityHurtByGunEvent
import ichttt.mods.firstaid.common.EventHandler
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge

/**
 * Tells First Aid where a TACZ bullet hit a player.
 *
 * TACZ moves its bullets with its own ray logic and never goes through `Projectile#onHit`, which is where First Aid
 * normally records the hit position, so without this every gunshot would land on a random limb. The headshot flag comes
 * from TACZ's own hitbox test and is turned into the player's eye position, which First Aid maps to the head. One shot
 * hurts twice (normal and armor-piercing part); First Aid keeps the recorded hit for the rest of the tick, so both parts
 * hit the same limb.
 *
 * Only registered when both mods are present (see [register]'s caller in `Dncity`), so this class is never loaded otherwise.
 */
object TaczFirstAidBridge {
    fun register() {
        // LOWEST: let other handlers change the target/damage first, only the final target matters here
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityHurtByGunEvent.Pre::class.java, ::onGunHurt)
    }

    private fun onGunHurt(event: EntityHurtByGunEvent.Pre) {
        val target = event.hurtEntity as? ServerPlayer ?: return
        val hitPosition = if (event.isHeadShot) target.eyePosition else event.bullet.position()
        EventHandler.recordProjectileHit(target, event.bullet, hitPosition)
    }
}
