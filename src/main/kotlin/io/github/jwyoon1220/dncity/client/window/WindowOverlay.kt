package io.github.jwyoon1220.dncity.client.window

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.client.render.OverlayCullingManager
import io.github.jwyoon1220.dncity.window.NativeWindow
import java.util.UUID
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * A single native child-window overlay, positioned as a 2D screen-space rectangle over the
 * Minecraft window. Created via [createFrame] (AWT/Swing-hosting, e.g. for [BrowserOverlay]) or
 * [createNativeHandle] (a completely empty native window for external native rendering) -- both
 * share the same underlying [NativeWindow.nCreateChild] call, see that class's doc comment.
 *
 * Visibility is governed by [options] and applied automatically by [WindowOverlayManager]; call
 * [destroy] to tear the overlay down (also done automatically on world logout).
 */
class WindowOverlay internal constructor(
    private var hwnd: Long,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    val options: OverlayOptions,
    /** The hosted frame, only present for overlays created via [createFrame]. */
    val awtFrame: JFrame? = null,
) {
    /** The raw native window handle (an HWND on Windows), for external native rendering. */
    val nativeHandle: Long get() = hwnd

    /**
     * Extra teardown to run from [destroy], before the native window/frame are torn down --
     * e.g. [BrowserOverlay] uses this to release its JCEF browser/client, so any caller that
     * destroys the overlay (not just [BrowserOverlay] itself) releases those resources too.
     */
    internal var extraTeardown: (() -> Unit)? = null

    /** Current corner radius applied via [setCornerRadius], 0 meaning a normal rectangle. */
    var cornerRadius: Int = 0
        private set

    private var visible = false

    internal fun applyVisible(visible: Boolean) {
        this.visible = visible
        if (hwnd != 0L) NativeWindow.nShow(hwnd, visible)
        // See OverlayCullingManager: only a window that's actually on screen should cull
        // whatever's behind it.
        if (visible) {
            OverlayCullingManager.updateOverlayBounds(x, y, width, height)
        } else {
            OverlayCullingManager.clearOverlayBounds()
            // Clicking into the overlay (especially BrowserOverlay's cross-process window) can
            // give it real OS input focus; hiding it (e.g. opening the pause menu) doesn't hand
            // that focus back to Minecraft on its own, which otherwise leaves Minecraft unable to
            // receive clicks/keys at all until something else takes focus.
            NativeWindow.nSetForegroundWindow(WindowOverlayManager.minecraftHwnd)
        }
    }

    fun move(x: Int, y: Int, width: Int, height: Int) {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
        if (hwnd == 0L) return
        NativeWindow.nMove(hwnd, x, y, width, height)
        // The rounded-rect region is sized in window-local coordinates, so a size change
        // invalidates it -- recompute against the new width/height rather than leaving the old
        // (now mismatched) region in place.
        if (cornerRadius > 0) NativeWindow.nSetRoundRectRgn(hwnd, width, height, cornerRadius)
        if (visible) OverlayCullingManager.updateOverlayBounds(x, y, width, height)
    }

    /**
     * Clips this overlay to a rounded-rectangle region ([radius] pixels), phone-UI style, using
     * the current [width]/[height]. Pass 0 to restore a normal rectangular window.
     */
    fun setCornerRadius(radius: Int) {
        cornerRadius = radius
        if (hwnd != 0L) NativeWindow.nSetRoundRectRgn(hwnd, width, height, radius)
    }

    fun destroy() {
        if (hwnd == 0L) return
        WindowOverlayManager.unregister(this)
        if (visible) OverlayCullingManager.clearOverlayBounds()
        extraTeardown?.invoke()
        extraTeardown = null
        awtFrame?.let { frame -> SwingUtilities.invokeLater { frame.dispose() } }
        NativeWindow.nDestroy(hwnd)
        hwnd = 0L
        NativeWindow.nSetForegroundWindow(WindowOverlayManager.minecraftHwnd)
    }

    companion object {
        /**
         * `net.minecraft.client.main.Main` (Minecraft's own client entry point) unconditionally
         * sets `java.awt.headless=true` in a static initializer at JVM boot -- it renders via
         * GLFW, not AWT, and this predates any of this mod's code running. Left alone, the first
         * thing anywhere in the process that touches AWT throws `HeadlessException` regardless of
         * a real display being present -- not just `java.awt.Window`/`Frame`/`JFrame`
         * construction: JCEF's `CefBrowserWr` constructor calls `MouseInfo.getNumberOfButtons()`
         * internally, which hits the same check. So this must run before *any* AWT-touching call,
         * not just before this class's own `JFrame(...)` -- [BrowserOverlay.open] calls this
         * before constructing its `CefBrowser` (which happens before it ever calls [createFrame])
         * for exactly that reason.
         *
         * Flipping the property alone is not enough in practice: `GraphicsEnvironment.isHeadless()`
         * caches its result in private static fields (`headless`/`defaultHeadless`) the first time
         * *anything* in the process calls it, and by the time this mod's code runs, something else
         * already has. [resetGeCaches] resets those.
         *
         * Plain reflection (`field.isAccessible = true`) on `java.awt.GraphicsEnvironment`'s
         * private fields throws `InaccessibleObjectException` naming module `dncity` as the denied
         * requester, even with `--add-opens java.desktop/java.awt=ALL-UNNAMED,dncity` present at
         * launch (confirmed by hand: it's there verbatim in the generated
         * `build/moddev/clientRunVmArgs.txt`). Root cause (confirmed by hand from the stack trace
         * and NeoForge's launch setup): `--add-opens` on the JVM command line is only consulted
         * while the JVM resolves its *boot* module layer at startup; `dncity` isn't a module yet at
         * that point -- NeoForge's `cpw.mods.bootstraplauncher`/`securejarhandler` define it
         * dynamically, in a *second* `ModuleLayer` built at runtime from the mod jars on the game's
         * classpath/modpath, which command-line `--add-opens` simply never sees. `sun.misc.Unsafe`
         * writes bypass access control (visibility, module opens) entirely at the memory level, so
         * which module layer defined the target class doesn't matter -- [getUnsafe] obtains it
         * (itself only needing plain reflection into `sun.misc`, which -- unlike `java.awt` above --
         * does *not* throw: `jdk.unsupported` opens that package unconditionally, not just to
         * `ALL-UNNAMED`, a JDK-level exemption kept specifically so legacy/low-level libraries can
         * keep using `Unsafe` without needing `--add-opens` of their own).
         *
         * Resetting `headless`/`defaultHeadless` alone is *also* not enough on its own (confirmed
         * by hand: still threw `HeadlessException` from `HeadlessGraphicsEnvironment.getDefault-
         * ScreenDevice` -- i.e. from the headless implementation class itself -- with both fields
         * successfully nulled). `GraphicsEnvironment.getLocalGraphicsEnvironment()` doesn't
         * actually re-check `headless` after its first call: it compiles to a lazy-holder-idiom
         * singleton (`GraphicsEnvironment$LocalGE.INSTANCE`, a `static final` field computed once
         * by `LocalGE`'s static initializer, the first time anything in the process calls
         * `getLocalGraphicsEnvironment()`). Something during Minecraft/NeoForge's own boot already
         * triggered that -- while still headless -- permanently binding `INSTANCE` to a
         * `HeadlessGraphicsEnvironment`.
         *
         * **Do not** try to fix this by `Unsafe`-overwriting `INSTANCE` itself (tried and reverted
         * -- confirmed by hand: it crashed the whole JVM with `EXCEPTION_ACCESS_VIOLATION` deep in
         * unrelated JIT-compiled AWT native-peer code, tens of seconds later once the JIT warmed
         * up). `java.awt.GraphicsEnvironment` is a bootstrap-loaded class, and HotSpot/Graal's JIT
         * performs "trusted final field" constant-folding for `static final` fields on
         * bootstrap-loaded classes even when the field's type is a plain object reference, not
         * just for primitives/`String`s -- overwriting `INSTANCE` post-hoc violates that
         * assumption and can corrupt already-JIT-compiled code anywhere else in the process that
         * touched the field, not just call sites near the write.
         *
         * Instead, [freshGraphicsConfiguration] leaves `LocalGE.INSTANCE` alone (however broken)
         * and sidesteps it entirely: it calls the private static `LocalGE.createGE()` (now that
         * `headless` is false) to build a *separate*, real, non-cached `GraphicsEnvironment` purely
         * to read a valid `GraphicsConfiguration` off of, which [createFrame] then passes explicitly
         * into `JFrame(title, gc)` -- a constructor overload that never calls
         * `getLocalGraphicsEnvironment()` at all when given a non-null `gc`. `createGE()` can't be
         * invoked via plain `setAccessible` reflection for the same module-layer reason as above,
         * and `Unsafe` itself has no "invoke a method" primitive -- only field/memory access -- so
         * this borrows the well-known "trusted `MethodHandles.Lookup`" trick: `Unsafe`-read
         * `MethodHandles.Lookup`'s own private `IMPL_LOOKUP` field (the fully-privileged lookup the
         * JDK's own internals use), which -- unlike a normal `Lookup` obtained via
         * `MethodHandles.lookup()` -- can `findStatic` a private method on an arbitrary class
         * without any accessibility or module-boundary check at all. This only *reads* `createGE()`'s
         * return value -- no field anywhere is written via `Unsafe`, so there's no final-field
         * constant-folding hazard.
         */
        internal fun ensureAwtAvailable() {
            System.setProperty("java.awt.headless", "false")
            if (!resetHeadlessCaches()) {
                Dncity.LOGGER.warn(
                    "WindowOverlay: could not reset GraphicsEnvironment's cached headless flags; " +
                        "AWT/JCEF calls may still throw HeadlessException",
                )
            }
        }

        private fun getUnsafe(): sun.misc.Unsafe {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            return unsafeField.get(null) as sun.misc.Unsafe
        }

        private fun resetHeadlessCaches(): Boolean = try {
            val unsafe = getUnsafe()
            val geClass = java.awt.GraphicsEnvironment::class.java
            for (fieldName in arrayOf("headless", "defaultHeadless")) {
                val field = geClass.getDeclaredField(fieldName)
                unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), null)
            }
            true
        } catch (e: Throwable) {
            Dncity.LOGGER.warn("WindowOverlay: failed to reset GraphicsEnvironment's headless caches", e)
            false
        }

        /**
         * Builds a fresh, real (non-headless) [java.awt.GraphicsConfiguration] without touching
         * `GraphicsEnvironment.getLocalGraphicsEnvironment()`'s own (possibly still-headless)
         * cached singleton -- see [ensureAwtAvailable]'s doc for why that singleton can't safely be
         * repaired in place. Returns `null` (logging why) if the trusted-lookup trick fails for any
         * reason; callers should fall back to a `GraphicsConfiguration`-less code path.
         */
        internal fun freshGraphicsConfiguration(): java.awt.GraphicsConfiguration? = try {
            val unsafe = getUnsafe()

            val implLookupField = java.lang.invoke.MethodHandles.Lookup::class.java.getDeclaredField("IMPL_LOOKUP")
            val trustedLookup = unsafe.getObject(
                unsafe.staticFieldBase(implLookupField),
                unsafe.staticFieldOffset(implLookupField),
            ) as java.lang.invoke.MethodHandles.Lookup

            val localGeClass = Class.forName("java.awt.GraphicsEnvironment\$LocalGE")
            val createGE = trustedLookup.findStatic(
                localGeClass,
                "createGE",
                java.lang.invoke.MethodType.methodType(java.awt.GraphicsEnvironment::class.java),
            )
            val freshEnv = createGE.invoke() as java.awt.GraphicsEnvironment
            freshEnv.defaultScreenDevice.defaultConfiguration
        } catch (e: Throwable) {
            Dncity.LOGGER.warn("WindowOverlay: failed to build a fresh non-headless GraphicsConfiguration", e)
            null
        }

        /**
         * Creates a native child window with an [javax.swing.JFrame] reparented into it. The
         * frame is built, sized, and populated via [onCreated] *before* it's shown/reparented, so
         * callers never see a flash of an empty white frame.
         */
        fun createFrame(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            title: String = "",
            options: OverlayOptions = OverlayOptions(),
            onCreated: (JFrame) -> Unit = {},
        ): WindowOverlay {
            ensureAwtAvailable()
            val gc = freshGraphicsConfiguration()
            val childHwnd = NativeWindow.nCreateChild(WindowOverlayManager.minecraftHwnd, x, y, width, height, title)
            check(childHwnd != 0L) { "Failed to create native overlay window" }

            val uniqueTitle = "dncity-overlay-${UUID.randomUUID()}"
            lateinit var frame: JFrame
            SwingUtilities.invokeAndWait {
                frame = if (gc != null) JFrame(uniqueTitle, gc) else JFrame(uniqueTitle)
                frame.isUndecorated = true
                frame.setSize(width, height)
                onCreated(frame)
                frame.isVisible = true
            }

            val awtHwnd = NativeWindow.nFindWindowByTitle(uniqueTitle)
            check(awtHwnd != 0L) { "Could not locate the AWT frame's native window by its unique title" }
            NativeWindow.nReparent(awtHwnd, childHwnd)

            val overlay = WindowOverlay(childHwnd, x, y, width, height, options, frame)
            WindowOverlayManager.register(overlay)
            return overlay
        }

        /**
         * Creates a native child window for hosting an *already-existing* top-level window owned
         * by another process -- unlike [createFrame], this never touches AWT/`JFrame`/
         * `GraphicsEnvironment` itself, since that window was created by
         * [BrowserHostProcess]'s child process instead. Deliberately does **not** reparent that
         * window itself: `SetParent` across process boundaries sends a synchronous message to the
         * target window's owning thread and blocks the caller until it's handled, which -- if
         * that thread is busy (e.g. the child process is still spinning up Chromium's
         * renderer/GPU subprocesses) -- would freeze the entire game if run here, since this must
         * run on the render thread (`nCreateChild`'s parent is Minecraft's own window, and
         * Win32 windows are only pumped by whatever thread creates them -- Minecraft's GLFW pump
         * is the render thread). [BrowserOverlay] reparents the remote window itself from a
         * background thread, using [nativeHandle] as the new parent, once this call returns.
         */
        fun createRemoteChild(x: Int, y: Int, width: Int, height: Int, options: OverlayOptions): WindowOverlay {
            val childHwnd = NativeWindow.nCreateChild(WindowOverlayManager.minecraftHwnd, x, y, width, height, "")
            check(childHwnd != 0L) { "Failed to create native overlay window" }

            val overlay = WindowOverlay(childHwnd, x, y, width, height, options)
            WindowOverlayManager.register(overlay)
            return overlay
        }

        /**
         * Creates a completely empty native child window -- no AWT/Swing involvement at all --
         * and returns its handle via [nativeHandle] for external native rendering to consume.
         */
        fun createNativeHandle(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            title: String = "",
            options: OverlayOptions = OverlayOptions(),
        ): WindowOverlay {
            val childHwnd = NativeWindow.nCreateChild(WindowOverlayManager.minecraftHwnd, x, y, width, height, title)
            check(childHwnd != 0L) { "Failed to create native overlay window" }
            val overlay = WindowOverlay(childHwnd, x, y, width, height, options)
            WindowOverlayManager.register(overlay)
            return overlay
        }
    }
}
