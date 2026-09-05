package io.github.jwyoon1220.dncity.client.render

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Vector4f

/** A screen-space pixel rectangle, in the same coordinate space as [io.github.jwyoon1220.dncity.client.window.WindowOverlay]'s x/y/width/height. */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(px: Int, py: Int): Boolean = px in x until x + width && py in y until y + height
}

/**
 * Tracks the screen-space rectangle a [io.github.jwyoon1220.dncity.client.window.WindowOverlay]
 * currently occupies, so `MixinEntityRenderDispatcher` can skip rendering entities whose
 * projected position falls behind it.
 *
 * Deliberately entity-only, not terrain/block: Minecraft frustum-culls terrain per-chunk, not
 * per-pixel, so a single screen-space rectangle can't skip chunk geometry submission the way it
 * can skip an individual entity's `render()` call. The real payoff here is skipping whatever
 * *fragment*-heavy work would otherwise run for pixels the overlay is about to cover anyway (an
 * Iris shader pack's per-pixel lighting on that entity, for example) -- with vanilla rendering
 * and no shader pack, the saving is limited to whatever this entity's own render cost is.
 *
 * [shouldCull] is a single-point (entity origin) approximation of "is this entity behind the
 * overlay", not true per-entity bounding-box coverage -- an entity whose model extends outside
 * the overlay while its origin sits inside it is still culled. See AGENTS.md's "window overlay"
 * section for this and other documented limitations (e.g. this has no special handling for
 * Iris's shadow-map render pass, which uses a different camera and should not be culled by a
 * screen-space rectangle meant for the main view).
 */
object OverlayCullingManager {
    var isCullingEnabled: Boolean = true

    var activeOverlayBounds: Rect? = null
        private set

    fun updateOverlayBounds(x: Int, y: Int, width: Int, height: Int) {
        activeOverlayBounds = Rect(x, y, width, height)
    }

    fun clearOverlayBounds() {
        activeOverlayBounds = null
    }

    /**
     * Projects the world-space point ([entityX], [entityY], [entityZ]) to screen space using the
     * main camera's current position and `RenderSystem`'s active view/projection matrices, and
     * checks whether the projected point lands inside [activeOverlayBounds].
     */
    fun shouldCull(entityX: Double, entityY: Double, entityZ: Double): Boolean {
        if (!isCullingEnabled) return false
        val bounds = activeOverlayBounds ?: return false

        val mc = Minecraft.getInstance()
        val window = mc.window ?: return false
        val cameraPos = mc.gameRenderer.mainCamera.position

        val relative = Vector4f(
            (entityX - cameraPos.x).toFloat(),
            (entityY - cameraPos.y).toFloat(),
            (entityZ - cameraPos.z).toFloat(),
            1f,
        )

        val combined = Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix())
        combined.transform(relative)

        // w <= 0 means the point is behind the camera (or degenerate) -- not something this
        // screen-space check applies to, so don't cull it.
        if (relative.w <= 0f) return false

        val ndcX = relative.x / relative.w
        val ndcY = relative.y / relative.w
        val screenX = ((ndcX * 0.5f + 0.5f) * window.width).toInt()
        val screenY = ((1f - (ndcY * 0.5f + 0.5f)) * window.height).toInt()

        return bounds.contains(screenX, screenY)
    }
}
