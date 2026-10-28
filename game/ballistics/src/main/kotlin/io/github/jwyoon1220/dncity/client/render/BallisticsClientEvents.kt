// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.client.render

import io.github.jwyoon1220.dncity.ballistics.DncityBallistics
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup of [DncityBallistics] (see [io.github.jwyoon1220.dncity.Dncity] for why it is a class of its own). */
@Suppress("removal")
@EventBusSubscriber(modid = DncityBallistics.ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object BallisticsClientEvents {
    /** War Thunder-style hit camera after hitting an armored vehicle, see [VehicleHitCamOverlay]. */
    @SubscribeEvent
    fun onRegisterGuiLayers(event: RegisterGuiLayersEvent) {
        event.registerAboveAll(VehicleHitCamOverlay.ID, VehicleHitCamOverlay)
        // Red ballistic armor row above vanilla's armor row
        event.registerAbove(VanillaGuiLayers.ARMOR_LEVEL, BallisticArmorBar.ID, BallisticArmorBar)
    }

    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        // Body armor wear tooltip (see compat.TaczSmallArmsBridge)
        NeoForge.EVENT_BUS.addListener(BallisticArmorBar::onTooltip)
    }
}
