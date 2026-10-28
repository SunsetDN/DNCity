// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.client.farentity

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Vanilla's per-entity render distance (`Entity.shouldRenderAtSqrDistance`, 1.21.1) is bounding-box size x 64 x view
 * scale, view scale being `clamp(render distance / 8, 1, 2.5) x entityDistanceScaling` (`LevelRenderer`): a player (size
 * 1.0) disappears at 96 blocks with 12 chunks of terrain, long before the terrain under them does. For people and
 * vehicles this lifts that cap to the client's chunk render distance; how far the server sends them is still vanilla
 * tracking, and beyond tracking the far proxies take over ([FarEntityClient]). Chunk-section and Sodium occlusion
 * culling still apply. Other entities (items, projectiles, XP orbs...) keep vanilla's distance.
 */
object EntityRenderDistance {
    /** Bounding boxes at least this big (mean side, blocks) count as vehicles even with nobody inside. DESIGN. */
    private const val VEHICLE_SIZE = 2.0

    @JvmStatic
    fun rendersWithin(entity: Entity, distanceSq: Double): Boolean {
        if (!(entity is LivingEntity || entity.isVehicle || entity.boundingBox.size >= VEHICLE_SIZE)) return false
        val blocks = Minecraft.getInstance().options.effectiveRenderDistance * 16.0
        return distanceSq < blocks * blocks
    }
}
