// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.item

import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoDefinition
import io.github.jwyoon1220.dncity.ballistics.terminal.ammo.AmmoRegistry
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * The single registered item behind every ammo in `data/<ns>/dncity/ammo`. Item registration happens once at mod load; ammo
 * data is reloaded freely, so the item only stores the ammo's id ([ModDataComponents.AMMO_ID]) and the definition is looked up
 * whenever it is needed. A stack whose ammo is gone after a reload stays a harmless item ([definitionOf] returns null).
 *
 * Name, stack size and rarity are stamped onto the stack as ordinary data components when it is created ([stackOf]), because
 * those must work on a client that has not loaded the server's data.
 */
class AmmoItem(properties: Properties) : Item(properties) {

    fun definitionOf(stack: ItemStack): AmmoDefinition? =
        stack.get(ModDataComponents.AMMO_ID)?.let { AmmoRegistry[Ident(it.namespace, it.path)] }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val id = stack.get(ModDataComponents.AMMO_ID) ?: return
        val def = AmmoRegistry[Ident(id.namespace, id.path)]
        if (def == null) {
            tooltip += Component.literal("$id (not loaded)")
            return
        }
        tooltip += Component.literal("${def.projectile.type}  ${"%.1f".format(def.compat.gunCaliberM * 1000.0)} mm")
        tooltip += Component.literal("v0 ${def.projectile.muzzleVelocityMps.toInt()} m/s, ${"%.2f".format(def.projectile.massKg)} kg")
    }

    companion object {
        fun stackOf(def: AmmoDefinition, count: Int = 1): ItemStack {
            val stack = ItemStack(ModItems.AMMO, 1)
            stack.set(ModDataComponents.AMMO_ID, ResourceLocation.fromNamespaceAndPath(def.id.namespace, def.id.path))
            stack.set(DataComponents.MAX_STACK_SIZE, def.item.maxStackSize)
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(def.display.name))
            stack.count = count.coerceIn(1, def.item.maxStackSize)
            return stack
        }
    }
}
