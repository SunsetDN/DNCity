// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity

import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper

/**
 * Solid spans of Distant Horizons' terrain columns, for [FarEntityMath.isOccluded]. The only class that touches DH's API;
 * callers load it only after `ModList.isLoaded("distanthorizons")`, so DNCity still runs without DH.
 *
 * One instance per side: the client reads the level DH renders ([EDhApiLevelType.CLIENT_LEVEL]), the server the level DH
 * builds from the real chunks and generates ([EDhApiLevelType.SERVER_LEVEL]; in singleplayer both exist). DH's column lookup
 * blocks on its data provider (`getAsync(...).get()` in `DhApiTerrainDataRepo`), so it must only run on a background
 * thread. It only reads DH's database (`FullDataSourceProviderV2.get` returns an empty source for a missing section), it
 * never queues world generation.
 */
class DhTerrainColumns(private val preferredLevelType: EDhApiLevelType) {
    private var cacheDimension: String? = null
    private var cache: IDhApiTerrainDataCache? = null

    /** A column sampler for [dimension] (`namespace:path`), or null while DH has no world for it. */
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
        return candidates.firstOrNull { it.levelType == preferredLevelType } ?: candidates.firstOrNull()
    }
}
