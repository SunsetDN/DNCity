package io.github.jwyoon1220.dncity.client.phone

import icyllis.modernui.mc.MuiScreen
import icyllis.modernui.mc.MuiModApi
import io.github.jwyoon1220.dncity.client.phone.nanovg.PhoneNanoVgSurface
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Opens/closes [PhoneFragment] as a plain ModernUI [MuiScreen] -- replaces the removed JCEF-based
 * `PhoneOverlay` (see git history). Synchronous and render-thread-only throughout: unlike the old
 * async/cross-process open sequence, ModernUI screen creation has no child process or network
 * round trip to wait on, so there's no in-flight/generation-guard state to track any more.
 */
object PhoneController {
    val isOpen: Boolean
        get() {
            val screen = Minecraft.getInstance().screen
            return screen is MuiScreen && screen.fragment is PhoneFragment
        }

    /** No-op if already open. Must be called from the render thread. */
    fun open() {
        if (isOpen) return
        // Explicit `Screen` type is load-bearing here -- see MaterialScreens.kt's doc comment:
        // MuiModApi.createScreen's return type is an unbounded `<T extends Screen & MuiScreen> T`
        // with no argument to infer T from, so without an expected type Kotlin infers T as
        // Nothing and the call throws KotlinNothingValueException at runtime.
        PhoneNanoVgSurface.create()
        val screen: Screen = MuiModApi.get().createScreen(PhoneFragment())
        Minecraft.getInstance().setScreen(screen)
    }

    /** No-op if not open. Must be called from the render thread. */
    fun close() {
        if (!isOpen) return
        Minecraft.getInstance().setScreen(null)
        PhoneNanoVgSurface.destroy()
    }

    /** See [io.github.jwyoon1220.dncity.client.ModKeyMappings.PHONE_TOGGLE]. Must be called from the render thread. */
    fun toggle() {
        if (isOpen) close() else open()
    }
}
