// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import java.util.Locale

// Three separate things, never merged:
//   ArmorMaterial     what a substance is (density, hardness, how it resists a penetrator model)     -> ArmorMaterial.kt
//   ArmorLayer        a material with a real thickness and a shape                                    -> here
//   ArmorConstruction layers, gaps and interactions (ERA, NERA) in order; ArmorStack is one ray's view -> here

enum class LayerShape { FLAT, CURVED, CAST }

/** Where a layer sits in the assembly. A [LINER] is the spall liner behind the armor, [STRUCTURE] is hull skin that is not meant as armor. */
enum class LayerRole { ARMOR, STRUCTURE, BACKING, LINER }

data class LayerGeometry(val shape: LayerShape, val radiusM: Double?) {
    companion object {
        val FLAT = LayerGeometry(LayerShape.FLAT, null)
    }
}

/**
 * One slab of one material. [thicknessM] is the real (normal) thickness; line-of-sight thickness depends on the impact angle
 * and is [ImpactAngle.losThicknessM]'s business, never stored.
 */
data class ArmorLayer(
    val materialId: ResourceLocation,
    val thicknessM: Double,
    val geometry: LayerGeometry,
    val role: LayerRole,
)

/**
 * What an interaction armor does to a penetrator, data for the [ArmorEffectModel] named by [solver]. ERA and NERA are
 * effects; the layers they sit between are ordinary [ArmorLayer]s.
 *
 * @property singleUse a tile that has worked is spent (ERA); the spent state is runtime state of the vehicle, not of this data
 */
data class ArmorEffectSpec(
    val id: ResourceLocation,
    val solver: ResourceLocation,
    val parameters: Map<String, Double>,
    val singleUse: Boolean,
)

/** One step along a ray through armor. */
sealed interface ArmorElement {
    /** A single solid layer. */
    data class Solid(val layer: ArmorLayer) : ArmorElement

    /** A gap (spaced armor, an engine bay). [fillMaterialId] null means air. */
    data class Gap(val distanceM: Double, val fillMaterialId: ResourceLocation?) : ArmorElement

    /**
     * Layers that only work *together*, plus the interaction they cause (ERA: plate, explosive, plate; NERA: plate,
     * elastomer, plate). The effect acts on the projectile first, the layers are then traversed as usual with what is left.
     */
    data class EffectPackage(val layers: List<ArmorLayer>, val effectId: ResourceLocation) : ArmorElement

    /** Empty space behind the armor, where modules and crew are (the post-penetration solver works here). */
    data class InternalSpace(val depthM: Double) : ArmorElement
}

/** The declared composition of one piece of armor, from the outside in. Static data. */
data class ArmorConstruction(val id: ResourceLocation, val elements: List<ArmorElement>) {
    /** The view of one ray: the same elements plus the angle the ray meets the outer surface at (from the surface normal). */
    fun stack(angleFromNormalRad: Double): ArmorStack = ArmorStack(elements, angleFromNormalRad)
}

/** What one ray traverses, in order. The solver pipeline walks it element by element, carrying a [ProjectileState]. */
data class ArmorStack(val elements: List<ArmorElement>, val angleFromNormalRad: Double)

object ArmorEffectRegistry : JsonDataRegistry<ArmorEffectSpec>("dncity/armor_effects", "armor effects") {
    override fun parse(id: ResourceLocation, json: JsonObject): ArmorEffectSpec {
        val parameters = HashMap<String, Double>()
        json.getAsJsonObject("parameters")?.entrySet()?.forEach { (k, v) -> parameters[k] = v.asDouble }
        return ArmorEffectSpec(
            id = id,
            solver = ResourceLocation.parse(json.get("solver")?.asString ?: throw IllegalArgumentException("missing solver")),
            parameters = parameters,
            singleUse = json.get("single_use")?.asBoolean ?: false,
        )
    }
}

object ArmorConstructionRegistry : JsonDataRegistry<ArmorConstruction>("dncity/armor_constructions", "armor constructions") {
    override fun parse(id: ResourceLocation, json: JsonObject): ArmorConstruction {
        val elements = json.getAsJsonArray("elements")?.map { parseElement(it.asJsonObject) }
            ?: throw IllegalArgumentException("missing elements")
        require(elements.isNotEmpty()) { "elements must not be empty" }
        return ArmorConstruction(id, elements)
    }

    private fun parseElement(json: JsonObject): ArmorElement = when (val type = json.get("type")?.asString) {
        "solid" -> ArmorElement.Solid(parseLayer(json))
        "gap" -> ArmorElement.Gap(
            SiJson.requireLength(json, "distance"),
            json.get("fill")?.asString?.let(ResourceLocation::parse),
        )
        "effect_package" -> ArmorElement.EffectPackage(
            json.getAsJsonArray("layers").map { parseLayer(it.asJsonObject) },
            ResourceLocation.parse(json.get("effect").asString),
        )
        "internal_space" -> ArmorElement.InternalSpace(SiJson.requireLength(json, "depth"))
        else -> throw IllegalArgumentException("unknown element type '$type'")
    }

    private fun parseLayer(json: JsonObject): ArmorLayer {
        val shape = json.get("shape")?.asString?.uppercase(Locale.ROOT)?.let { raw ->
            LayerShape.entries.firstOrNull { it.name == raw } ?: throw IllegalArgumentException("unknown shape '$raw'")
        } ?: LayerShape.FLAT
        val role = json.get("role")?.asString?.uppercase(Locale.ROOT)?.let { raw ->
            LayerRole.entries.firstOrNull { it.name == raw } ?: throw IllegalArgumentException("unknown role '$raw'")
        } ?: LayerRole.ARMOR
        val thickness = SiJson.requireLength(json, "thickness")
        require(thickness > 0.0) { "thickness must be positive" }
        return ArmorLayer(
            materialId = ResourceLocation.parse(json.get("material")?.asString ?: throw IllegalArgumentException("layer without material")),
            thicknessM = thickness,
            geometry = LayerGeometry(shape, SiJson.length(json, "radius")),
            role = role,
        )
    }
}
