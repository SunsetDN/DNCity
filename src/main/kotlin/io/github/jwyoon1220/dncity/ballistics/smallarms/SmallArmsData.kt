// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

import com.google.gson.Gson
import com.google.gson.JsonElement
import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.neoforge.event.AddReloadListenerEvent

/**
 * `data/dncity/dncity/small_arms/block_materials.json` and `body_armor.json`, reloaded with datapacks (a datapack replaces either
 * by shipping the same id). A file that fails to parse keeps the previous tables and is logged; with no table loaded the bridge
 * leaves TACZ's own behaviour untouched.
 */
object SmallArmsData : SimpleJsonResourceReloadListener(Gson(), "dncity/small_arms") {
    private val BLOCKS_ID = ResourceLocation.fromNamespaceAndPath(Dncity.ID, "block_materials")
    private val ARMOR_ID = ResourceLocation.fromNamespaceAndPath(Dncity.ID, "body_armor")

    @Volatile
    var blocks: BlockMaterialTable? = null
        private set

    @Volatile
    var armor: BodyArmorTable? = null
        private set

    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(this)
    }

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        objects[BLOCKS_ID]?.let { json ->
            runCatching { BlockMaterialTable.fromJson(json.asJsonObject) }
                .onSuccess { blocks = it }
                .onFailure { Dncity.LOGGER.error("Invalid {}: {}", BLOCKS_ID, it.message) }
        }
        objects[ARMOR_ID]?.let { json ->
            runCatching { BodyArmorTable.fromJson(json.asJsonObject) }
                .onSuccess { armor = it }
                .onFailure { Dncity.LOGGER.error("Invalid {}: {}", ARMOR_ID, it.message) }
        }
        Dncity.LOGGER.info("Small-arms tables: blocks {}, body armor {}", blocks != null, armor != null)
    }
}
