// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.client.farentity

import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType
import io.github.jwyoon1220.dncity.farentity.DhTerrainColumns
import io.github.jwyoon1220.dncity.farentity.FarEntityMath

/**
 * Client side of Distant Horizons for the far proxies. Loaded only after `ModList.isLoaded("distanthorizons")` (see
 * [FarEntityClient]).
 *
 * DH draws its LODs into its own colour + depth framebuffer and copies only the colour into Minecraft's
 * (`coreSubProjects/.../shaders/apply.frag`), so Minecraft's depth buffer knows nothing about LOD terrain and cannot hide
 * a proxy behind a far hill. The occlusion test therefore walks the terrain DH renders on the CPU
 * ([FarEntityMath.isOccluded]) on [FarEntityClient]'s background thread. The server makes its own, authoritative
 * test before it sends anyone ([io.github.jwyoon1220.dncity.farentity.FarEntityVisibility]); this one is for drawing.
 */
internal object DhLodTerrain {
    private val terrain = DhTerrainColumns(EDhApiLevelType.CLIENT_LEVEL)

    /** DH's LOD radius in blocks, 0 when DH is not rendering (or not initialised yet). Render thread. */
    fun lodRadiusBlocks(): Int {
        val configs = DhApi.Delayed.configs ?: return 0
        val graphics = configs.graphics()
        if (graphics.renderingEnabled().value != true) return 0
        return (graphics.chunkRenderDistance().value ?: 0) * 16
    }

    /** Background thread only (DH's lookup blocks). */
    fun columns(dimension: String): FarEntityMath.ColumnSource? = terrain.columns(dimension)

    fun clear() = terrain.clear()
}
