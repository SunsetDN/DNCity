// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation

/**
 * A named parameter set that one penetrator model reads when it meets a material (`dncity:rha_ap`, `dncity:rha_long_rod`,
 * `dncity:rha_ce`). The core does not interpret [params]; the model that owns the preset does, so a new model needs no
 * change here and a material can be tuned per penetrator without touching its physical data.
 *
 * @property curves optional named tabulated curves, each a list of `[x, y]` points, for effects that are not a formula
 */
data class ResistancePreset(
    val id: ResourceLocation,
    val params: Map<String, Double>,
    val curves: Map<String, List<DoubleArray>>,
)

object ResistancePresetRegistry : JsonDataRegistry<ResistancePreset>("dncity/resistance_presets", "resistance presets") {
    override fun parse(id: ResourceLocation, json: JsonObject): ResistancePreset {
        val params = HashMap<String, Double>()
        json.getAsJsonObject("params")?.entrySet()?.forEach { (k, v) -> params[k] = v.asDouble }
        val curves = HashMap<String, List<DoubleArray>>()
        json.getAsJsonObject("curves")?.entrySet()?.forEach { (name, points) ->
            curves[name] = points.asJsonArray.map { p ->
                val pair = p.asJsonArray
                require(pair.size() == 2) { "curve $name: every point must be [x, y]" }
                doubleArrayOf(pair[0].asDouble, pair[1].asDouble)
            }
        }
        return ResistancePreset(id, params, curves)
    }
}
