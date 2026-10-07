// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject

enum class MaterialClass { METAL, CERAMIC, POLYMER, ELASTOMER, COMPOSITE, GLASS, EARTH, AIR }

/** Whether a layer of this material sheds fragments on its exit side, and how many relative to a plain steel plate. */
data class SpallSpec(val enabled: Boolean, val coefficient: Double) {
    init {
        require(coefficient >= 0.0) { "spall coefficient must not be negative" }
    }
}

/**
 * What a material *is*: physical properties and a classification. It deliberately holds no armor effectiveness number.
 * How well it stops a given kind of penetrator is [resistance]: for each terminal model id, the id of a [ResistancePreset]
 * (a parameter set owned by that solver). RHA equivalence depends on velocity, angle, diameter and L/D, so it is a
 * function the solver evaluates, never one constant on the material.
 *
 * Manufacturing (rolled, cast, forged) is not modelled yet; when it is, it is its own axis, not part of the layer shape.
 * ERA, NERA and spaced armor are not materials, see [ArmorConstruction].
 */
data class ArmorMaterial(
    val id: Ident,
    val materialClass: MaterialClass,
    val densityKgM3: Double,
    val hardnessBhn: Double?,
    val resistance: Map<Ident, Ident>,
    val spall: SpallSpec,
) {
    init {
        require(densityKgM3 > 0.0) { "density must be positive" }
    }

    /** Preset this material uses against the given penetrator model, if it defines one. */
    fun presetFor(terminalModel: Ident): Ident? = resistance[terminalModel]

    companion object {
        fun fromJson(id: Ident, json: JsonObject): ArmorMaterial {
            val cls = if (json.has("type")) SiJson.enumOf<MaterialClass>(json, "type") else MaterialClass.METAL
            val resistance = HashMap<Ident, Ident>()
            json.getAsJsonObject("resistance")?.entrySet()?.forEach { (model, preset) ->
                resistance[Ident.parse(model)] = Ident.parse(preset.asString)
            }
            val spall = json.getAsJsonObject("spall")
            return ArmorMaterial(
                id = id,
                materialClass = cls,
                densityKgM3 = SiJson.density(json) ?: throw IllegalArgumentException("missing density_kg_m3 or density_g_cm3"),
                hardnessBhn = json.get("hardness_bhn")?.asDouble,
                resistance = resistance,
                spall = SpallSpec(spall?.get("enabled")?.asBoolean ?: false, spall?.get("coefficient")?.asDouble ?: 1.0),
            )
        }
    }
}
