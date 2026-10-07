// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject

// Three separate things, never merged:
//   ArmorMaterial     what a substance is (density, hardness, how it resists a penetrator model)      -> ArmorMaterial.kt
//   ArmorLayer        a material with a real thickness and a shape                                     -> here
//   ArmorConstruction layers, gaps and interactions (ERA, NERA) in order, a *definition*; what one ray
//                     meets is an ArmorStack; what has been used up on one vehicle is ArmorRuntimeState -> here / ArmorRuntime.kt

/** Shape of a layer's surface. How it was made (rolled, cast, ...) is a different axis and not modelled yet. */
enum class LayerShape { FLAT, CURVED }

/** Where a layer sits in the assembly. A [LINER] is the spall liner behind the armor, [STRUCTURE] is hull skin that is not meant as armor. */
enum class LayerRole { ARMOR, STRUCTURE, BACKING, LINER }

data class LayerGeometry(val shape: LayerShape, val radiusM: Double?) {
    init {
        require(shape != LayerShape.CURVED || (radiusM != null && radiusM > 0.0)) { "a curved layer needs a positive radius" }
    }

    companion object {
        val FLAT = LayerGeometry(LayerShape.FLAT, null)
    }
}

/**
 * One slab of one material. [thicknessM] is the real (normal) thickness; line-of-sight thickness depends on the impact angle
 * and is [ImpactAngle.losThicknessM]'s business, never stored.
 */
data class ArmorLayer(
    val materialId: Ident,
    val thicknessM: Double,
    val geometry: LayerGeometry,
    val role: LayerRole,
) {
    init {
        require(thicknessM > 0.0 && thicknessM.isFinite()) { "layer thickness must be positive and finite: $thicknessM" }
    }

    companion object {
        fun fromJson(json: JsonObject): ArmorLayer = ArmorLayer(
            materialId = Ident.parse(json.get("material")?.asString ?: throw IllegalArgumentException("layer without material")),
            thicknessM = SiJson.requireLength(json, "thickness"),
            geometry = LayerGeometry(
                if (json.has("shape")) SiJson.enumOf<LayerShape>(json, "shape") else LayerShape.FLAT,
                SiJson.length(json, "radius"),
            ),
            role = if (json.has("role")) SiJson.enumOf<LayerRole>(json, "role") else LayerRole.ARMOR,
        )
    }
}

/**
 * What an interaction armor does to a penetrator, data for the [ArmorEffectModel] named by [solver]. ERA and NERA are
 * effects; the layers they sit between are ordinary [ArmorLayer]s.
 *
 * @property singleUse a tile that has worked is spent (ERA). Only the *rule* is data here; whether a particular tile on a
 *   particular vehicle is spent is [ArmorRuntimeState].
 */
data class ArmorEffectSpec(
    val id: Ident,
    val solver: Ident,
    val parameters: Map<String, Double>,
    val singleUse: Boolean,
) {
    companion object {
        fun fromJson(id: Ident, json: JsonObject): ArmorEffectSpec {
            val parameters = HashMap<String, Double>()
            json.getAsJsonObject("parameters")?.entrySet()?.forEach { (k, v) -> parameters[k] = v.asDouble }
            return ArmorEffectSpec(
                id = id,
                solver = Ident.parse(json.get("solver")?.asString ?: throw IllegalArgumentException("missing solver")),
                parameters = parameters,
                singleUse = json.get("single_use")?.asBoolean ?: false,
            )
        }
    }
}

/** One step along a ray through armor. */
sealed interface ArmorElement {
    /** A single solid layer. */
    data class Solid(val layer: ArmorLayer) : ArmorElement

    /**
     * A gap (spaced armor, an engine bay). [distanceM] is the separation measured along the surface normal, a real SI
     * distance: the ray crosses it obliquely, so the path it flies is `distanceM / cos(angle)`, and that path counts towards
     * the traversal distance, the fuze's arming distance and every later effect that depends on flight. [fillMaterialId] null means air.
     */
    data class Gap(val distanceM: Double, val fillMaterialId: Ident?) : ArmorElement {
        init {
            require(distanceM > 0.0 && distanceM.isFinite()) { "gap distance must be positive and finite: $distanceM" }
        }
    }

    /**
     * Layers that only work *together*, plus the interaction they cause (ERA: plate, explosive, plate; NERA: plate,
     * elastomer, plate). When the effect acts is the effect model's business, see [EffectPhase].
     */
    data class EffectPackage(val layers: List<ArmorLayer>, val effectId: Ident) : ArmorElement {
        init {
            require(layers.isNotEmpty()) { "an effect package needs at least one layer" }
        }
    }

    /** Empty space behind the armor, where modules and crew are. Reaching it ends the armor traversal, see [TraversalOutcome]. */
    data class InternalSpace(val depthM: Double) : ArmorElement {
        init {
            require(depthM > 0.0 && depthM.isFinite()) { "internal space depth must be positive and finite: $depthM" }
        }
    }
}

/** The declared composition of one piece of armor, from the outside in. Static *definition*, shared by every vehicle that has it. */
data class ArmorConstruction(val id: Ident, val elements: List<ArmorElement>) {
    init {
        require(elements.any { it !is ArmorElement.InternalSpace }) { "a construction needs at least one armor element" }
        val internal = elements.indexOfFirst { it is ArmorElement.InternalSpace }
        require(internal < 0 || internal == elements.lastIndex) { "internal space must be the last element" }
    }

    /** The view of one ray: the elements plus the surface they sit on. */
    fun stack(surface: SurfaceModel): ArmorStack = ArmorStack(id, elements, surface)

    companion object {
        fun fromJson(id: Ident, json: JsonObject): ArmorConstruction {
            val elements = json.getAsJsonArray("elements")?.map { parseElement(it.asJsonObject) }
                ?: throw IllegalArgumentException("missing elements")
            return ArmorConstruction(id, elements)
        }

        private fun parseElement(json: JsonObject): ArmorElement = when (val type = json.get("type")?.asString) {
            "solid" -> ArmorElement.Solid(ArmorLayer.fromJson(json))
            "gap" -> ArmorElement.Gap(SiJson.requireLength(json, "distance"), json.get("fill")?.asString?.let { Ident.parse(it) })
            "effect_package" -> ArmorElement.EffectPackage(
                json.getAsJsonArray("layers")?.map { ArmorLayer.fromJson(it.asJsonObject) } ?: emptyList(),
                Ident.parse(json.get("effect")?.asString ?: throw IllegalArgumentException("effect_package without effect")),
            )
            "internal_space" -> ArmorElement.InternalSpace(SiJson.requireLength(json, "depth"))
            else -> throw IllegalArgumentException("unknown element type '$type'")
        }
    }
}

/**
 * Where the surface of each element is and which way it faces. The impact angle on an element is derived from the
 * projectile's direction at that moment and [normalAt], so it can differ from element to element (curved plates, a deflected
 * projectile, a tumbling rod).
 */
fun interface SurfaceModel {
    /** Unit normal pointing out of the armor, towards the side the projectile comes from, at [point] on element [elementIndex]. */
    fun normalAt(elementIndex: Int, point: V3): V3
}

/** A flat stack of parallel layers sharing one normal. */
class UniformSurface(normal: V3) : SurfaceModel {
    private val n = normal.normalize().also { require(it.lengthSqr() > 0.0) { "surface normal must not be zero" } }
    override fun normalAt(elementIndex: Int, point: V3): V3 = n
}

/** What one ray traverses, in order, on a given surface, for the effects of construction [constructionId]. */
data class ArmorStack(val constructionId: Ident, val elements: List<ArmorElement>, val surface: SurfaceModel)
