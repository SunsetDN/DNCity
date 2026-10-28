// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity.compat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import io.github.jwyoon1220.dncity.farentity.FarEntityHooks
import java.util.function.Function

/**
 * SuperbWarfare seats a passenger in the first free slot when it mounts (`VehicleEntity.addPassenger`) and hides crew by
 * seat (`ClientEventHandler.setPlayerInvisible` -> `VehicleEntity.hidePassenger`), so a far proxy has to sit in the seat
 * the real player holds, or a tank's hidden driver would be drawn on top of the hull. The server reads the real seat,
 * the client pins the proxy vehicle's next mount to it through SuperbWarfare's own `entityIndexOverride`.
 */
object SbwFarEntityCompat {
    fun register() {
        val defaultSeat = FarEntityHooks.seatIndex
        FarEntityHooks.seatIndex = { vehicle, passenger ->
            if (vehicle is VehicleEntity) vehicle.getSeatIndex(passenger) else defaultSeat(vehicle, passenger)
        }
        val defaultMount = FarEntityHooks.prepareProxyMount
        FarEntityHooks.prepareProxyMount = { vehicle, seat ->
            if (vehicle is VehicleEntity && seat >= 0) vehicle.entityIndexOverride = Function { seat } else defaultMount(vehicle, seat)
        }
    }
}
