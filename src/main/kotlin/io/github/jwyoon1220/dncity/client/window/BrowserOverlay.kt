package io.github.jwyoon1220.dncity.client.window

import io.github.jwyoon1220.dncity.window.NativeWindow
import net.minecraft.client.Minecraft
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Web-page use case of the window overlay system: shows [url] in a native overlay window whose
 * actual JCEF browser/JFrame lives in a separate child process -- see [BrowserHostProcess]'s doc
 * comment for why (in short: creating the AWT/JCEF window in-process, inside Minecraft's own JVM,
 * reliably crashed the whole JVM natively). This class only ever touches the *reparented* HWND
 * [BrowserHostProcess] hands back ([WindowOverlay.createRemoteChild]) -- no AWT/Swing/JCEF
 * classes are loaded into this process at all.
 */
object BrowserOverlay {
    /**
     * All calls funnel through this single background thread rather than spawning one per call,
     * so [BrowserHostProcess.createBrowser]'s (possibly long, first-run-download) blocking call
     * and the cross-process reparent below never race each other across concurrent [open] calls.
     */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dncity-browserhost-open").apply { isDaemon = true } }

    /**
     * Opens [url] in a new overlay window, asynchronously. [BrowserHostProcess.createBrowser]
     * blocks (on this call's worker-thread task, never the caller's own thread) until the child
     * process's browser/JFrame exists and reports its HWND back. [WindowOverlay.createRemoteChild]
     * (which creates this process's own native child window, so needs the render thread's message
     * pump -- see `WindowCommand`'s doc) hops back via `Minecraft.getInstance().execute {}`, but
     * the actual cross-process `nReparent` runs back on this worker thread afterward -- see
     * [WindowOverlay.createRemoteChild]'s doc for why that must never run on the render thread.
     */
    fun open(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        url: String,
        options: OverlayOptions = OverlayOptions(),
        /**
         * Invoked on the render thread once the overlay's native window exists, with the created
         * [WindowOverlay] -- since [open] itself returns immediately (before the child-process
         * round trip below has run), this is how a caller that needs the overlay reference (to
         * [WindowOverlay.move]/[WindowOverlay.destroy] it later -- e.g.
         * [io.github.jwyoon1220.dncity.client.phone.PhoneOverlay]) gets hold of it.
         */
        onReady: (WindowOverlay) -> Unit = {},
    ) {
        worker.execute {
            val (id, hwnd) = BrowserHostProcess.createBrowser(width, height, url)

            var overlay: WindowOverlay? = null
            val renderThreadDone = CountDownLatch(1)
            Minecraft.getInstance().execute {
                try {
                    overlay = WindowOverlay.createRemoteChild(x, y, width, height, options)
                } finally {
                    renderThreadDone.countDown()
                }
            }
            renderThreadDone.await()

            val created = overlay ?: return@execute
            NativeWindow.nReparent(hwnd, created.nativeHandle)
            created.extraTeardown = { BrowserHostProcess.destroy(id) }
            Minecraft.getInstance().execute { onReady(created) }
        }
    }
}
