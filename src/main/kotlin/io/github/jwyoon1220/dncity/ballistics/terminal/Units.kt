// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonObject
import java.util.Locale

/**
 * Internal unit system of terminal ballistics is SI: metres, kilograms, seconds, kg/m^3, joules.
 * JSON may be written in what people find easy (mm, g); [SiJson] converts while loading, so no solver ever sees mm or g.
 */
object Units {
    fun mmToM(mm: Double): Double = mm / 1000.0
    fun gToKg(g: Double): Double = g / 1000.0
    fun gPerCm3ToKgM3(g: Double): Double = g * 1000.0
}

/**
 * Angle convention of the whole pipeline: an impact angle is measured **from the surface normal**.
 * 0 degrees is a perpendicular hit, 60 degrees is 60 degrees off the normal, 90 degrees grazes the surface.
 *
 * An angle is never stored on armor: it is derived from the projectile's current direction and the local surface normal
 * every time a layer is met (see [ImpactContext]), so deflection, curved surfaces and yawed rods stay possible.
 */
object ImpactAngle {
    /** Line-of-sight thickness of a plate. Geometry only: this is not a resistance, solvers add their own obliquity effects. */
    fun losThicknessM(thicknessM: Double, angleFromNormalRad: Double): Double {
        val c = kotlin.math.cos(angleFromNormalRad)
        return if (c <= 1e-6) Double.POSITIVE_INFINITY else thicknessM / c
    }

    /** Angle between the direction of flight and the outward surface normal, 0 = head-on. */
    fun fromNormal(direction: V3, outwardNormal: V3): Double {
        val d = direction.normalize()
        val n = outwardNormal.normalize()
        return kotlin.math.acos((-d.dot(n)).coerceIn(-1.0, 1.0))
    }
}

/** Reads a quantity that may be given in SI (`<name>_m`, `<name>_kg`) or in the friendly unit (`<name>_mm`, `<name>_g`). */
internal object SiJson {
    fun length(json: JsonObject, name: String): Double? = when {
        json.has("${name}_m") -> json.get("${name}_m").asDouble
        json.has("${name}_mm") -> Units.mmToM(json.get("${name}_mm").asDouble)
        else -> null
    }

    fun mass(json: JsonObject, name: String): Double? = when {
        json.has("${name}_kg") -> json.get("${name}_kg").asDouble
        json.has("${name}_g") -> Units.gToKg(json.get("${name}_g").asDouble)
        else -> null
    }

    fun density(json: JsonObject, name: String = "density"): Double? = when {
        json.has("${name}_kg_m3") -> json.get("${name}_kg_m3").asDouble
        json.has("${name}_g_cm3") -> Units.gPerCm3ToKgM3(json.get("${name}_g_cm3").asDouble)
        else -> null
    }

    fun requireLength(json: JsonObject, name: String): Double =
        length(json, name) ?: throw IllegalArgumentException("missing ${name}_m or ${name}_mm")

    fun requireMass(json: JsonObject, name: String): Double =
        mass(json, name) ?: throw IllegalArgumentException("missing ${name}_kg or ${name}_g")

    inline fun <reified E : Enum<E>> enumOf(json: JsonObject, name: String): E {
        val raw = json.get(name)?.asString ?: throw IllegalArgumentException("missing $name")
        return enumValues<E>().firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
            ?: throw IllegalArgumentException("unknown $name '$raw'")
    }
}
