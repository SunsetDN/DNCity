// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject

/**
 * How one material resists one kind of penetrator, as data the solver [solver] reads. The core never interprets
 * [parameters] or [curves]: they belong to that solver, which is what keeps a single generic "efficiency" number from
 * growing here. [ArmorMaterial.resistance] must point a terminal model at a preset of that same solver (checked by
 * [BallisticsValidation]).
 *
 * A preset of the baseline material of a solver (RHA) may be empty: the solver's formulas are calibrated against it.
 *
 * @property curves optional named tabulated curves, each a list of `[x, y]` points, for effects that are not a formula
 */
data class ResistancePreset(
    val id: Ident,
    val solver: Ident,
    val parameters: Map<String, Double>,
    val curves: Map<String, List<DoubleArray>>,
) {
    companion object {
        fun fromJson(id: Ident, json: JsonObject): ResistancePreset {
            val parameters = HashMap<String, Double>()
            json.getAsJsonObject("parameters")?.entrySet()?.forEach { (k, v) -> parameters[k] = v.asDouble }
            val curves = HashMap<String, List<DoubleArray>>()
            json.getAsJsonObject("curves")?.entrySet()?.forEach { (name, points) ->
                curves[name] = points.asJsonArray.map { p ->
                    val pair = p.asJsonArray
                    require(pair.size() == 2) { "curve $name: every point must be [x, y]" }
                    doubleArrayOf(pair[0].asDouble, pair[1].asDouble)
                }
            }
            val solver = json.get("solver")?.asString ?: throw IllegalArgumentException("missing solver")
            return ResistancePreset(id, Ident.parse(solver), parameters, curves)
        }
    }
}
