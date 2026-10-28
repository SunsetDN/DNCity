// AGENT-DONE(claude): radio-tuning-ui
package io.github.jwyoon1220.dncity.client

import io.github.jwyoon1220.dncity.item.RadioItem
import io.github.jwyoon1220.dncity.radio.network.RadioSetActivePayload
import io.github.jwyoon1220.dncity.radio.network.RadioSetPoweredPayload
import io.github.jwyoon1220.dncity.radio.network.RadioTunePayload
import io.github.jwyoon1220.dncity.radio.RadioBand
import io.github.jwyoon1220.dncity.radio.RadioBandGroup
import io.github.jwyoon1220.dncity.radio.RadioData
import io.github.jwyoon1220.dncity.radio.RadioMode
import io.github.jwyoon1220.dncity.radio.RadioSlot
import io.github.jwyoon1220.dncity.radio.RadioTuning
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * The radio's numeric-dial tuning UI. Client-only: reads the item's current [RadioData] (synced
 * to the client as part of the ItemStack) for display, and every action sends one of the
 * radio_* payloads (see [io.github.jwyoon1220.dncity.radio.network]) to the server, which is the only
 * authority that actually mutates the stack -- for the radio in [hand], so a radio opened from the
 * offhand is tuned too.
 *
 * Frequencies are typed in the unit the radio's band is usually read in ([unitMhz]: kHz for a
 * mediumwave set, MHz otherwise); the payload carries kHz. A value is checked with
 * [RadioTuning.problem] (the server's own checks) before it is sent, and the reason for a refusal is
 * shown under the rows -- the server used to drop it without a word (for example "145.5" on a VHF
 * set, which was read as 145.5 kHz).
 */
class RadioScreen(private val radio: RadioItem, stack: ItemStack, private val hand: InteractionHand) :
    Screen(Component.literal("Radio")) {

    private var data: RadioData = radio.dataOf(stack)

    /** MHz for every band group except mediumwave, which is read in kHz. */
    private val unitMhz = radio.bandGroup != RadioBandGroup.MW
    private val unitName = if (unitMhz) "MHz" else "kHz"
    private val offHand = hand == InteractionHand.OFF_HAND

    private var status: Component? = null
    private var statusIsError = false

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
            freqBox.setMaxLength(FREQUENCY_MAX_CHARS)
            freqBox.setFilter { it.matches(NUMBER_INPUT) }
            freqBox.value = if (slot != null && slot.enabled) formatFrequency(slot.frequencyKhz) else ""
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
                    if (current.freqBox.value.isNotEmpty()) sendTune(current)
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
        // An empty or out-of-range dial starts from the bottom of this radio's range instead of doing nothing.
        val current = parseKhz(row.freqBox.value)?.takeIf { RadioBand.fromFrequencyKhz(it)?.let(radio.bandGroup::supports) == true }
        val next = if (current == null) {
            RadioTuning.rangeKhz(radio).start
        } else {
            val band = RadioBand.fromFrequencyKhz(current)!!
            (current + direction * seekStepKhz(band)).coerceIn(band.minKhz, band.maxKhz)
        }
        row.freqBox.value = formatFrequency(next)
        sendTune(row)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            rows.firstOrNull { it.freqBox.isFocused }?.let {
                sendTune(it)
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 2, 0xFFFFFF)
        val left = panelLeft
        for (row in rows) {
            val y = rowStartY + row.index * rowHeight
            graphics.drawString(font, "${row.index + 1}", left, y + 5, 0xFFFFFF)
            val khz = parseKhz(row.freqBox.value)
            val band = khz?.let { RadioBand.fromFrequencyKhz(it) }
            if (khz != null && (band == null || !radio.bandGroup.supports(band))) {
                graphics.drawString(font, Component.translatable("radio.dncity.tune.out_of_band_short"), left + 224, y + 5, ERROR_COLOR)
            } else {
                graphics.drawString(font, "$unitName ${band?.name ?: ""}", left + 224, y + 5, 0xA0A0A0)
            }
        }
        status?.let {
            val y = rowStartY + rows.size * rowHeight + 6
            graphics.drawCenteredString(font, it, width / 2, y, if (statusIsError) ERROR_COLOR else OK_COLOR)
        }
    }

    override fun isPauseScreen(): Boolean = false

    /** [khz] in this radio's unit, without trailing zeros (145500.0 kHz -> "145.5" on a VHF set). */
    private fun formatFrequency(khz: Double): String =
        java.math.BigDecimal.valueOf(if (unitMhz) khz / 1000.0 else khz).stripTrailingZeros().toPlainString()

    /** The typed value in kHz, or null when it is not a number (decimal arithmetic, so "145.525" MHz is exactly 145525 kHz). */
    private fun parseKhz(text: String): Double? =
        text.toBigDecimalOrNull()?.let { if (unitMhz) it.movePointRight(3) else it }?.toDouble()

    private fun powerLabel(): Component = Component.literal(if (data.powered) "Power: ON" else "Power: OFF")

    private fun activeLabel(index: Int): Component = Component.literal(if (data.activeSlot == index) "TX*" else "TX")

    private fun togglePower() {
        data = data.copy(powered = !data.powered)
        PacketDistributor.sendToServer(RadioSetPoweredPayload(data.powered, offHand))
        powerButton.message = powerLabel()
    }

    private fun sendActive(index: Int) {
        data = data.copy(activeSlot = index)
        PacketDistributor.sendToServer(RadioSetActivePayload(index, offHand))
        rows.forEach { it.activeButton?.message = activeLabel(it.index) }
    }

    private fun sendTune(row: SlotRow) {
        val khz = parseKhz(row.freqBox.value)
        val problem = RadioTuning.problem(radio, row.index, khz, row.mode)
        if (problem == null) {
            PacketDistributor.sendToServer(RadioTunePayload(row.index, khz!!, row.mode.name, offHand))
            data = data.copy(slots = data.slots.toMutableList().also { it[row.index] = RadioSlot(khz, row.mode, enabled = true) })
            showStatus(
                Component.translatable("radio.dncity.tune.sent", row.index + 1, formatFrequency(khz), unitName, row.mode.name),
                error = false,
            )
            return
        }
        val args: Array<Any> = when (problem) {
            RadioTuning.Problem.OUT_OF_BAND -> RadioTuning.rangeKhz(radio).let {
                arrayOf(formatFrequency(it.start), formatFrequency(it.endInclusive), unitName)
            }
            RadioTuning.Problem.MODE_UNSUPPORTED -> arrayOf(row.mode.name)
            else -> emptyArray()
        }
        showStatus(Component.translatable(problem.translationKey, *args), error = true)
    }

    private fun showStatus(message: Component, error: Boolean) {
        status = message
        statusIsError = error
    }

    companion object {
        private const val SEEK_STEP_TOLERANCE_MULTIPLIER = 4.0
        private const val FREQUENCY_MAX_CHARS = 12
        private val NUMBER_INPUT = Regex("[0-9]*\\.?[0-9]*")
        private const val ERROR_COLOR = 0xFF5555
        private const val OK_COLOR = 0x55FF55
    }
}
