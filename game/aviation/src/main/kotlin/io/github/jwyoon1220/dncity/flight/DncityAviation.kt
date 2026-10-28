// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.flight

import io.github.jwyoon1220.dncity.flight.compat.SbwFlightModelBridge
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod

/**
 * `dncity_aviation`: War Thunder-style flight for SuperbWarfare's aircraft that have data in `dncity/flight_models`
 * (see [io.github.jwyoon1220.dncity.flight.aircraft.FlightModels]). Shares its SI vector and atmosphere types with
 * `dncity_ballistics`, which it therefore requires.
 */
@Mod(DncityAviation.ID)
object DncityAviation {
    const val ID = "dncity_aviation"

    init {
        // Loaded lazily: the bridge references SuperbWarfare classes
        if (ModList.get().isLoaded("superbwarfare")) SbwFlightModelBridge.register()
    }
}
