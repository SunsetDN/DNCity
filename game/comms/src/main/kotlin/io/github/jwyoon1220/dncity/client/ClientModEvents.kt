// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.client

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.client.phone.PhoneCallManager
import io.github.jwyoon1220.dncity.client.phone.PhoneCallReceiver
import io.github.jwyoon1220.dncity.client.phone.PhoneController
import io.github.jwyoon1220.dncity.client.phone.nanovg.PhoneNanoVgSurface
import io.github.jwyoon1220.dncity.command.PhoneCommand
import io.github.jwyoon1220.dncity.item.RadioItem
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpClient
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol
import io.github.jwyoon1220.dncity.radio.DncityComms
import io.github.jwyoon1220.dncity.radio.network.RadioKcpMessages
import io.github.jwyoon1220.dncity.voice.ClientRadioReceiver
import io.github.jwyoon1220.dncity.voice.ClientVoiceReceiver
import io.github.jwyoon1220.dncity.voice.RadioStationClientState
import io.github.jwyoon1220.dncity.voice.VoiceClientLoop
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModList
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import org.apache.logging.log4j.Level

/**
 * All client-only setup for [DncityComms], behind an `@EventBusSubscriber(value = [Dist.CLIENT])` class of its own --
 * see [Dncity]'s doc comment for why that split matters (FML's dist-filtered class scan means a dedicated server never
 * loads this class at all, unlike relying on an event simply never firing there).
 */
@Suppress("removal")
@EventBusSubscriber(modid = DncityComms.ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ClientModEvents {
    @SubscribeEvent
    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(ModKeyMappings.RADIO_PTT)
        event.register(ModKeyMappings.PHONE_TOGGLE)
    }

    /**
     * This is used for initializing client specific
     * things such as renderers and keymaps
     * Fired on the mod specific event bus.
     */
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        Dncity.LOGGER.log(Level.INFO, "Initializing DNCity comms client...")
        RadioItem.screenOpener = { radio, stack, hand ->
            Minecraft.getInstance().setScreen(RadioScreen(radio, stack, hand))
        }

        // Relayed audio from the server (raw UDP lane, already freshness/staleness-checked by the transport)
        VoiceKcpClient.registerHandler(VoiceKcpProtocol.TYPE_VOICE_AUDIO_RELAY) { msg ->
            val relay = VoiceKcpProtocol.decodeVoiceRelay(msg)
            ClientVoiceReceiver.handleRelayedFrame(relay.senderEntityId, relay.audio)
        }
        VoiceKcpClient.registerHandler(VoiceKcpProtocol.TYPE_RADIO_AUDIO_RELAY) { msg ->
            val relay = RadioKcpMessages.decodeRadioRelay(msg)
            ClientRadioReceiver.handleRelayedFrame(
                relay.senderEntityId, relay.audio, relay.frequencyKhz, relay.modeName,
                relay.senderPosition, relay.rangeInfo.effectiveMaxRangeBlocks, relay.rangeInfo.obstructed, relay.rangeInfo.stormNoise,
            )
        }
        VoiceKcpClient.registerHandler(VoiceKcpProtocol.TYPE_PHONE_CALL_AUDIO_RELAY) { msg ->
            PhoneCallReceiver.handleFrame(VoiceKcpProtocol.decodeAudio(msg).audio)
        }

        // Close-range voice: mic capture/playback only runs while actually in a world, not
        // sitting at the main menu.
        NeoForge.EVENT_BUS.addListener(::onVoiceLogin)
        NeoForge.EVENT_BUS.addListener(::onVoiceLogout)
        NeoForge.EVENT_BUS.addListener(::onVoiceClientTick)

        // Phone screen (see client.phone.PhoneController) -- registered here, not in DncityComms' common init, since its
        // body touches client-only APIs that must never run on a dedicated server.
        NeoForge.EVENT_BUS.addListener(PhoneCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPhoneLogout)
        NeoForge.EVENT_BUS.addListener(::onPhoneToggleKey)
        NeoForge.EVENT_BUS.addListener(::onPhoneNanoVgTick)

        // Makes VoiceSettingsScreen reachable from this mod's "Config" button in the Mods list.
        ModList.get().getModContainerById(DncityComms.ID).ifPresent { container ->
            container.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory { _, screen ->
                VoiceSettingsScreen(screen)
            })
        }
    }

    private fun onVoiceLogin(event: ClientPlayerNetworkEvent.LoggingIn) = VoiceClientLoop.start()

    private fun onVoiceLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        VoiceClientLoop.stop()
        PhoneCallManager.reset()
        RadioStationClientState.clear()
    }

    private fun onVoiceClientTick(event: ClientTickEvent.Post) {
        VoiceClientLoop.tick()
        RadioStationClientState.tick()
    }

    private fun onPhoneLogout(event: ClientPlayerNetworkEvent.LoggingOut) = PhoneController.close()

    // ClientTickEvent.Post already runs on the render thread, so PhoneController.toggle() can be
    // called directly here -- unlike PhoneCommand's handlers, which hop via
    // Minecraft.getInstance().execute {} since Brigadier commands run on the integrated server's
    // own thread even in singleplayer.
    private fun onPhoneToggleKey(event: ClientTickEvent.Post) {
        while (ModKeyMappings.PHONE_TOGGLE.consumeClick()) {
            PhoneController.toggle()
        }
    }

    // Render-thread only (ClientTickEvent.Post runs there, see PhoneNanoVgSurface's doc comment
    // for why that matters -- it's the only thread with Minecraft's GL context current).
    private fun onPhoneNanoVgTick(event: ClientTickEvent.Post) {
        if (PhoneController.isOpen) PhoneNanoVgSurface.renderFrame()
    }
}
