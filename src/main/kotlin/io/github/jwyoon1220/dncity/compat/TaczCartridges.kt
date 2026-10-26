// AGENT-DONE(claude): cartridge-items
package io.github.jwyoon1220.dncity.compat

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.item.ModItems
import io.github.jwyoon1220.dncity.item.cartridge.CartridgeCatalog
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Registers one [TaczCartridgeItem] per round in [CartridgeCatalog] plus the "Ammunition" creative tab that lists them.
 * Called from [Dncity] only when TACZ is loaded.
 */
object TaczCartridges {
    private val TABS: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Dncity.ID)

    private val items = mutableListOf<DeferredItem<TaczCartridgeItem>>()

    fun register(modBus: IEventBus) {
        for (round in CartridgeCatalog.rounds) {
            items += ModItems.REGISTRY.register(round.itemPath) { ->
                TaczCartridgeItem(round, Item.Properties().stacksTo(round.caliber.stackSize))
            }
        }
        TABS.register("ammo") { ->
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.dncity.ammo"))
                .icon { items.firstOrNull { it.get().round.itemPath == "cartridge_556x45_m855" }?.let { ItemStack(it.get()) } ?: ItemStack.EMPTY }
                .displayItems { _, output -> items.forEach { output.accept(it.get()) } }
                .build()
        }
        TABS.register(modBus)
    }
}
