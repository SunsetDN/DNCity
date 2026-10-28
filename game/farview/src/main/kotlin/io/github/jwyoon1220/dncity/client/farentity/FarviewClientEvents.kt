// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.client.farentity

import io.github.jwyoon1220.dncity.farentity.DncityFarview
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup of [DncityFarview] (see [io.github.jwyoon1220.dncity.Dncity] for why it is a class of its own). */
@Suppress("removal")
@EventBusSubscriber(modid = DncityFarview.ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object FarviewClientEvents {
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        NeoForge.EVENT_BUS.addListener(FarEntityClient::onClientTick)
        NeoForge.EVENT_BUS.addListener(FarEntityClient::onLoggingOut)
    }
}
