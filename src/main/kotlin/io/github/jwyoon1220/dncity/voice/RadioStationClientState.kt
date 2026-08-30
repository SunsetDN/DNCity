package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.radio.RadioMode
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/** Client-side mirror of [io.github.jwyoon1220.dncity.radio.RadioStationRegistry]'s membership
 * for the local player only -- authoritative state still lives server-side; this is purely what
 * [RadioTransmitter] reads to decide whether PTT audio should route to a station instead of a
 * held [io.github.jwyoon1220.dncity.item.RadioItem], and what drives the action-bar reminder
 * while joined. */
object RadioStationClientState {
    data class Joined(val pos: BlockPos, val name: String, val frequencyKhz: Double, val mode: RadioMode)

    var joined: Joined? = null
        private set

    fun update(pos: BlockPos, name: String, frequencyKhz: Double, mode: RadioMode) {
        joined = Joined(pos, name, frequencyKhz, mode)
        ticksUntilReminder = 0
    }

    fun clear() {
        joined = null
    }

    private var ticksUntilReminder = 0

    /** Re-sends the action-bar line every [REMINDER_INTERVAL_TICKS] while joined -- a plain
     * action-bar message fades out on its own after a few seconds, so this is what keeps "라디오
     * 스테이션 (station name, 132.3MHz)" effectively persistent on screen for as long as
     * membership lasts, at the cost of a small periodic re-fade-in flicker rather than a fully
     * static overlay (simpler than a custom `IGuiOverlay` for what's meant to be a lightweight
     * reminder, not a permanent HUD element). */
    fun tick() {
        val station = joined ?: return
        if (ticksUntilReminder-- > 0) return
        ticksUntilReminder = REMINDER_INTERVAL_TICKS
        val mhz = "%.1f".format(station.frequencyKhz / 1000.0)
        val text = Component.translatable("radio.dncity.station_action_bar", station.name, mhz)
        Minecraft.getInstance().player?.displayClientMessage(text, true)
    }

    private const val REMINDER_INTERVAL_TICKS = 40 // ~2s -- comfortably inside the vanilla action bar's fade-out window
}
