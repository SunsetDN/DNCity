package io.github.jwyoon1220.dncity.client.render.fsr

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.irisshaders.iris.pipeline.Fsr2PassthroughPipelineLoader

/**
 * Settings screen for FSR2 temporal upscaling, structurally mirroring
 * [io.github.jwyoon1220.dncity.client.VoiceSettingsScreen] (plain [Button]s, a [parent] for the
 * back button, in-memory-only state via [FsrConfig]) -- see AGENTS.md's "Architecture: FSR2
 * temporal upscaling" for the overall feature design.
 *
 * Per the confirmed design decision that FSR2 must work even with no Iris shaderpack loaded (see
 * `net.irisshaders.iris.pipeline.Fsr2PassthroughPipelineLoader`'s TODO), this screen is always
 * reachable/enabled once Iris is present -- it does not gate on a real shaderpack being active.
 *
 * The widget layout/state-cycling here is real (not a TODO stub, unlike [FsrRenderTargets]/
 * [FsrPassDriver]) since it only touches [FsrConfig]'s plain data; what's still a stub is any
 * downstream effect of toggling these settings (e.g. forcing a resize/history-invalidate on
 * [FsrRenderTargets], or force-loading the passthrough pipeline on enable) -- those calls are
 * left as TODO markers below rather than silently omitted, so it's clear this screen alone does
 * not make FSR2 actually run yet.
 */
class FsrSettingsScreen(private val parent: Screen?) : Screen(Component.literal("FSR2 Settings")) {

    private val panelLeft get() = (width - 220) / 2
    private val contentTop = 40

    private lateinit var enabledButton: Button
    private lateinit var qualityButton: Button
    private lateinit var sharpnessLabelButton: Button

    override fun init() {
        val left = panelLeft

        enabledButton = addRenderableWidget(
            Button.builder(enabledLabel()) { toggleEnabled() }
                .bounds(left, contentTop, 220, 20)
                .build(),
        )

        qualityButton = addRenderableWidget(
            Button.builder(qualityLabel()) { cycleQuality() }
                .bounds(left, contentTop + 26, 220, 20)
                .build(),
        )

        addRenderableWidget(
            Button.builder(Component.literal("-10%")) { adjustSharpness(-0.1f) }
                .bounds(left, contentTop + 52, 60, 20)
                .build(),
        )
        sharpnessLabelButton = addRenderableWidget(
            Button.builder(sharpnessLabel()) {}
                .bounds(left + 64, contentTop + 52, 92, 20)
                .build(),
        )
        sharpnessLabelButton.active = false
        addRenderableWidget(
            Button.builder(Component.literal("+10%")) { adjustSharpness(0.1f) }
                .bounds(left + 160, contentTop + 52, 60, 20)
                .build(),
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 4, 0xFFFFFF)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    private fun toggleEnabled() {
        FsrConfig.enabled = !FsrConfig.enabled
        enabledButton.message = enabledLabel()
        if (FsrConfig.enabled) {
            Fsr2PassthroughPipelineLoader.ensurePipelineAvailable()
            resizeHistory()
        } else {
            FsrRenderTargets.invalidateHistory()
        }
    }

    private fun cycleQuality() {
        FsrConfig.quality = FsrConfig.quality.next()
        qualityButton.message = qualityLabel()
        if (FsrConfig.enabled) resizeHistory()
    }

    private fun adjustSharpness(delta: Float) {
        FsrConfig.sharpness = (FsrConfig.sharpness + delta).coerceIn(0f, 1f)
        sharpnessLabelButton.message = sharpnessLabel()
    }

    private fun resizeHistory() {
        val window = Minecraft.getInstance().window
        FsrRenderTargets.resize(window.width, window.height)
    }

    private fun enabledLabel(): Component =
        Component.literal(if (FsrConfig.enabled) "FSR2: Enabled" else "FSR2: Disabled")

    private fun qualityLabel(): Component =
        Component.literal("Quality: ${FsrConfig.quality.label}")

    private fun sharpnessLabel(): Component =
        Component.literal("${(FsrConfig.sharpness * 100).toInt()}% sharp")
}
