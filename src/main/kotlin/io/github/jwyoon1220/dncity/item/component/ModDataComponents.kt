package io.github.jwyoon1220.dncity.item.component

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.radio.RadioData
import net.minecraft.core.registries.Registries
import net.neoforged.neoforge.registries.DeferredRegister

// THIS LINE IS REQUIRED FOR USING PROPERTY DELEGATES
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModDataComponents {
    val REGISTRY: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Dncity.ID)

    val RADIO_DATA by REGISTRY.registerComponentType<RadioData>("radio_data") { builder ->
        builder.persistent(RadioData.CODEC)
    }
}
