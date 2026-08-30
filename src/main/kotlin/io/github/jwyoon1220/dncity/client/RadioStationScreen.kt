package io.github.jwyoon1220.dncity.client

import io.github.jwyoon1220.dncity.network.RadioStationConfigurePayload
import io.github.jwyoon1220.dncity.network.RadioStationJoinPayload
import io.github.jwyoon1220.dncity.radio.RadioBandGroup
import io.github.jwyoon1220.dncity.radio.RadioMode
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * A [io.github.jwyoon1220.dncity.block.RadioStationBlock]'s tuning + join UI, opened from
 * [io.github.jwyoon1220.dncity.voice.ClientRadioStationReceiver.handleOpen]. The broadcaster list
 * is a snapshot from the moment the block was right-clicked -- it doesn't live-update while this
 * screen stays open, same simplification the block entity's own doc comment calls out; reopen the
 * screen for a fresh list.
 */
class RadioStationScreen(
    private val blockPos: BlockPos,
    private val initialName: String,
    private val initialFrequencyKhz: Double,
    initialMode: RadioMode,
    private val broadcasterNames: List<String>,
    initiallyJoined: Boolean,
) : Screen(Component.literal("Radio Station")) {

    private var mode: RadioMode = initialMode
    private var joined: Boolean = initiallyJoined

    private lateinit var nameBox: EditBox
    private lateinit var freqBox: EditBox
    private lateinit var modeButton: Button
    private lateinit var joinButton: Button

    private val panelLeft get() = (width - 220) / 2

    override fun init() {
        val left = panelLeft
        var y = 20

        nameBox = EditBox(font, left, y, 140, 18, Component.literal("station_name"))
        nameBox.value = initialName
        addRenderableWidget(nameBox)

        freqBox = EditBox(font, left + 146, y, 74, 18, Component.literal("station_freq"))
        freqBox.value = trimFrequency(initialFrequencyKhz)
        addRenderableWidget(freqBox)
        y += 24

        modeButton = addRenderableWidget(
            Button.builder(Component.literal(mode.name)) {
                val modes = RadioBandGroup.BROADCAST.modes.toList()
                mode = modes[(modes.indexOf(mode) + 1) % modes.size]
                modeButton.message = Component.literal(mode.name)
            }.bounds(left, y, 70, 18).build(),
        )

        addRenderableWidget(
            Button.builder(Component.literal("설정")) { sendConfigure() }
                .bounds(left + 76, y, 144, 18)
                .build(),
        )
        y += 28

        joinButton = addRenderableWidget(
            Button.builder(joinLabel()) { toggleJoin() }
                .bounds(left, y, 220, 20)
                .build(),
        )
        y += 30

        broadcasterListStartY = y
    }

    private var broadcasterListStartY = 0

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val left = panelLeft
        graphics.drawCenteredString(font, title, width / 2, 4, 0xFFFFFF)

        graphics.drawString(font, "지금 방송 중:", left, broadcasterListStartY, 0xA0A0A0)
        if (broadcasterNames.isEmpty()) {
            graphics.drawString(font, "(없음)", left + 12, broadcasterListStartY + 12, 0x606060)
        } else {
            broadcasterNames.forEachIndexed { i, name ->
                graphics.drawString(font, "- $name", left + 12, broadcasterListStartY + 12 + i * 10, 0xE0E0E0)
            }
        }
    }

    override fun isPauseScreen(): Boolean = false

    private fun trimFrequency(khz: Double): String = if (khz == khz.toLong().toDouble()) khz.toLong().toString() else khz.toString()

    private fun joinLabel(): Component = Component.literal(if (joined) "나가기" else "발신 참여하기")

    private fun sendConfigure() {
        val frequency = freqBox.value.toDoubleOrNull() ?: return
        PacketDistributor.sendToServer(RadioStationConfigurePayload(blockPos, nameBox.value, frequency, mode.name))
    }

    private fun toggleJoin() {
        joined = !joined
        PacketDistributor.sendToServer(RadioStationJoinPayload(blockPos, joined))
        joinButton.message = joinLabel()
    }
}
