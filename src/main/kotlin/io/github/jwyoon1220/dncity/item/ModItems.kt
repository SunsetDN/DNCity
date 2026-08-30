package io.github.jwyoon1220.dncity.item

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.block.ModBlocks
import io.github.jwyoon1220.dncity.radio.RadioBandGroup
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModItems {
    val REGISTRY = DeferredRegister.createItems(Dncity.ID)

    /** Slot count shared by every radio variant -- band/mode restriction (not channel capacity)
     * is what actually distinguishes them; see [RadioBandGroup]. */
    private const val TIER = 3

    val RADIO_MW by REGISTRY.register("radio_mw") { -> RadioItem(RadioBandGroup.MW, TIER, properties = Item.Properties().stacksTo(1)) }
    val RADIO_HF by REGISTRY.register("radio_hf") { -> RadioItem(RadioBandGroup.HF, TIER, properties = Item.Properties().stacksTo(1)) }
    val RADIO_VHF by REGISTRY.register("radio_vhf") { -> RadioItem(RadioBandGroup.VHF_UHF, TIER, properties = Item.Properties().stacksTo(1)) }

    /** Receive-only -- see [RadioBandGroup.SCANNER] and [RadioItem.canTransmit]. */
    val RADIO_RECEIVER by REGISTRY.register("radio_receiver") { ->
        RadioItem(RadioBandGroup.SCANNER, TIER, canTransmit = false, properties = Item.Properties().stacksTo(1))
    }

    /** The item form of [io.github.jwyoon1220.dncity.block.RadioStationBlock] -- a station is a
     * placed block (see that class's doc comment), not a handheld item like the other four. */
    val RADIO_STATION by REGISTRY.register("radio_station") { -> BlockItem(ModBlocks.RADIO_STATION, Item.Properties()) }

    val ALL_RADIOS get() = listOf(RADIO_MW, RADIO_HF, RADIO_VHF, RADIO_RECEIVER, RADIO_STATION)
}
