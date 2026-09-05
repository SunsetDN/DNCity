// AGENT-DONE(codex): fsr2-history-targets
package io.github.jwyoon1220.dncity.client.render.fsr

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft

/**
 * TODO(FSR2): owns the persistent history color buffer(s) FSR2's temporal accumulation needs,
 * plus (per AGENTS.md's "Architecture: FSR2 temporal upscaling") any DNCity-side intermediate
 * targets the reconstruction pass requires. Signature-only scaffold -- see the design notes below
 * for what each member must actually do; nothing here is wired into the render loop yet.
 *
 * Design:
 * - History buffer(s) are ping-ponged (two [com.mojang.blaze3d.pipeline.TextureTarget]-style
 *   targets, ROLE swapped each frame) and sized at **output** (post-upscale) resolution -- not
 *   the internal render resolution, since FSR2's reconstruction pass reads history at output
 *   resolution and writes a new output-resolution frame each time.
 * - [resize] must be called on window resize, quality-mode change ([FsrConfig.quality]), and
 *   whenever Iris's active pipeline reloads/hot-swaps a shaderpack (not yet located: the exact
 *   Iris-side version counter/event to hook, similar in spirit to
 *   `net.irisshaders.iris.mixin.MixinRenderTarget`'s `depthBufferVersion` field on the Iris side
 *   of this scaffold) -- any of these invalidates the history buffer's contents (stale-resolution
 *   or stale-scene data would otherwise ghost/corrupt the next frame's accumulation).
 * - [close] releases the underlying GL objects; must be called on world unload the same way
 *   `WindowOverlayManager.destroyAll()` runs on `ClientPlayerNetworkEvent.LoggingOut` (see
 *   AGENTS.md's "Architecture: window overlay" section for that precedent).
 */
object FsrRenderTargets {
    private var readTarget: TextureTarget? = null
    private var writeTarget: TextureTarget? = null
    private var validHistory = false

    val historyRead: TextureTarget?
        get() = readTarget

    val historyWrite: TextureTarget?
        get() = writeTarget

    val hasValidHistory: Boolean
        get() = validHistory
    /**
     * TODO(FSR2): (re)allocates the history buffer(s) at [outputWidth] x [outputHeight]. Called
     * once at startup and again whenever window size, [FsrConfig.quality], or the Iris pipeline
     * version changes (see class doc). Must clear/invalidate history contents on every call, not
     * just resize -- a stale-resolution history frame blended into a differently-sized new frame
     * produces visible artifacts, not just a crash.
     */
    fun resize(outputWidth: Int, outputHeight: Int) {
        require(outputWidth > 0 && outputHeight > 0) { "FSR2 output size must be positive" }
        RenderSystem.assertOnRenderThreadOrInit()
        close()
        readTarget = TextureTarget(outputWidth, outputHeight, false, Minecraft.ON_OSX)
        writeTarget = TextureTarget(outputWidth, outputHeight, false, Minecraft.ON_OSX)
        invalidateHistory()
    }

    /**
     * TODO(FSR2): explicitly discards history contents without necessarily changing size --
     * needed on Iris shaderpack hot-swap (scene composition changed even if resolution didn't) and
     * on world/dimension change (camera teleported, so the previous frame's history is nonsense
     * for reprojection).
     */
    fun invalidateHistory() {
        RenderSystem.assertOnRenderThreadOrInit()
        readTarget?.clear(Minecraft.ON_OSX)
        writeTarget?.clear(Minecraft.ON_OSX)
        validHistory = false
    }

    /** Releases both output-resolution history targets. */
    fun close() {
        RenderSystem.assertOnRenderThreadOrInit()
        readTarget?.destroyBuffers()
        writeTarget?.destroyBuffers()
        readTarget = null
        writeTarget = null
        validHistory = false
    }

    /** Makes the freshly rendered history the read source for the next frame. */
    fun swapHistory() {
        check(readTarget != null && writeTarget != null) { "FSR2 history targets have not been allocated" }
        val previousRead = readTarget
        readTarget = writeTarget
        writeTarget = previousRead
        validHistory = true
    }
}
