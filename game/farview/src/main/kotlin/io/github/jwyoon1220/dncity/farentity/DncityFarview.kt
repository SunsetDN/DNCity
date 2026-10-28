// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.farentity

import io.github.jwyoon1220.dncity.farentity.compat.SbwFarEntityCompat
import io.github.jwyoon1220.dncity.farentity.network.FarEntityNetworking
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * `dncity_farview`: players out in Distant Horizons' LOD terrain drawn as real models, with server-side line of sight
 * ([FarEntityServer], [FarEntityVisibility]), and people/vehicles rendered out to the chunk render distance. Client-only
 * setup is in [io.github.jwyoon1220.dncity.client.farentity.FarviewClientEvents].
 */
@Mod(DncityFarview.ID)
object DncityFarview {
    const val ID = "dncity_farview"

    init {
        MOD_BUS.addListener(FarEntityNetworking::onRegisterPayloadHandlers)
        NeoForge.EVENT_BUS.addListener(FarEntityServer::onServerTick)
        NeoForge.EVENT_BUS.addListener(FarEntityServer::onLoggedOut)
        NeoForge.EVENT_BUS.addListener(FarEntityServer::onChangedDimension)
        NeoForge.EVENT_BUS.addListener(FarEntityServer::onServerStopping)
        // far-player proxies sit in the real seat (hidden tank crew stays hidden)
        if (ModList.get().isLoaded("superbwarfare")) SbwFarEntityCompat.register()
    }
}
