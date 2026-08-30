package io.github.jwyoon1220.dncity

import io.github.jwyoon1220.dncity.block.ModBlockEntities
import io.github.jwyoon1220.dncity.block.ModBlocks
import io.github.jwyoon1220.dncity.command.PhoneNumberCommand
import io.github.jwyoon1220.dncity.command.RadioCommand
import io.github.jwyoon1220.dncity.item.ModItems
import io.github.jwyoon1220.dncity.item.component.ModDataComponents
import io.github.jwyoon1220.dncity.command.MusicCommand
import io.github.jwyoon1220.dncity.music.MusicServerEvents
import io.github.jwyoon1220.dncity.network.MusicNetworking
import io.github.jwyoon1220.dncity.network.PhoneNetworking
import io.github.jwyoon1220.dncity.network.RadioNetworking
import io.github.jwyoon1220.dncity.network.RadioStationNetworking
import io.github.jwyoon1220.dncity.network.VoiceNetworking
import io.github.jwyoon1220.dncity.phone.PhoneServerEvents
import io.github.jwyoon1220.dncity.radio.RadioStationServerEvents
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * Main mod class.
 *
 * Only dist-agnostic (common) registrations belong here -- KotlinForForge's automatic
 * `@EventBusSubscriber` injection (`AutoKotlinEventBusSubscriber`) reflects over every declared
 * method of this class via kotlin-reflect during mod construction, which forces the JVM to
 * resolve each method's parameter/return types even for methods that are never called. A
 * client-only method living here (e.g. one referencing `net.minecraft.client.KeyMapping`) used to
 * crash the dedicated server under RuntimeDistCleaner for exactly this reason, even when the
 * event it listened for would never have fired on the server. All client-only setup lives in
 * [io.github.jwyoon1220.dncity.client.ClientModEvents] instead, whose own
 * `@EventBusSubscriber(value = [Dist.CLIENT])` annotation is dist-filtered by FML's ASM-based
 * class scan *before* the class is ever loaded, so a dedicated server never reflects over it at
 * all.
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
        ModBlockEntities.REGISTRY.register(MOD_BUS)
        ModItems.REGISTRY.register(MOD_BUS)
        ModDataComponents.REGISTRY.register(MOD_BUS)

        NeoForge.EVENT_BUS.addListener(RadioCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(MusicCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(PhoneNumberCommand::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(MusicServerEvents::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(PhoneServerEvents::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(PhoneServerEvents::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(RadioStationServerEvents::onPlayerLoggedOut)
        MOD_BUS.addListener(RadioNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(RadioStationNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(VoiceNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(MusicNetworking::onRegisterPayloadHandlers)
        MOD_BUS.addListener(PhoneNetworking::onRegisterPayloadHandlers)
    }

    /**
     * Fired on the mod bus -- only ever fires on a dedicated server, so (unlike client-only
     * setup) this is safe to keep here: its own event type carries no client-only references.
     */
    @SubscribeEvent
    fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
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
