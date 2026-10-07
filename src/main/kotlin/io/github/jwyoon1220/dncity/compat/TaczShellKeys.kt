// AGENT-DONE(claude): shell-ballistics
package io.github.jwyoon1220.dncity.compat

import com.tacz.guns.entity.EntityKineticBullet
import io.github.jwyoon1220.dncity.ballistics.ShellRegistry
import net.minecraft.resources.ResourceLocation

/**
 * All TACZ bullets are one entity type and are not saved, the ammo id is a field. Exposing it as the shell key is what
 * lets the shell JSONs describe each TACZ ammo (`"entity": "tacz:bullet", "key": "tacz:556x45"`).
 */
object TaczShellKeys {
    fun register() {
        ShellRegistry.registerKeyResolver(ResourceLocation.fromNamespaceAndPath("tacz", "bullet")) { entity ->
            (entity as? EntityKineticBullet)?.ammoId?.toString()
        }
    }
}
