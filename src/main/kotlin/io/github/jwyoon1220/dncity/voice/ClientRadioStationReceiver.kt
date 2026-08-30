package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.client.RadioStationScreen
import io.github.jwyoon1220.dncity.network.RadioStationMembershipPayload
import io.github.jwyoon1220.dncity.network.RadioStationOpenPayload
import io.github.jwyoon1220.dncity.radio.RadioMode
import net.minecraft.client.Minecraft

/**
 * Client-side handlers for [io.github.jwyoon1220.dncity.network.RadioStationNetworking]'s two
 * server -> client payloads. Same pattern as [ClientRadioReceiver]/[ClientVoiceReceiver] -- a
 * plain (non-`@EventBusSubscriber`) object referenced only from inside
 * `RadioStationNetworking.onRegisterPayloadHandlers`'s `playToClient` closures, so it's never
 * eagerly reflected over or loaded on a dedicated server (see [io.github.jwyoon1220.dncity.Dncity]'s
 * doc comment for why that distinction matters), safe to reference client-only classes
 * ([net.minecraft.client.Minecraft], [RadioStationScreen]) directly.
 */
object ClientRadioStationReceiver {
    private fun modeOf(name: String): RadioMode = RadioMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: RadioMode.FM

    fun handleOpen(payload: RadioStationOpenPayload) {
        Minecraft.getInstance().setScreen(
            RadioStationScreen(
                payload.blockPos, payload.stationName, payload.frequencyKhz,
                modeOf(payload.mode), payload.broadcasterNames, payload.joinedByMe,
            ),
        )
    }

    fun handleMembership(payload: RadioStationMembershipPayload) {
        if (payload.joined) {
            RadioStationClientState.update(payload.blockPos, payload.stationName, payload.frequencyKhz, modeOf(payload.mode))
        } else {
            RadioStationClientState.clear()
        }
    }
}
