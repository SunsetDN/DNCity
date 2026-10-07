// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import kotlin.math.abs

/**
 * Data range of the one axis the calibration data actually varies (plate thickness). [extrapolationMargin] is a fraction of the
 * bound: a value within `[min*(1-margin), max*(1+margin)]` but outside `[min, max]` is EXTRAPOLATED, anything further is
 * OUT_OF_MODEL. There is no default margin; a preset must state it.
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
 * One calibration of [FullCaliberApModel]: uncapped full-caliber steel AP against one homogeneous RHA, at normal incidence.
 *
 * What the data supports is a single projectile (diameter and mass fixed), a single obliquity (0 degrees) and a range of plate
 * thicknesses. So only [thickness] has a range, and only it can be EXTRAPOLATED. Diameter and mass are *calibrated values*
 * compared with [numericalRelTolerance] (float noise, not generalization); anything else is OUT_OF_MODEL. Obliquity is 0 degrees,
 * with [normalIncidenceToleranceRad] absorbing only the floating-point error of the geometry; it is not a validated range.
 *
 * [calibrationCoefficient] is deliberately not called an armor coefficient: it absorbs armor resistance, projectile construction,
 * the mismatch between [limitDefinition] and what `PERFORATED` means, test method and model error, and is only valid together
 * with [provenance] and [allowedProjectiles].
 *
 * @property allowedProjectiles TEMPORARY: the only projectiles this calibration may be applied to. `ProjectileDefinition` has no
 *   construction field, so "uncapped AP" is stated by listing definitions. TODO: replace with a penetrator-construction taxonomy
 *   (FULL_CALIBER_MONOBLOC, CAPPED_FULL_CALIBER, COMPOSITE_RIGID, SABOT, LONG_ROD, SHAPED_CHARGE, ...); a solver must not
 *   need to know a specific projectile id.
 */
data class FullCaliberApPreset(
    val id: Ident,
    val calibrationCoefficient: Double,
    val profile: DeMarreProfile,
    val thickness: AxisRange,
    val calibratedDiameterM: Double,
    val calibratedMassKg: Double,
    val numericalRelTolerance: Double,
    val normalIncidenceToleranceRad: Double,
    val allowedProjectiles: Set<Ident>,
    val limitDefinition: BallisticLimitDefinition,
    val residual: ResidualStrategy,
    val provenance: CalibrationProvenance,
) {
    init {
        require(calibrationCoefficient > 0.0 && calibrationCoefficient.isFinite()) { "calibration coefficient must be positive and finite" }
        require(calibratedDiameterM > 0.0 && calibratedMassKg > 0.0) { "calibrated diameter and mass must be positive" }
        require(numericalRelTolerance in 0.0..MAX_NUMERICAL_REL_TOLERANCE) { "numerical tolerance must be within 0..$MAX_NUMERICAL_REL_TOLERANCE (it is float noise, not a range)" }
        require(normalIncidenceToleranceRad in 0.0..MAX_NORMAL_INCIDENCE_TOLERANCE_RAD) {
            "normal incidence tolerance must be within 0..${Math.toDegrees(MAX_NORMAL_INCIDENCE_TOLERANCE_RAD)} degrees (float noise, not a range)"
        }
        require(allowedProjectiles.isNotEmpty()) { "a preset must name the projectiles it was calibrated for" }
        require(limitDefinition == provenance.ballisticLimitDefinition) { "preset limit definition must equal the provenance's" }
        require(abs(provenance.projectileMassKg - calibratedMassKg) <= 1e-9 * calibratedMassKg) { "provenance mass must equal the calibrated mass" }
    }

    fun isCalibratedDiameter(diameterM: Double): Boolean = abs(diameterM - calibratedDiameterM) <= numericalRelTolerance * calibratedDiameterM

    fun isCalibratedMass(massKg: Double): Boolean = abs(massKg - calibratedMassKg) <= numericalRelTolerance * calibratedMassKg

    fun ballisticLimitMps(diameterM: Double, thicknessM: Double, massKg: Double): Double =
        calibrationCoefficient * profile.baselineVelocityMps(diameterM, thicknessM, massKg)

    companion object {
        /** Sanity cap on the numerical tolerances, so that a "tolerance" cannot grow into a physical range. */
        const val MAX_NUMERICAL_REL_TOLERANCE = 1e-3
        const val MAX_NORMAL_INCIDENCE_TOLERANCE_RAD = 8.726646259971648e-4 // 0.05 degrees

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
            require(ranges.keySet() == setOf("thickness_m")) {
                "valid_ranges may only contain thickness_m: the data varies nothing else (diameter and mass are 'calibrated' values)"
            }
            val t = ranges.getAsJsonObject("thickness_m")
            fun tNum(f: String) = t.get(f)?.asDouble ?: throw IllegalArgumentException("valid_ranges.thickness_m: missing $f (there is no default extrapolation margin)")
            val calibrated = json.getAsJsonObject("calibrated") ?: throw IllegalArgumentException("missing calibrated")
            fun cNum(f: String) = calibrated.get(f)?.asDouble ?: throw IllegalArgumentException("calibrated: missing $f")
            val residualName = json.get("residual_strategy")?.asString ?: throw IllegalArgumentException("missing residual_strategy")
            val defName = json.get("ballistic_limit_definition")?.asString ?: throw IllegalArgumentException("missing ballistic_limit_definition")
            return FullCaliberApPreset(
                id = id,
                calibrationCoefficient = json.get("calibration_coefficient")?.asDouble ?: throw IllegalArgumentException("missing calibration_coefficient"),
                profile = DeMarreProfile.fromJson(json.getAsJsonObject("de_marre")),
                thickness = AxisRange(tNum("min"), tNum("max"), tNum("extrapolation_margin")),
                calibratedDiameterM = cNum("diameter_m"),
                calibratedMassKg = cNum("mass_kg"),
                numericalRelTolerance = cNum("numerical_tolerance"),
                normalIncidenceToleranceRad = Math.toRadians(json.get("normal_incidence_tolerance_deg")?.asDouble ?: throw IllegalArgumentException("missing normal_incidence_tolerance_deg")),
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
