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

    private class SlotRow(val index: Int, val freqBox: EditBox, var mode: RadioMode, val modeButton: Button, val activeButton: Button)

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

            val freqBox = EditBox(font, left + 16, y, 70, 18, Component.literal("freq_$i"))
            freqBox.value = if (slot != null && slot.enabled) trimFrequency(slot.frequencyKhz) else ""
            addRenderableWidget(freqBox)

            var mode = slot?.mode ?: RadioMode.AM
            var modeButton: Button = addRenderableWidget(
                Button.builder(Component.literal(mode.name)) {
                    val current = rows.first { it.index == i }
                    current.mode = RadioMode.entries[(current.mode.ordinal + 1) % RadioMode.entries.size]
                    current.modeButton.message = Component.literal(current.mode.name)
                    sendTune(current)
                }.bounds(left + 92, y, 48, 18).build(),
            )

            val activeButton = addRenderableWidget(
                Button.builder(activeLabel(i)) { sendActive(i) }
                    .bounds(left + 144, y, 30, 18)
                    .build(),
            )

            val row = SlotRow(i, freqBox, mode, modeButton, activeButton)
            rows.add(row)

            addRenderableWidget(
                Button.builder(Component.literal("Set")) { sendTune(row) }
                    .bounds(left + 178, y, 42, 18)
                    .build(),
            )
        }
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
        rows.forEach { it.activeButton.message = activeLabel(it.index) }
    }

    private fun sendTune(row: SlotRow) {
        val frequency = row.freqBox.value.toDoubleOrNull() ?: return
        PacketDistributor.sendToServer(RadioTunePayload(row.index, frequency, row.mode.name))
    }
}
