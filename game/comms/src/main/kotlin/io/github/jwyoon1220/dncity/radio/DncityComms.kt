// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.radio

import io.github.jwyoon1220.dncity.block.ModBlockEntities
import io.github.jwyoon1220.dncity.block.ModBlocks
import io.github.jwyoon1220.dncity.command.PhoneNumberCommand
import io.github.jwyoon1220.dncity.command.RadioCommand
import io.github.jwyoon1220.dncity.item.ModItems
import io.github.jwyoon1220.dncity.item.RadioComponents
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer
import io.github.jwyoon1220.dncity.phone.PhoneCallSession
import io.github.jwyoon1220.dncity.phone.PhoneServerEvents
import io.github.jwyoon1220.dncity.radio.network.PhoneNetworking
import io.github.jwyoon1220.dncity.radio.network.RadioNetworking
import io.github.jwyoon1220.dncity.radio.network.RadioStationNetworking
import io.github.jwyoon1220.dncity.voice.RadioRelay
import io.github.jwyoon1220.dncity.voice.VoiceRelay
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * `dncity_comms`: radios and radio stations, close-range voice, phones. Audio rides DNCity core's UDP transport; this mod
 * registers what each audio message type means ([VoiceKcpServer.registerHandler] here, the client side in
 * [io.github.jwyoon1220.dncity.client.ClientModEvents]). Client-only setup is in that class, for the reason
 * [io.github.jwyoon1220.dncity.Dncity]'s doc comment gives.
 */
@Suppress("removal")
@Mod(DncityComms.ID)
@EventBusSubscriber(modid = DncityComms.ID, bus = EventBusSubscriber.Bus.MOD)
object DncityComms {
    const val ID = "dncity_comms"

    init {
        ModBlocks.REGISTRY.register(MOD_BUS)
        ModBlockEntities.REGISTRY.register(MOD_BUS)
        ModItems.REGISTRY.register(MOD_BUS)
        RadioComponents.REGISTRY.register(MOD_BUS)

        NeoForge.EVENT_BUS.addListener(RadioCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(PhoneNumberCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(PhoneServerEvents::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(PhoneServerEvents::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(RadioStationServerEvents::onPlayerLoggedOut)
        MOD_BUS.addListener(RadioNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(RadioStationNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(PhoneNetworking::onRegisterPayloadHandlers)

        // Uplink audio from clients (raw UDP lane, already freshness/staleness-checked by the transport)
        VoiceKcpServer.registerHandler(VoiceKcpProtocol.TYPE_VOICE_AUDIO) { player, msg ->
            VoiceRelay.relay(player, VoiceKcpProtocol.decodeAudio(msg).audio)
        }
        VoiceKcpServer.registerHandler(VoiceKcpProtocol.TYPE_RADIO_AUDIO) { player, msg ->
            RadioRelay.relay(player, VoiceKcpProtocol.decodeAudio(msg).audio)
        }
        VoiceKcpServer.registerHandler(VoiceKcpProtocol.TYPE_PHONE_CALL_AUDIO) { player, msg ->
            PhoneCallSession.relayAudio(player, VoiceKcpProtocol.decodeAudio(msg).audio)
        }
    }

    @SubscribeEvent
    fun onBuildCreativeModeTabContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            ModItems.ALL_RADIOS.forEach(event::accept)
        }
    }
}
