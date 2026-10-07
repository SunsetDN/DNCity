// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import java.util.Locale

enum class MaterialClass { METAL, CERAMIC, POLYMER, ELASTOMER, COMPOSITE, GLASS, EARTH, AIR }

/** Whether a layer of this material sheds fragments on its exit side, and how many relative to a plain steel plate. */
data class SpallSpec(val enabled: Boolean, val coefficient: Double)

/**
 * What a material *is*: physical properties and a classification. It deliberately holds no armor effectiveness number.
 * How well it stops a given kind of penetrator is [resistance]: for each terminal model id, the id of a [ResistancePreset]
 * (a curve or parameter set owned by that solver). RHA equivalence depends on velocity, angle, diameter and L/D, so it is a
 * function that the solver evaluates, never one constant on the material.
 *
 * ERA, NERA and spaced armor are not materials, see [ArmorConstruction].
 */
data class ArmorMaterial(
    val id: ResourceLocation,
    val materialClass: MaterialClass,
    val densityKgM3: Double,
    val hardnessBhn: Double?,
    val resistance: Map<ResourceLocation, ResourceLocation>,
    val spall: SpallSpec,
) {
    /** Preset this material uses against the given penetrator model, if it defines one. */
    fun presetFor(terminalModel: ResourceLocation): ResourceLocation? = resistance[terminalModel]
}

object ArmorMaterialRegistry : JsonDataRegistry<ArmorMaterial>("dncity/armor_materials", "armor materials") {
    override fun parse(id: ResourceLocation, json: JsonObject): ArmorMaterial {
        val cls = json.get("type")?.asString?.uppercase(Locale.ROOT)?.let { raw ->
            MaterialClass.entries.firstOrNull { it.name == raw } ?: throw IllegalArgumentException("unknown type '$raw'")
        } ?: MaterialClass.METAL
        val resistance = HashMap<ResourceLocation, ResourceLocation>()
        json.getAsJsonObject("resistance")?.entrySet()?.forEach { (model, preset) ->
            resistance[ResourceLocation.parse(model)] = ResourceLocation.parse(preset.asString)
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
