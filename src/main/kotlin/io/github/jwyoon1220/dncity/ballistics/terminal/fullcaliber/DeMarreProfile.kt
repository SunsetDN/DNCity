// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import kotlin.math.pow

/**
 * The fixed *form* of the de Marre relation, `V = C * d^pd * T^pT * m^-pm`, in SI (d, T in m, m in kg, V in m/s).
 * The exponents are empirical and come from data (the 1937 US Navy manual's nickel-steel values are 0.75 / 0.70 / 0.50);
 * nothing here is fitted, and with a single calibration projectile the diameter and mass exponents cannot be tested.
 */
data class DeMarreProfile(
    val diameterExponent: Double,
    val thicknessExponent: Double,
    val massExponent: Double,
    val baselineConstantSi: Double,
) {
    init {
        require(diameterExponent > 0.0 && thicknessExponent > 0.0 && massExponent > 0.0) { "de Marre exponents must be positive" }
        require(baselineConstantSi > 0.0 && baselineConstantSi.isFinite()) { "de Marre constant must be positive and finite" }
    }

    fun baselineVelocityMps(diameterM: Double, thicknessM: Double, massKg: Double): Double =
        baselineConstantSi * diameterM.pow(diameterExponent) * thicknessM.pow(thicknessExponent) * massKg.pow(-massExponent)

    companion object {
        private const val FT_M = 0.3048
        private const val IN_M = 0.0254
        private const val LB_KG = 0.45359237

        /** Constant given for ft/s with d and T in inches and m in pounds, converted so that no solver sees imperial units. */
        fun fromImperial(diameterExponent: Double, thicknessExponent: Double, massExponent: Double, constantFtsInLb: Double): DeMarreProfile =
            DeMarreProfile(
                diameterExponent, thicknessExponent, massExponent,
                FT_M * constantFtsInLb * IN_M.pow(-(diameterExponent + thicknessExponent)) * LB_KG.pow(massExponent),
            )

        fun fromJson(json: JsonObject?): DeMarreProfile {
            requireNotNull(json) { "preset has no de_marre block" }
            fun num(k: String) = json.get(k)?.asDouble ?: throw IllegalArgumentException("de_marre: missing $k")
            val units = json.get("constant_units")?.asString ?: throw IllegalArgumentException("de_marre: missing constant_units")
            val constant = if (json.has("constant_log10")) 10.0.pow(num("constant_log10")) else num("constant")
            return when (units) {
                "SI" -> DeMarreProfile(num("diameter_exponent"), num("thickness_exponent"), num("mass_exponent"), constant)
                "IMPERIAL_FTS_IN_LB" -> fromImperial(num("diameter_exponent"), num("thickness_exponent"), num("mass_exponent"), constant)
                else -> throw IllegalArgumentException("de_marre: unknown constant_units '$units'")
            }
        }
    }
}
