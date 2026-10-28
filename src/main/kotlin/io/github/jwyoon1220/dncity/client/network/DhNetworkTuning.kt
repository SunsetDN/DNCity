// AGENT-DONE(claude): network-lanes
package io.github.jwyoon1220.dncity.client.network

import com.seibel.distanthorizons.core.config.Config
import io.github.jwyoon1220.dncity.Dncity

/**
 * Turns on Distant Horizons' adaptive LOD transfer speed for this client. DH streams LOD data over the
 * same Minecraft TCP connection as the game, capped at a fixed 500 KB/s per player by default
 * (`Config.Server.playerBandwidthLimit`); on a slower link that fills the connection's queue and game
 * packets wait behind it. With `enableAdaptiveTransferSpeed` (default off upstream) the client measures
 * the LOD bytes it actually receives every second and asks the server for that rate (+50 KB/s while it
 * keeps up, the measured rate minus 25 KB/s when it does not -- `ClientCongestionControl`), so the
 * stream follows the link instead of filling it.
 *
 * Set as DH's API override (`ConfigEntry.setApiValue`): the player's own config file is not changed, and
 * `-Ddncity.dhAdaptiveTransfer=false` keeps DH's own setting. Loaded only when DH is (`ClientModEvents`);
 * `com.seibel.distanthorizons.core` is not relocated in DH's jar (only its loader and library packages are).
 */
internal object DhNetworkTuning {
    fun apply() {
        if (System.getProperty("dncity.dhAdaptiveTransfer", "true") == "false") return
        try {
            Config.Server.enableAdaptiveTransferSpeed.setApiValue(true)
        } catch (e: Throwable) {
            // a DH build whose internals moved: keep its own behaviour
            Dncity.LOGGER.warn("DNCity: could not enable Distant Horizons' adaptive transfer speed", e)
        }
    }
}
