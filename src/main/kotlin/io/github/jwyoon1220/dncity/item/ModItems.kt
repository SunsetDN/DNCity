package io.github.jwyoon1220.dncity.item

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.radio.RadioBandGroup
import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModItems {
    val REGISTRY = DeferredRegister.createItems(Dncity.ID)

    /** Slot count shared by both radio variants -- band restriction (not channel capacity) is
     * what actually distinguishes them; see [RadioBandGroup]. */
    private const val TIER = 3

    val RADIO_HF by REGISTRY.register("radio_hf") { -> RadioItem(RadioBandGroup.HF_MF, TIER, Item.Properties().stacksTo(1)) }
    val RADIO_VHF by REGISTRY.register("radio_vhf") { -> RadioItem(RadioBandGroup.VHF_UHF, TIER, Item.Properties().stacksTo(1)) }

    val ALL_RADIOS get() = listOf(RADIO_HF, RADIO_VHF)
}
