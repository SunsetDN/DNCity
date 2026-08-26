package io.github.jwyoon1220.dncity

import io.github.jwyoon1220.dncity.voice.VoiceClientLoop
import io.github.jwyoon1220.dncity.block.ModBlocks
import io.github.jwyoon1220.dncity.client.ModKeyMappings
import io.github.jwyoon1220.dncity.client.RadioScreen
import io.github.jwyoon1220.dncity.client.VoiceSettingsScreen
import io.github.jwyoon1220.dncity.command.RadioCommand
import io.github.jwyoon1220.dncity.item.ModItems
import io.github.jwyoon1220.dncity.item.RadioItem
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import io.github.jwyoon1220.dncity.command.MusicCommand
import io.github.jwyoon1220.dncity.music.AudioPlayer
import io.github.jwyoon1220.dncity.music.MidiPlayer
import io.github.jwyoon1220.dncity.music.MusicClientReceiver
import io.github.jwyoon1220.dncity.music.MusicServerEvents
import io.github.jwyoon1220.dncity.network.MusicNetworking
import io.github.jwyoon1220.dncity.network.RadioNetworking
import io.github.jwyoon1220.dncity.network.VoiceNetworking
import net.minecraft.client.Minecraft
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModList
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.runForDist

/**
 * Main mod class.
 *
 * An example for blocks is in the `blocks` package of this mod.
 */
@Suppress("removal")
@Mod(Dncity.ID)
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
object Dncity {
    const val ID = "dncity"

    // the logger for our mod
    val LOGGER: Logger = LogManager.getLogger(ID)

    init {

        // Register the KDeferredRegister to the mod-specific event bus
        ModBlocks.REGISTRY.register(MOD_BUS)
        ModItems.REGISTRY.register(MOD_BUS)
        ModDataComponents.REGISTRY.register(MOD_BUS)

        NeoForge.EVENT_BUS.addListener(RadioCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(MusicCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(MusicServerEvents::onPlayerLoggedIn)
        MOD_BUS.addListener(RadioNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(VoiceNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(MusicNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(ModKeyMappings::onRegisterKeyMappings)

        val obj = runForDist(clientTarget = {
            MOD_BUS.addListener(::onClientSetup)
            Minecraft.getInstance()
        }, serverTarget = {
            MOD_BUS.addListener(::onServerSetup)
            "test"
        })
    }

    /**
     * This is used for initializing client specific
     * things such as renderers and keymaps
     * Fired on the mod specific event bus.
     */
    private fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing client...")
        RadioItem.screenOpener = { radio, stack, hand ->
            Minecraft.getInstance().setScreen(RadioScreen(radio, stack, hand))
        }

        // Close-range voice: mic capture/playback only runs while actually in a world, not
        // sitting at the main menu.
        NeoForge.EVENT_BUS.addListener(::onVoiceLogin)
        NeoForge.EVENT_BUS.addListener(::onVoiceLogout)
        NeoForge.EVENT_BUS.addListener(::onVoiceClientTick)

        // Makes VoiceSettingsScreen reachable from this mod's "Config" button in the Mods list.
        ModList.get().getModContainerById(ID).ifPresent { container ->
            container.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory { _, screen ->
                VoiceSettingsScreen(screen)
            })
        }
    }

    private fun onVoiceLogin(event: ClientPlayerNetworkEvent.LoggingIn) = VoiceClientLoop.start()

    private fun onVoiceLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        VoiceClientLoop.stop()
        MidiPlayer.stop()
        AudioPlayer.shutdown()
        MusicClientReceiver.reset()
    }

    private fun onVoiceClientTick(event: ClientTickEvent.Post) {
        VoiceClientLoop.tick()
        AudioPlayer.tick()
    }

    /**
     * Fired on the global Forge bus.
     */
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "Server starting...")
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.log(Level.INFO, "Hello! This is working!")
    }

    @SubscribeEvent
    fun onBuildCreativeModeTabContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            ModItems.ALL_RADIOS.forEach(event::accept)
        }
    }
}
