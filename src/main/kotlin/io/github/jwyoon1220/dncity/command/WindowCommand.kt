package io.github.jwyoon1220.dncity.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.jwyoon1220.dncity.client.window.BrowserOverlay
import io.github.jwyoon1220.dncity.client.window.WindowOverlay
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.awt.Color
import javax.swing.JButton
import javax.swing.JPanel

/**
 * "/windowtest" and "/browser" -- debug-only commands for manually exercising [WindowOverlay]/
 * [BrowserOverlay] in `runClient` (there's no real consumer feature wired to either yet).
 * Client-only: registered from `Dncity.onClientSetup`, not the shared `init {}` block other
 * commands use, since these bodies touch AWT/native window APIs (and JCEF) that must never run
 * on a dedicated server.
 *
 * Brigadier commands run on the integrated server's own thread, even in singleplayer -- but a
 * native Win32 window is thread-affine (its messages only get pumped by whatever thread's
 * message loop created it), and Minecraft's own GLFW message pump runs on the render thread, not
 * the server thread. Every handler below hops onto the render thread via
 * `Minecraft.getInstance().execute {}` before touching [WindowOverlay]/[BrowserOverlay] so the
 * native window actually gets its messages pumped.
 */
object WindowCommand {

    fun onRegisterCommands(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("windowtest")
                .then(Commands.literal("frame").executes { spawnFrame(); 1 })
                .then(Commands.literal("handle").executes { ctx -> spawnHandle(ctx.source); 1 }),
        )
        dispatcher.register(
            Commands.literal("browser")
                .then(
                    Commands.literal("open")
                        .then(
                            Commands.argument("url", StringArgumentType.greedyString())
                                .executes { ctx -> openBrowser(ctx.source, StringArgumentType.getString(ctx, "url")); 1 },
                        ),
                ),
        )
    }

    /** Rounded-corner radius (px) applied to `/windowtest` overlays -- see [WindowOverlay.setCornerRadius]. */
    private const val TEST_CORNER_RADIUS = 30

    private fun spawnFrame() {
        Minecraft.getInstance().execute {
            val overlay = WindowOverlay.createFrame(100, 100, 400, 300, "DNCity Overlay Test") { frame ->
                val panel = JPanel().apply { background = Color(0x22, 0x88, 0xCC) }
                panel.add(JButton("Close").apply { addActionListener { frame.isVisible = false } })
                frame.contentPane.add(panel)
            }
            overlay.setCornerRadius(TEST_CORNER_RADIUS)
        }
    }

    private fun spawnHandle(source: CommandSourceStack) {

        Minecraft.getInstance().execute {
            val overlay = WindowOverlay.createNativeHandle(500, 100, 300, 200, "DNCity Raw Handle Test")
            overlay.setCornerRadius(TEST_CORNER_RADIUS)
            // Reported from the render thread this now runs on, not the server thread the
            // command itself dispatched from -- sendSuccess is just queuing a chat packet, which
            // is fine to do off the original command thread.
            source.sendSuccess({ Component.literal("Raw HWND = ${overlay.nativeHandle}") }, false)
        }
    }

    private fun openBrowser(source: CommandSourceStack, url: String) {
        source.sendSuccess({ Component.literal("Opening $url (first run may download the JCEF/Chromium runtime, ~150-300MB)") }, false)
        // Not wrapped in Minecraft.getInstance().execute {} like the other handlers -- open()
        // manages its own background-thread/render-thread split internally (see its doc), since
        // the JCEF download it triggers on first use must not block the render thread.
        BrowserOverlay.open(100, 100, 800, 600, url)
    }
}
