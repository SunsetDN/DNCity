// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity

import io.github.jwyoon1220.dncity.network.VoiceNetworking
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServerEvents
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.NeoForge
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * DNCity core (`dncity`): what every DNCity feature mod builds on, and nothing game-specific.
 *
 * - the UDP transport ([io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer] / `VoiceKcpClient`): one session per
 *   player, a reliable KCP lane and a raw datagram lane; feature mods register message handlers per type byte
 * - the native window overlay and browser host (`client.window`), the FSR2 scaffold (`client.render.fsr`), Distant
 *   Horizons' network tuning (`client.network`)
 * - the FMOD banks TACZ plays its weapon sounds from (TACZ finds them under the `dncity` mod id)
 *
 * Features are separate mods in `game/` (see AGENTS.md, "Feature mods"): `dncity_comms` (radio, voice, phone),
 * `dncity_music`, `dncity_security` (PGP login), `dncity_ballistics`, `dncity_aviation`, `dncity_farview`. Their
 * registry entries, payloads and data keep the `dncity` namespace ([ID]), so worlds and data packs did not change.
 *
 * Only dist-agnostic (common) registrations belong here -- KotlinForForge's automatic `@EventBusSubscriber` injection
 * reflects over every declared method of this class during mod construction, which resolves each method's parameter
 * types even on a dedicated server. Client-only setup lives in
 * [io.github.jwyoon1220.dncity.client.window.CoreClientEvents], whose `@EventBusSubscriber(value = [Dist.CLIENT])` is
 * dist-filtered before the class is ever loaded.
 */
@Suppress("removal")
@Mod(Dncity.ID)
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
object Dncity {
    /** Mod id of the core, and the namespace every DNCity feature mod registers its content under. */
    const val ID = "dncity"

    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        NeoForge.EVENT_BUS.addListener(VoiceKcpServerEvents::onServerStarting)
        NeoForge.EVENT_BUS.addListener(VoiceKcpServerEvents::onServerStopping)
        NeoForge.EVENT_BUS.addListener(VoiceKcpServerEvents::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(VoiceKcpServerEvents::onPlayerLoggedOut)
        MOD_BUS.addListener(VoiceNetworking::onRegisterPayloadHandlers)
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.log(Level.INFO, "DNCity core loaded")
    }
}
