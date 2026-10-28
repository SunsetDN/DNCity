// AGENT-DONE(claude): oblique-full-caliber-ap
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import kotlin.math.cos
import kotlin.math.pow

/**
 * How an inclined plate is turned into the normal-incidence problem the de Marre calibration knows: the plate behaves like a
 * normal plate of effective thickness `T_eff`, theta from the plate normal.
 *
 * - [GEOMETRIC_LOS]: `T / cos(theta)`, pure geometry (the line-of-sight thickness). No empirical content, no data needed. It is known
 *   to be optimistic for the plate at high angles (real inclined plate is harder to defeat than its line-of-sight thickness says),
 *   and the result carries [ModelDiagnostic.OBLIQUITY_GEOMETRIC_ONLY] so that is never hidden.
 * - [COSINE_POWER]: `T / cos(theta)^n` with `n` from a source the preset names. Exponents are empirical and depend on projectile and
 *   plate; the preset must say where its value comes from. This class does not know any.
 * - [TABULATED]: `T * f(theta, T/d)` from a [SlopeTable] the preset names a source for. Unlike a single exponent it can say that the
 *   same angle costs a projectile more against a plate that is thick relative to its diameter than against a thin one. The angle
 *   range is the table's own and there is no extrapolation in either axis.
 *
 * The valid angle range is data too: [maxAngleRad] is the largest angle the author claims the model for and
 * [extrapolationMarginRad] how far beyond it a result is still returned as EXTRAPOLATED. Beyond that the model declines (unless a
 * [RicochetSpec] says the projectile certainly glances off there), so a steep angle is never "answered" with a penetration number.
 */
data class ObliquitySpec(
    val basis: ObliquityBasis,
    val exponent: Double,
    val maxAngleRad: Double,
    val extrapolationMarginRad: Double,
    val exponentSource: String?,
    val table: SlopeTable? = null,
    val tableSource: String? = null,
) {
    init {
        require(maxAngleRad > 0.0 && maxAngleRad <= MAX_ANGLE_RAD) { "obliquity max angle must be in (0, ${Math.toDegrees(MAX_ANGLE_RAD)}] degrees" }
        require(extrapolationMarginRad >= 0.0 && maxAngleRad + extrapolationMarginRad <= MAX_ANGLE_RAD) { "obliquity range plus margin must stay within ${Math.toDegrees(MAX_ANGLE_RAD)} degrees" }
        if (basis != ObliquityBasis.TABULATED) require(table == null && tableSource == null) { "$basis takes no slope table" }
        when (basis) {
            ObliquityBasis.GEOMETRIC_LOS -> {
                require(exponent == 1.0) { "GEOMETRIC_LOS has exponent 1 by definition" }
                require(exponentSource == null) { "GEOMETRIC_LOS takes no exponent source" }
            }
            ObliquityBasis.COSINE_POWER -> {
                require(exponent in 1.0..MAX_EXPONENT) { "cosine exponent must be in 1..$MAX_EXPONENT" }
                require(!exponentSource.isNullOrBlank()) { "COSINE_POWER needs the source of its exponent" }
            }
            ObliquityBasis.TABULATED -> {
                requireNotNull(table) { "TABULATED needs a slope table" }
                require(!tableSource.isNullOrBlank()) { "TABULATED needs the source of its slope table" }
                require(exponentSource == null) { "TABULATED takes no exponent" }
                require(maxAngleRad == table.maxAngleRad) { "TABULATED: the angle range is the table's own (max_angle_deg must equal its last angle)" }
                require(extrapolationMarginRad == 0.0) { "TABULATED: a table is not extrapolated (extrapolation_margin_deg must be 0)" }
            }
        }
    }

    fun assessAngle(angleRad: Double): ModelRegime = when {
        !angleRad.isFinite() || angleRad < 0.0 -> ModelRegime.OUT_OF_MODEL
        angleRad <= maxAngleRad -> ModelRegime.IN_RANGE
        angleRad <= maxAngleRad + extrapolationMarginRad -> ModelRegime.EXTRAPOLATED
        else -> ModelRegime.OUT_OF_MODEL
    }

    /** Whether the plate/projectile ratio is one this spec can answer for; only a table restricts it. */
    fun coversRatio(thicknessToDiameter: Double): Boolean = table?.coversRatio(thicknessToDiameter) ?: true

    /** Thickness of the equivalent normal plate. Only call within [assessAngle] and [coversRatio]. */
    fun effectiveThicknessM(thicknessM: Double, angleRad: Double, diameterM: Double): Double = when (basis) {
        ObliquityBasis.TABULATED -> thicknessM * requireNotNull(table!!.factor(angleRad, thicknessM / diameterM)) { "outside the slope table" }
        else -> thicknessM / cos(angleRad).pow(exponent)
    }

    companion object {
        /** cos blows up near 90 degrees; no claim is ever made past this. */
        const val MAX_ANGLE_RAD = 1.3962634015954636 // 80 degrees
        const val MAX_EXPONENT = 2.0

        fun fromJson(json: JsonObject): ObliquitySpec {
            json.get("basis")?.asString?.let { b -> require(b == "COSINE_POWER" || !json.has("exponent")) { "obliquity: $b takes no exponent" } }
            val allowed = setOf("basis", "exponent", "exponent_source", "max_angle_deg", "extrapolation_margin_deg", "table", "table_source")
            require(json.keySet().all { it in allowed }) { "obliquity: unknown key(s) ${json.keySet() - allowed}" }
            val basis = json.get("basis")?.asString?.let { n -> ObliquityBasis.entries.firstOrNull { it.name == n } ?: throw IllegalArgumentException("obliquity: unknown basis '$n'") }
                ?: throw IllegalArgumentException("obliquity: missing basis")
            fun num(k: String) = json.get(k)?.asDouble ?: throw IllegalArgumentException("obliquity: missing $k (there is no default angle range)")
            return ObliquitySpec(
                basis = basis,
                exponent = if (basis == ObliquityBasis.COSINE_POWER) num("exponent") else 1.0,
                maxAngleRad = Math.toRadians(num("max_angle_deg")),
                extrapolationMarginRad = Math.toRadians(num("extrapolation_margin_deg")),
                exponentSource = json.get("exponent_source")?.asString,
                table = json.getAsJsonObject("table")?.let { SlopeTable.fromJson(it) },
                tableSource = json.get("table_source")?.asString,
            )
        }
    }
}

enum class ObliquityBasis { GEOMETRIC_LOS, COSINE_POWER, TABULATED }
