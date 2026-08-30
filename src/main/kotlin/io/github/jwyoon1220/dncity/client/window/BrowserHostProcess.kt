package io.github.jwyoon1220.dncity.client.window

import io.github.jwyoon1220.dncity.Dncity
import org.apache.logging.log4j.Level
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Spawns and talks to the standalone `engine:browserhost` child process that actually creates
 * JCEF browser windows. See that module's `build.gradle.kts` doc comment for why: creating an AWT
 * `JFrame`/JCEF browser inside Minecraft's own JVM needed `Unsafe`-based reflection to work
 * around `java.awt.headless=true` being forced at boot (see the now-removed
 * `WindowOverlay.ensureAwtAvailable`), which reliably crashed the whole JVM natively
 * (`EXCEPTION_ACCESS_VIOLATION` in unrelated, already-JIT-compiled AWT native-peer code) --
 * confirmed on both GraalVM CE and plain OpenJDK 21, so not a JIT-vendor bug, just inherently
 * unsafe live-state mutation. The child process never has headless forced at all, so it needs
 * none of that.
 *
 * One child process, lazily started on the first [createBrowser] call, hosts every browser window
 * for the rest of this game session (mirroring the old in-process `CefApp`'s own singleton
 * lifetime). Talks to the child over a Unix domain socket -- supported by `SocketChannel`/
 * `ServerSocketChannel` since JDK 16, and by Windows itself (`afunix.sys`) since Windows 10
 * 1803, well below what any supported dev/player machine runs. This process binds/listens
 * *before* spawning the child (passing the socket path as the child's one argument), so there's
 * no connect race to handle on either side.
 */
object BrowserHostProcess {
    private var process: Process? = null
    private var serverChannel: ServerSocketChannel? = null
    private var socketPath: Path? = null
    private var writer: java.io.BufferedWriter? = null
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Long>>()
    private val nextId = AtomicLong(1)

    @Synchronized
    private fun ensureStarted() {
        if (process?.isAlive == true) return

        val jarFile = extractJar()
        val path = Files.createTempFile("dncity-browserhost-", ".sock")
        Files.delete(path) // bind() below requires the path not to already exist
        socketPath = path

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path))
        serverChannel = server

        val javaBin = ProcessHandle.current().info().command().orElse("java")
        val builder = ProcessBuilder(javaBin, "-jar", jarFile.absolutePath, path.toString())
        builder.redirectErrorStream(true)
        val proc = builder.start()
        process = proc
        Thread({
            proc.inputStream.bufferedReader().forEachLine { line -> Dncity.LOGGER.log(Level.INFO, "browserhost: $line") }
        }, "dncity-browserhost-log").apply { isDaemon = true }.start()

        val client = server.accept() // blocks until the child connects, which it does immediately on startup
        writer = Channels.newOutputStream(client).bufferedWriter(Charsets.US_ASCII)
        val reader = Channels.newInputStream(client).bufferedReader(Charsets.US_ASCII)
        Thread({
            while (true) {
                val line = reader.readLine() ?: break
                handleResponse(line)
            }
        }, "dncity-browserhost-reader").apply { isDaemon = true }.start()

        Runtime.getRuntime().addShutdownHook(Thread(::shutdown, "dncity-browserhost-shutdown"))
    }

    private fun handleResponse(line: String) {
        val parts = line.split(' ')
        when (parts.getOrNull(0)) {
            "HWND" -> pending.remove(parts[1].toLong())?.complete(parts[2].toLong())
            "ERROR" -> {
                val message = String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
                pending.remove(parts[1].toLong())?.completeExceptionally(RuntimeException("browserhost: $message"))
            }
        }
    }

    private fun send(line: String) {
        val out = writer ?: error("BrowserHostProcess not started")
        synchronized(out) {
            out.write(line)
            out.write("\n")
            out.flush()
        }
    }

    /** Creates a browser window in the child process; returns (id, hwnd) -- [id] is needed later by [destroy]. */
    fun createBrowser(width: Int, height: Int, url: String): Pair<Long, Long> {
        ensureStarted()
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<Long>()
        pending[id] = future
        val encodedUrl = Base64.getEncoder().encodeToString(url.toByteArray(Charsets.UTF_8))
        send("CREATE $id $width $height $encodedUrl")
        // Generous timeout: the child's first-ever browser creation may trigger a one-time
        // ~150-300MB Chromium runtime download (see BrowserHostMain/CefAppBuilder's doc).
        return id to future.get(10, TimeUnit.MINUTES)
    }

    fun destroy(id: Long) {
        try {
            send("DESTROY $id")
        } catch (e: Exception) {
            Dncity.LOGGER.warn("BrowserHostProcess: failed to send DESTROY for browser $id", e)
        }
    }

    @Synchronized
    private fun shutdown() {
        try {
            writer?.let { send("SHUTDOWN") }
        } catch (_: Exception) {
        }
        process?.destroy()
        process = null
        serverChannel?.close()
        serverChannel = null
        socketPath?.let { Files.deleteIfExists(it) }
        socketPath = null
        writer = null
    }

    private fun extractJar(): File {
        val resource = javaClass.getResourceAsStream("/browserhost/browserhost-all.jar")
            ?: error("browserhost-all.jar resource not found on classpath")
        val tempFile = File.createTempFile("dncity-browserhost-", ".jar")
        tempFile.deleteOnExit()
        resource.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
        return tempFile
    }
}
