package io.github.jwyoon1220.dncity.client

import io.github.jwyoon1220.dncity.audio.NativeAudio
import io.github.jwyoon1220.dncity.voice.CloseRangeVoice
import io.github.jwyoon1220.dncity.voice.VoiceClientLoop
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Mod config screen for the close-range voice tier (see [io.github.jwyoon1220.dncity.voice],
 * registered as this mod's [net.neoforged.neoforge.client.gui.IConfigScreenFactory] in
 * `Dncity.onClientSetup`, so it's reachable from the Mods list's "Config" button).
 *
 * Split into tabs the same way [io.github.jwyoon1220.dncity.client.RadioScreen] keeps its widget
 * layout simple: plain [Button]s, no vanilla `Tab`/`TabManager` machinery -- a "Voice" tab for
 * settings that actually change behavior (mute, noise gate threshold) and an "Info" tab for
 * live read-only state (mic level, VAD), kept apart so the settings tab doesn't get cluttered
 * with numbers that update every frame.
 *
 * Settings here are in-memory only for now ([VoiceClientLoop]'s fields) -- they reset to
 * defaults on game restart; not yet persisted to a config file.
 */
class VoiceSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Voice Settings")) {

    private enum class Tab(val label: String) {
        VOICE("Voice"),
        DEVICES("Devices"),
        INFO("Info"),
    }

    private var currentTab = Tab.VOICE
    private val panelLeft get() = (width - 220) / 2
    private val contentTop = 46

    private lateinit var muteButton: Button
    private lateinit var thresholdLabelButton: Button

    // "Default (Windows)" plus every enumerated device name, in display order -- display index 0
    // is always the OS default, display index i (i > 0) maps to NativeAudio device index i - 1.
    private var captureDeviceNames: List<String> = emptyList()
    private var playbackDeviceNames: List<String> = emptyList()
    private var captureDisplayIndex = 0
    private var playbackDisplayIndex = 0
    private lateinit var captureLabelButton: Button
    private lateinit var playbackLabelButton: Button

    override fun init() {
        val left = panelLeft
        for ((i, tab) in Tab.entries.withIndex()) {
            addRenderableWidget(
                Button.builder(tabLabel(tab)) { switchTab(tab) }
                    .bounds(left + i * 74, 20, 70, 18)
                    .build(),
            )
        }

        when (currentTab) {
            Tab.VOICE -> buildVoiceTab(left)
            Tab.DEVICES -> buildDevicesTab(left)
            Tab.INFO -> {}
        }
    }

    private fun buildVoiceTab(left: Int) {
        muteButton = addRenderableWidget(
            Button.builder(muteLabel()) { toggleMute() }
                .bounds(left, contentTop, 220, 20)
                .build(),
        )

        addRenderableWidget(
            Button.builder(Component.literal("-5 dB")) { adjustThreshold(-5f) }
                .bounds(left, contentTop + 26, 60, 20)
                .build(),
        )
        thresholdLabelButton = addRenderableWidget(
            Button.builder(thresholdLabel()) {}
                .bounds(left + 64, contentTop + 26, 92, 20)
                .build(),
        )
        thresholdLabelButton.active = false
        addRenderableWidget(
            Button.builder(Component.literal("+5 dB")) { adjustThreshold(5f) }
                .bounds(left + 160, contentTop + 26, 60, 20)
                .build(),
        )
    }

    /**
     * Mic/speaker device pickers -- cycles a "Default (Windows)" + enumerated-device-name list
     * with left/right arrow buttons, mirroring [buildVoiceTab]'s threshold widget layout. Devices
     * are re-enumerated every time this tab is built (cheap once [NativeAudio.init] has run) so a
     * device plugged in mid-session shows up next time the tab is opened.
     */
    private fun buildDevicesTab(left: Int) {
        NativeAudio.init()
        captureDeviceNames = NativeAudio.listCaptureDevices().toList()
        playbackDeviceNames = NativeAudio.listPlaybackDevices().toList()
        captureDisplayIndex = (NativeAudio.getSelectedCaptureDevice() + 1).coerceIn(0, captureDeviceNames.size)
        playbackDisplayIndex = (NativeAudio.getSelectedPlaybackDevice() + 1).coerceIn(0, playbackDeviceNames.size)

        addRenderableWidget(
            Button.builder(Component.literal("<")) { cycleCapture(-1) }
                .bounds(left, contentTop, 20, 20)
                .build(),
        )
        captureLabelButton = addRenderableWidget(
            Button.builder(deviceLabel("Mic", captureDeviceNames, captureDisplayIndex)) {}
                .bounds(left + 24, contentTop, 172, 20)
                .build(),
        )
        captureLabelButton.active = false
        addRenderableWidget(
            Button.builder(Component.literal(">")) { cycleCapture(1) }
                .bounds(left + 200, contentTop, 20, 20)
                .build(),
        )

        addRenderableWidget(
            Button.builder(Component.literal("<")) { cyclePlayback(-1) }
                .bounds(left, contentTop + 26, 20, 20)
                .build(),
        )
        playbackLabelButton = addRenderableWidget(
            Button.builder(deviceLabel("Speaker", playbackDeviceNames, playbackDisplayIndex)) {}
                .bounds(left + 24, contentTop + 26, 172, 20)
                .build(),
        )
        playbackLabelButton.active = false
        addRenderableWidget(
            Button.builder(Component.literal(">")) { cyclePlayback(1) }
                .bounds(left + 200, contentTop + 26, 20, 20)
                .build(),
        )
    }

    private fun cycleCapture(direction: Int) {
        val count = captureDeviceNames.size + 1
        captureDisplayIndex = (captureDisplayIndex + direction + count) % count
        VoiceClientLoop.selectCaptureDevice(captureDisplayIndex - 1)
        captureLabelButton.message = deviceLabel("Mic", captureDeviceNames, captureDisplayIndex)
    }

    private fun cyclePlayback(direction: Int) {
        val count = playbackDeviceNames.size + 1
        playbackDisplayIndex = (playbackDisplayIndex + direction + count) % count
        VoiceClientLoop.selectPlaybackDevice(playbackDisplayIndex - 1)
        playbackLabelButton.message = deviceLabel("Speaker", playbackDeviceNames, playbackDisplayIndex)
    }

    private fun deviceLabel(prefix: String, names: List<String>, displayIndex: Int): Component {
        val name = if (displayIndex == 0) "Default (Windows)" else names[displayIndex - 1]
        return Component.literal("$prefix: $name")
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 4, 0xFFFFFF)

        if (currentTab == Tab.INFO) {
            renderInfoTab(graphics)
        }
    }

    private fun renderInfoTab(graphics: GuiGraphics) {
        val left = panelLeft
        var y = contentTop
        val lineHeight = 14

        fun line(text: String, color: Int = 0xFFFFFF) {
            graphics.drawString(font, text, left, y, color)
            y += lineHeight
        }

        line("Max range: ${CloseRangeVoice.MAX_RANGE_BLOCKS.toInt()} blocks")
        if (!VoiceClientLoop.isRunning) {
            line("Voice chat is not active (join a world to enable it)", 0xA0A0A0)
            return
        }
        line("Mic: ${if (VoiceClientLoop.muted) "muted" else "live"}")
        line("Speaking: ${if (NativeAudio.isVoiceActive()) "yes" else "no"}")
        line("Input level: ${"%.1f".format(NativeAudio.getInputLevelDb())} dBFS")
        line("Noise gate threshold: ${"%.0f".format(VoiceClientLoop.noiseGateThresholdDb)} dBFS")
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab
        clearWidgets()
        init()
    }

    private fun toggleMute() {
        VoiceClientLoop.muted = !VoiceClientLoop.muted
        muteButton.message = muteLabel()
    }

    private fun adjustThreshold(deltaDb: Float) {
        VoiceClientLoop.noiseGateThresholdDb = (VoiceClientLoop.noiseGateThresholdDb + deltaDb).coerceIn(-80f, -20f)
        thresholdLabelButton.message = thresholdLabel()
    }

    private fun tabLabel(tab: Tab): Component {
        val prefix = if (tab == currentTab) "> " else ""
        return Component.literal(prefix + tab.label)
    }

    private fun muteLabel(): Component =
        Component.literal(if (VoiceClientLoop.muted) "Mic: Muted" else "Mic: Live")

    private fun thresholdLabel(): Component =
        Component.literal("${"%.0f".format(VoiceClientLoop.noiseGateThresholdDb)} dBFS")
}
