package io.github.jwyoon1220.dncity.client.window

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import org.lwjgl.glfw.GLFWNativeWin32

/**
 * Client-only lifecycle manager for [WindowOverlay] instances -- same start/stop/tick shape as
 * [io.github.jwyoon1220.dncity.voice.VoiceClientLoop]. Wired from `Dncity.onClientSetup` into the
 * same `ClientTickEvent.Post` hook the voice/music subsystems use.
 *
 * Overlays are real WS_CHILD windows parented directly to the Minecraft window, so they already
 * track its position/z-order for free at the OS level -- the only thing this manager needs to
 * poll for is which [Screen] is currently open, to apply each overlay's [OverlayOptions].
 */
object WindowOverlayManager {
    private val overlays = mutableListOf<WindowOverlay>()
    private var lastScreenState: ScreenState? = null

    /** The Minecraft game window's HWND, resolved once via LWJGL (no native code needed for this). */
    val minecraftHwnd: Long by lazy {
        GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().window.window)
    }

    /**
     * `Options.pauseOnLostFocus`'s value from before the first overlay opened, restored once the
     * last one closes. An overlay is a real, separate native window, so clicking into it makes
     * Minecraft's own GLFW window lose OS focus -- which `GameRenderer.render()` otherwise treats
     * as "the player alt-tabbed away" and auto-opens the pause menu after half a second. Disabling
     * the option while any overlay is open avoids that (single-player games would otherwise jump
     * to the pause menu just from using the phone).
     */
    private var savedPauseOnLostFocus: Boolean? = null

    internal fun register(overlay: WindowOverlay) {
        if (overlays.isEmpty()) {
            val options = Minecraft.getInstance().options
            savedPauseOnLostFocus = options.pauseOnLostFocus
            options.pauseOnLostFocus = false
        }
        overlays.add(overlay)
        applyVisibility(overlay, lastScreenState ?: classify(Minecraft.getInstance().screen))
    }

    internal fun unregister(overlay: WindowOverlay) {
        overlays.remove(overlay)
        if (overlays.isEmpty()) {
            savedPauseOnLostFocus?.let { Minecraft.getInstance().options.pauseOnLostFocus = it }
            savedPauseOnLostFocus = null
        }
    }

    fun tick() {
        if (overlays.isEmpty()) return
        val screenState = classify(Minecraft.getInstance().screen)
        if (screenState != lastScreenState) {
            overlays.forEach { applyVisibility(it, screenState) }
            lastScreenState = screenState
        }
    }

    /** Destroys every tracked overlay -- called on world logout to avoid dangling HWNDs. */
    fun destroyAll() {
        overlays.toList().forEach { it.destroy() }
        lastScreenState = null
    }

    private fun applyVisibility(overlay: WindowOverlay, state: ScreenState) {
        val hide = when (state) {
            ScreenState.NONE -> false
            ScreenState.PAUSE -> overlay.options.hideOnPauseMenu
            ScreenState.INVENTORY -> overlay.options.hideOnInventoryScreen
            ScreenState.OTHER -> overlay.options.hideOnAnyOtherScreen
        }
        overlay.applyVisible(!hide)
    }

    private fun classify(screen: Screen?): ScreenState = when {
        screen == null -> ScreenState.NONE
        screen is PauseScreen -> ScreenState.PAUSE
        screen is InventoryScreen -> ScreenState.INVENTORY
        else -> ScreenState.OTHER
    }

    private enum class ScreenState { NONE, PAUSE, INVENTORY, OTHER }
}
