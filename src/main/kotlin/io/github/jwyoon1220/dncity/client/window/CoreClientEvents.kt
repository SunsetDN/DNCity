// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.client.window

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.client.network.DhNetworkTuning
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModList
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge

/** Client-only setup of DNCity core (see [Dncity]); each feature mod has its own. */
@Suppress("removal")
@EventBusSubscriber(modid = Dncity.ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object CoreClientEvents {
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        // Native window overlay (see WindowOverlayManager) -- registered here, not in Dncity's common init, since
        // WindowCommand's body touches AWT/native APIs that must never run on a dedicated server.
        NeoForge.EVENT_BUS.addListener(WindowCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onWindowOverlayTick)
        NeoForge.EVENT_BUS.addListener(::onWindowOverlayLogout)

        // DH's LOD stream follows the link instead of filling the game's TCP connection (see DhNetworkTuning)
        if (ModList.get().isLoaded("distanthorizons")) DhNetworkTuning.apply()
    }

    private fun onWindowOverlayTick(event: ClientTickEvent.Post) = WindowOverlayManager.tick()

    private fun onWindowOverlayLogout(event: ClientPlayerNetworkEvent.LoggingOut) = WindowOverlayManager.destroyAll()
}
