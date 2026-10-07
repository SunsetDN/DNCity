// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident

/**
 * Data range of one calibrated axis. [extrapolationMargin] is a fraction of the bound: a value within
 * `[min*(1-margin), max*(1+margin)]` but outside `[min, max]` is EXTRAPOLATED, anything further is OUT_OF_MODEL.
 * There is no default margin; a preset must state it.
 */
data class AxisRange(val min: Double, val max: Double, val extrapolationMargin: Double) {
    init {
        require(min > 0.0 && max >= min && min.isFinite() && max.isFinite()) { "axis range must satisfy 0 < min <= max" }
        require(extrapolationMargin >= 0.0 && extrapolationMargin.isFinite()) { "extrapolation margin must not be negative" }
    }

    fun assess(value: Double): ModelRegime = when {
        !value.isFinite() -> ModelRegime.OUT_OF_MODEL
        value in min..max -> ModelRegime.IN_RANGE
        value in (min * (1.0 - extrapolationMargin))..(max * (1.0 + extrapolationMargin)) -> ModelRegime.EXTRAPOLATED
        else -> ModelRegime.OUT_OF_MODEL
    }
}

/**
 * One calibration of [FullCaliberApModel]: uncapped full-caliber steel AP against one homogeneous RHA, normal impact only.
 *
 * [calibrationCoefficient] is deliberately not called an armor coefficient: it absorbs armor resistance, projectile construction,
 * the mismatch between [limitDefinition] and what `PERFORATED` means, test method and model error, and is only valid together
 * with [provenance] and [allowedProjectiles].
 *
 * @property allowedProjectiles the only projectiles this calibration may be applied to (`ProjectileDefinition` has no family field, so
 *   "uncapped AP" is stated by listing the definitions it was calibrated for)
 * @property normalToleranceRad largest impact angle from the surface normal still treated as a normal impact
 */
data class FullCaliberApPreset(
    val id: Ident,
    val calibrationCoefficient: Double,
    val profile: DeMarreProfile,
    val thickness: AxisRange,
    val diameter: AxisRange,
    val mass: AxisRange,
    val diameterOverThickness: AxisRange,
    val normalToleranceRad: Double,
    val allowedProjectiles: Set<Ident>,
    val limitDefinition: BallisticLimitDefinition,
    val residual: ResidualStrategy,
    val provenance: CalibrationProvenance,
) {
    init {
        require(calibrationCoefficient > 0.0 && calibrationCoefficient.isFinite()) { "calibration coefficient must be positive and finite" }
        require(normalToleranceRad in 0.0..MAX_NORMAL_TOLERANCE_RAD) { "normal tolerance must be within 0..${Math.toDegrees(MAX_NORMAL_TOLERANCE_RAD)} degrees" }
        require(allowedProjectiles.isNotEmpty()) { "a preset must name the projectiles it was calibrated for" }
        require(limitDefinition == provenance.ballisticLimitDefinition) { "preset limit definition must equal the provenance's" }
    }

    fun ballisticLimitMps(diameterM: Double, thicknessM: Double, massKg: Double): Double =
        calibrationCoefficient * profile.baselineVelocityMps(diameterM, thicknessM, massKg)

    companion object {
        /** Hard scope of the solver: normal impact only. A preset cannot widen it. */
        const val MAX_NORMAL_TOLERANCE_RAD = 0.017453292519943295 // 1 degree

        /**
         * @param requireVerifiedMass a preset that ships with the mod must rest on a mass confirmed by two independent sources;
         *   only tests and calibration tooling may turn this off
         */
        fun fromJson(id: Ident, json: JsonObject, requireVerifiedMass: Boolean = true): FullCaliberApPreset {
            require(!json.has("armor_coefficient")) { "'armor_coefficient' is not a valid field: the value is a calibration_coefficient" }
            val provenance = CalibrationProvenance.fromJson(json.getAsJsonObject("provenance"))
            if (requireVerifiedMass) {
                require(provenance.massVerification == MassVerification.TWO_INDEPENDENT_SOURCES) {
                    "projectile mass is ${provenance.massVerification}; a production preset needs TWO_INDEPENDENT_SOURCES"
                }
            }
            val ranges = json.getAsJsonObject("valid_ranges") ?: throw IllegalArgumentException("missing valid_ranges")
            fun range(k: String): AxisRange {
                val o = ranges.getAsJsonObject(k) ?: throw IllegalArgumentException("valid_ranges: missing $k")
                fun num(f: String) = o.get(f)?.asDouble ?: throw IllegalArgumentException("valid_ranges.$k: missing $f (there is no default extrapolation margin)")
                return AxisRange(num("min"), num("max"), num("extrapolation_margin"))
            }
            val residualName = json.get("residual_strategy")?.asString ?: throw IllegalArgumentException("missing residual_strategy")
            val defName = json.get("ballistic_limit_definition")?.asString ?: throw IllegalArgumentException("missing ballistic_limit_definition")
            return FullCaliberApPreset(
                id = id,
                calibrationCoefficient = json.get("calibration_coefficient")?.asDouble ?: throw IllegalArgumentException("missing calibration_coefficient"),
                profile = DeMarreProfile.fromJson(json.getAsJsonObject("de_marre")),
                thickness = range("thickness_m"),
                diameter = range("diameter_m"),
                mass = range("mass_kg"),
                diameterOverThickness = range("diameter_over_thickness"),
                normalToleranceRad = Math.toRadians(json.get("normal_tolerance_deg")?.asDouble ?: throw IllegalArgumentException("missing normal_tolerance_deg")),
                allowedProjectiles = json.getAsJsonArray("allowed_projectiles")?.mapTo(LinkedHashSet()) { Ident.parse(it.asString) }
                    ?: throw IllegalArgumentException("missing allowed_projectiles"),
                limitDefinition = BallisticLimitDefinition.entries.firstOrNull { it.name == defName }
                    ?: throw IllegalArgumentException("unknown ballistic_limit_definition '$defName'"),
                residual = ResidualStrategy.entries.firstOrNull { it.name == residualName }
                    ?: throw IllegalArgumentException("unknown or unsupported residual_strategy '$residualName'"),
                provenance = provenance,
            )
        }
    }
}
