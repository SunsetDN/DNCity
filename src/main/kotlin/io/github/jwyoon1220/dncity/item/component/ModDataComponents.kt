package io.github.jwyoon1220.dncity.item.component

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.radio.RadioData
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModDataComponents {
    val REGISTRY: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Dncity.ID)

    val RADIO_DATA by REGISTRY.registerComponentType<RadioData>("radio_data") { builder ->
        builder.persistent(RadioData.CODEC)
    }

    /** Which ammo definition (data/<ns>/dncity/ammo) an ammo stack is; looked up on use, so a data reload can change the ammo. */
    val AMMO_ID by REGISTRY.registerComponentType<ResourceLocation>("ammo_id") { builder ->
        builder.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC)
    }

    /** Remaining ballistic protection of a body armor piece; synced so the HUD can draw the red armor bar. */
    val BALLISTIC_ARMOR by REGISTRY.registerComponentType<BallisticArmorState>("ballistic_armor") { builder ->
        builder.persistent(BallisticArmorState.CODEC).networkSynchronized(BallisticArmorState.STREAM_CODEC)
    }
}
