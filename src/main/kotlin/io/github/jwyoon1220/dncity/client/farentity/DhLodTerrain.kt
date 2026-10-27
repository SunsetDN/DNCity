// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.client.farentity

import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper
import io.github.jwyoon1220.dncity.farentity.FarEntityMath

/**
 * The only class that touches Distant Horizons' API. Loaded only after `ModList.isLoaded("distanthorizons")` (see
 * [FarEntityClient]), so DNCity still runs without DH.
 *
 * DH draws its LODs into its own colour + depth framebuffer and copies only the colour into Minecraft's
 * (`coreSubProjects/.../shaders/apply.frag`), so Minecraft's depth buffer knows nothing about LOD terrain and cannot hide
 * a proxy behind a far hill. The occlusion test therefore walks DH's own terrain data on the CPU
 * ([FarEntityMath.isOccluded]). DH's column lookup blocks on its data provider (`getAsync(...).get()` in
 * `DhApiTerrainDataRepo`), so it is only ever called from [FarEntityClient]'s background thread.
 */
internal object DhLodTerrain {
    private var cacheDimension: String? = null
    private var cache: IDhApiTerrainDataCache? = null

    /** DH's LOD radius in blocks, 0 when DH is not rendering (or not initialised yet). Render thread. */
    fun lodRadiusBlocks(): Int {
        val configs = DhApi.Delayed.configs ?: return 0
        val graphics = configs.graphics()
        if (graphics.renderingEnabled().value != true) return 0
        return (graphics.chunkRenderDistance().value ?: 0) * 16
    }

    /** A column sampler for [dimension] (`namespace:path`), or null while DH has no world for it. Background thread. */
    @Synchronized
    fun columns(dimension: String): FarEntityMath.ColumnSource? {
        val repo = DhApi.Delayed.terrainRepo ?: return null
        val level = findLevel(dimension) ?: return null
        if (cacheDimension != dimension) {
            cache?.clear()
            cache = repo.softCache
            cacheDimension = dimension
        }
        val levelCache = cache
        return FarEntityMath.ColumnSource { x, z ->
            val result = repo.getColumnDataAtBlockPos(level, x, z, levelCache)
            val points = if (result.success) result.payload else null
            if (points == null) {
                null
            } else {
                val spans = ArrayList<Int>()
                for (point in points) {
                    val state = point?.blockStateWrapper ?: continue
                    // water and lava are drawn translucent by DH; you see a swimmer's head over them
                    if (state.isAir || state.isLiquid) continue
                    spans += point.bottomYBlockPos
                    spans += point.topYBlockPos
                }
                spans.toIntArray()
            }
        }
    }

    @Synchronized
    fun clear() {
        cache?.clear()
        cache = null
        cacheDimension = null
    }

    private fun findLevel(dimension: String): IDhApiLevelWrapper? {
        val world = DhApi.Delayed.worldProxy ?: return null
        if (!world.worldLoaded()) return null
        val candidates = world.allLoadedLevelWrappers.filter { it.dimensionName == dimension }
        // in singleplayer DH has a server and a client wrapper for the same dimension; the client one is what it renders
        return candidates.firstOrNull { it.levelType == EDhApiLevelType.CLIENT_LEVEL } ?: candidates.firstOrNull()
    }
}
