// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity

import net.minecraft.world.entity.Entity

/**
 * Where other mods' vehicles plug into the far-player proxies. SuperbWarfare numbers seats itself (a passenger's seat is
 * not its position in `getPassengers()`, and its renderer hides crew by seat), so `compat/SbwFarEntityCompat` replaces
 * both hooks while SuperbWarfare is loaded.
 */
object FarEntityHooks {
    /** Server side: the seat [passenger] holds in [vehicle], -1 if unknown. */
    @Volatile
    var seatIndex: (vehicle: Entity, passenger: Entity) -> Int = { vehicle, passenger -> vehicle.passengers.indexOf(passenger) }

    /**
     * Client side, right before a proxy player is mounted on a proxy vehicle: make the vehicle put the passenger in [seat]
     * (vanilla vehicles need nothing, they seat passengers in mounting order).
     */
    @Volatile
    var prepareProxyMount: (vehicle: Entity, seat: Int) -> Unit = { _, _ -> }
}
