package io.github.jwyoon1220.dncity.client

import io.github.jwyoon1220.dncity.item.RadioItem
import io.github.jwyoon1220.dncity.network.RadioSetActivePayload
import io.github.jwyoon1220.dncity.network.RadioSetPoweredPayload
import io.github.jwyoon1220.dncity.network.RadioTunePayload
import io.github.jwyoon1220.dncity.radio.RadioBand
import io.github.jwyoon1220.dncity.radio.RadioData
import io.github.jwyoon1220.dncity.radio.RadioMode
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The radio's numeric-dial tuning UI. Client-only: reads the item's current [RadioData] (synced
 * to the client as part of the ItemStack) for display, and every action sends one of the
 * radio_* payloads (see [io.github.jwyoon1220.dncity.network]) to the server, which is the only
 * authority that actually mutates the stack.
 */
class RadioScreen(private val radio: RadioItem, stack: ItemStack, @Suppress("unused") private val hand: InteractionHand) :
    Screen(Component.literal("Radio")) {

    private var data: RadioData = radio.dataOf(stack)

    private class SlotRow(val index: Int, val freqBox: EditBox, var mode: RadioMode, val modeButton: Button, val activeButton: Button?)

    private val rows = ObjectArrayList<SlotRow>()
    private lateinit var powerButton: Button

    private val panelLeft get() = (width - 220) / 2
    private val rowStartY = 40
    private val rowHeight = 22

    override fun init() {
        rows.clear()
        val left = panelLeft

        powerButton = addRenderableWidget(
            Button.builder(powerLabel()) { togglePower() }
                .bounds(left, 14, 220, 20)
                .build(),
        )

        for (i in 0 until radio.tier) {
            val slot = data.slots.getOrNull(i)
            val y = rowStartY + i * rowHeight

            // Seek buttons: step the tuned frequency within its current band by a band-appropriate
            // channel spacing (see seekStepKhz), same idea as a real tuner's seek/scan control --
            // clamped at the band's edge rather than wrapping into a different band, since jumping
            // families (e.g. MW -> VHF) needs a deliberate retype, not an accidental seek press.
            addRenderableWidget(
                Button.builder(Component.literal("<")) { seekRow(i, -1) }.bounds(left + 16, y, 14, 18).build(),
            )

            val freqBox = EditBox(font, left + 32, y, 54, 18, Component.literal("freq_$i"))
            freqBox.value = if (slot != null && slot.enabled) trimFrequency(slot.frequencyKhz) else ""
            addRenderableWidget(freqBox)

            addRenderableWidget(
                Button.builder(Component.literal(">")) { seekRow(i, 1) }.bounds(left + 88, y, 14, 18).build(),
            )

            // Only cycle through modes this physical radio's band group actually supports (see
            // RadioBandGroup) -- tuning to any other mode would just be rejected server-side.
            val supportedModes = radio.bandGroup.modes.toList()
            var mode = slot?.mode?.takeIf { it in radio.bandGroup.modes } ?: supportedModes.first()
            var modeButton: Button = addRenderableWidget(
                Button.builder(Component.literal(mode.name)) {
                    val current = rows.first { it.index == i }
                    val nextIndex = (supportedModes.indexOf(current.mode) + 1) % supportedModes.size
                    current.mode = supportedModes[nextIndex]
                    current.modeButton.message = Component.literal(current.mode.name)
                    sendTune(current)
                }.bounds(left + 104, y, 48, 18).build(),
            )

            // A receive-only radio (RadioItem.canTransmit == false, see RadioBandGroup.SCANNER)
            // never has anything to make "active" -- there's nothing to transmit with -- so it
            // gets no TX button at all rather than one that would just be rejected server-side.
            val activeButton = if (radio.canTransmit) {
                addRenderableWidget(
                    Button.builder(activeLabel(i)) { sendActive(i) }
                        .bounds(left + 154, y, 30, 18)
                        .build(),
                )
            } else {
                null
            }

            val row = SlotRow(i, freqBox, mode, modeButton, activeButton)
            rows.add(row)

            addRenderableWidget(
                Button.builder(Component.literal("Set")) { sendTune(row) }
                    .bounds(left + 186, y, 34, 18)
                    .build(),
            )
        }
    }

    /** Band-appropriate seek step: a coarser jump than [RadioBand.tuningToleranceKhz] alone so a
     * handful of presses meaningfully moves across the dial instead of nudging by less than a
     * channel's worth. */
    private fun seekStepKhz(band: RadioBand): Double = band.tuningToleranceKhz * SEEK_STEP_TOLERANCE_MULTIPLIER

    private fun seekRow(index: Int, direction: Int) {
        val row = rows.first { it.index == index }
        val current = row.freqBox.value.toDoubleOrNull() ?: return
        val band = RadioBand.fromFrequencyKhz(current) ?: return
        val next = (current + direction * seekStepKhz(band)).coerceIn(band.minKhz, band.maxKhz)
        row.freqBox.value = trimFrequency(next)
        sendTune(row)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 2, 0xFFFFFF)
        val left = panelLeft
        for (row in rows) {
            val y = rowStartY + row.index * rowHeight
            graphics.drawString(font, "${row.index + 1}", left, y + 5, 0xFFFFFF)
            val band = row.freqBox.value.toDoubleOrNull()?.let { RadioBand.fromFrequencyKhz(it) }
            graphics.drawString(font, band?.name ?: "?", left + 224, y + 5, 0xA0A0A0)
        }
    }

    override fun isPauseScreen(): Boolean = false

    private fun trimFrequency(khz: Double): String = if (khz == khz.toLong().toDouble()) khz.toLong().toString() else khz.toString()

    private fun powerLabel(): Component = Component.literal(if (data.powered) "Power: ON" else "Power: OFF")

    private fun activeLabel(index: Int): Component = Component.literal(if (data.activeSlot == index) "TX*" else "TX")

    private fun togglePower() {
        data = data.copy(powered = !data.powered)
        PacketDistributor.sendToServer(RadioSetPoweredPayload(data.powered))
        powerButton.message = powerLabel()
    }

    private fun sendActive(index: Int) {
        data = data.copy(activeSlot = index)
        PacketDistributor.sendToServer(RadioSetActivePayload(index))
        rows.forEach { it.activeButton?.message = activeLabel(it.index) }
    }

    private fun sendTune(row: SlotRow) {
        val frequency = row.freqBox.value.toDoubleOrNull() ?: return
        PacketDistributor.sendToServer(RadioTunePayload(row.index, frequency, row.mode.name))
    }

    companion object {
        private const val SEEK_STEP_TOLERANCE_MULTIPLIER = 4.0
    }
}
