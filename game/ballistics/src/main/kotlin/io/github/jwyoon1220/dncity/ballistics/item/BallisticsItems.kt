// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.ballistics.item

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

/** `dncity_ballistics`' items (registered under the `dncity` namespace, same ids as before the split). */
object BallisticsItems {
    val REGISTRY: DeferredRegister.Items = DeferredRegister.createItems(Dncity.ID)

    /** One item for every ammo in the data packs; the kind is the [ModDataComponents.AMMO_ID] component, see [AmmoItem]. */
    val AMMO by REGISTRY.register("ammo") { -> AmmoItem(Item.Properties()) }
}
