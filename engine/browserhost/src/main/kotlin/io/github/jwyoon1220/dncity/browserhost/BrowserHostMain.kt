package io.github.jwyoon1220.dncity.browserhost

import io.github.jwyoon1220.dncity.window.NativeWindow
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Entry point for the standalone JCEF/AWT host child process -- see this module's
 * build.gradle.kts doc comment for why it exists as a separate process (in short: creating an AWT
 * JFrame/JCEF browser inside Minecraft's own JVM needed Unsafe-based reflection to work around
 * `java.awt.headless=true` being forced at boot, which reliably crashed the whole JVM natively --
 * this process never has headless forced at all, so none of that is needed here).
 *
 * Launched by the main mod's `client/window/BrowserHostProcess.kt` as
 * `java -jar browserhost-all.jar <unix-domain-socket-path>`. Wire protocol (newline-delimited
 * ASCII; the parent binds/listens *before* spawning this process, so there's no connect race):
 * - Parent -> child: `CREATE <id> <width> <height> <base64-url>`, `DESTROY <id>`, `SHUTDOWN`
 * - Child -> parent: `HWND <id> <hwnd>`, `ERROR <id> <base64-message>`
 *
 * One [CefApp] (lazily built on the first `CREATE`) hosts every browser window this process ever
 * creates, the same lifetime assumption the old in-process `BrowserOverlay` used. This process
 * exits as soon as the parent's socket closes (EOF -- e.g. the game crashed or was killed) or a
 * `SHUTDOWN` command arrives, whichever comes first, so a dead parent never leaves this process
 * running forever.
 */
private val installDir = File("jcef-bundle")
private var cefApp: CefApp? = null

private data class BrowserHandle(val frame: JFrame, val browser: CefBrowser, val client: CefClient)

private val handles = HashMap<Long, BrowserHandle>()

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: browserhost <unix-domain-socket-path>" }
    val socketPath = Path.of(args[0])

    val channel = java.nio.channels.SocketChannel.open(StandardProtocolFamily.UNIX)
    channel.connect(UnixDomainSocketAddress.of(socketPath))

    val reader = Channels.newInputStream(channel).bufferedReader(Charsets.US_ASCII)
    val writer = Channels.newOutputStream(channel).bufferedWriter(Charsets.US_ASCII)

    fun send(line: String) = synchronized(writer) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    while (true) {
        val line = reader.readLine() ?: break
        val parts = line.split(' ')
        try {
            when (parts[0]) {
                "CREATE" -> {
                    val id = parts[1].toLong()
                    val width = parts[2].toInt()
                    val height = parts[3].toInt()
                    val url = String(Base64.getDecoder().decode(parts[4]), Charsets.UTF_8)
                    send("HWND $id ${createBrowserWindow(id, width, height, url)}")
                }
                "DESTROY" -> destroyBrowserWindow(parts[1].toLong())
                "SHUTDOWN" -> break
            }
        } catch (e: Throwable) {
            val id = parts.getOrNull(1) ?: "0"
            val msg = Base64.getEncoder().encodeToString((e.message ?: e.toString()).toByteArray(Charsets.UTF_8))
            send("ERROR $id $msg")
        }
    }

    handles.keys.toList().forEach(::destroyBrowserWindow)
    exitProcess(0)
}

private fun ensureCefApp(): CefApp {
    cefApp?.let { return it }
    val app = CefAppBuilder().apply { setInstallDir(installDir) }.build()
    cefApp = app
    return app
}

/** Creates the browser's JFrame, waits for it to become a real top-level window, and returns its HWND. */
private fun createBrowserWindow(id: Long, width: Int, height: Int, url: String): Long {
    val client = ensureCefApp().createClient()
    val browser = client.createBrowser(url, false, false)

    // A unique title is how NativeWindow.nFindWindowByTitle locates this specific frame's HWND
    // afterward -- same technique WindowOverlay.createFrame uses in-process.
    val uniqueTitle = "dncity-browserhost-$id-${UUID.randomUUID()}"
    lateinit var frame: JFrame
    SwingUtilities.invokeAndWait {
        frame = JFrame(uniqueTitle)
        frame.isUndecorated = true
        frame.setSize(width, height)
        frame.contentPane.add(browser.uiComponent)
        frame.isVisible = true
    }

    val hwnd = NativeWindow.nFindWindowByTitle(uniqueTitle)
    check(hwnd != 0L) { "Could not locate the browser frame's native window by its unique title" }

    handles[id] = BrowserHandle(frame, browser, client)
    return hwnd
}

private fun destroyBrowserWindow(id: Long) {
    val handle = handles.remove(id) ?: return
    SwingUtilities.invokeLater { handle.frame.dispose() }
    handle.browser.close(true)
    handle.client.dispose()
}
