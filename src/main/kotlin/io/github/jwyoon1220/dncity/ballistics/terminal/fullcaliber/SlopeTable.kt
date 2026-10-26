// AGENT-DONE(claude): slope-ricochet-tables
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject

/**
 * Obliquity as data: the factor `f = T_eff / T` that turns an inclined plate of thickness `T` into the equivalent normal plate the
 * de Marre calibration knows, tabulated over two axes: the angle from the plate normal and the ratio plate thickness / projectile
 * diameter (`T/d`). The second axis exists because the obliquity effect of a full-caliber projectile depends strongly on how thick
 * the plate is relative to the projectile (thin plates relative to the projectile are "overmatched"); a single cosine exponent
 * cannot express that. `f` includes the line-of-sight part: pure geometry would be `1 / cos(theta)`.
 *
 * Rules (checked here, so a data pack cannot load a table that breaks them):
 * - rows are strictly ascending in `T/d`, at least two of them (one row would claim the same effect for every ratio);
 * - every row uses the same angle grid, strictly ascending, starting at 0 degrees and not beyond [ObliquitySpec.MAX_ANGLE_RAD];
 * - `f = 1` at 0 degrees (that is the definition of the equivalent normal plate), `f >= 1` and non-decreasing with the angle.
 *
 * Lookup is bilinear: linear in the angle within a row, then linear in `T/d` between rows. Nothing outside the table is answered:
 * a ratio or angle outside its axis gives `null` and the model declines. Which source the numbers come from is
 * [ObliquitySpec.tableSource]; this class knows no values.
 */
class SlopeTable(
    val thicknessToDiameter: List<Double>,
    val anglesRad: List<Double>,
    /** `factors[row][angle]`. */
    val factors: List<List<Double>>,
) {
    init {
        require(thicknessToDiameter.size >= 2) { "slope table needs at least two thickness/diameter rows" }
        require(thicknessToDiameter.all { it.isFinite() && it > 0.0 }) { "slope table thickness/diameter values must be positive" }
        require(thicknessToDiameter.zipWithNext().all { (a, b) -> b > a }) { "slope table thickness/diameter rows must be strictly ascending" }
        require(anglesRad.size >= 2) { "slope table needs at least two angles" }
        require(anglesRad.first() == 0.0) { "slope table angles must start at 0 degrees" }
        require(anglesRad.zipWithNext().all { (a, b) -> b > a }) { "slope table angles must be strictly ascending" }
        require(anglesRad.last() <= ObliquitySpec.MAX_ANGLE_RAD) { "slope table angles must not exceed ${Math.toDegrees(ObliquitySpec.MAX_ANGLE_RAD)} degrees" }
        require(factors.size == thicknessToDiameter.size) { "slope table needs one factor row per thickness/diameter value" }
        factors.forEachIndexed { i, row ->
            val r = thicknessToDiameter[i]
            require(row.size == anglesRad.size) { "slope table row $r has ${row.size} factors for ${anglesRad.size} angles" }
            require(row.all { it.isFinite() && it >= 1.0 }) { "slope table row $r: factors must be >= 1" }
            require(row.first() == 1.0) { "slope table row $r: the factor at 0 degrees must be exactly 1" }
            require(row.zipWithNext().all { (a, b) -> b >= a }) { "slope table row $r: factors must not decrease with the angle" }
        }
    }

    val maxAngleRad: Double get() = anglesRad.last()

    fun coversRatio(ratio: Double): Boolean = ratio.isFinite() && ratio >= thicknessToDiameter.first() && ratio <= thicknessToDiameter.last()

    /** `T_eff / T`, or null when ([angleRad], [ratio]) is outside the table. */
    fun factor(angleRad: Double, ratio: Double): Double? {
        if (!angleRad.isFinite() || angleRad < 0.0 || angleRad > maxAngleRad || !coversRatio(ratio)) return null
        val (r0, rt) = bracket(thicknessToDiameter, ratio)
        val (a0, at) = bracket(anglesRad, angleRad)
        fun row(i: Int) = factors[i][a0] + (factors[i][a0 + 1] - factors[i][a0]) * at
        return row(r0) + (row(r0 + 1) - row(r0)) * rt
    }

    companion object {
        /** Index of the lower grid point and the fraction towards the next one; [x] is known to be inside [grid]. */
        internal fun bracket(grid: List<Double>, x: Double): Pair<Int, Double> {
            val i = (0 until grid.size - 1).firstOrNull { x <= grid[it + 1] } ?: (grid.size - 2)
            return i to (x - grid[i]) / (grid[i + 1] - grid[i])
        }

        fun fromJson(json: JsonObject): SlopeTable {
            val allowed = setOf("thickness_to_diameter", "angles_deg", "factors")
            require(json.keySet().all { it in allowed }) { "slope table: unknown key(s) ${json.keySet() - allowed}" }
            fun array(k: String) = json.getAsJsonArray(k) ?: throw IllegalArgumentException("slope table: missing $k")
            return SlopeTable(
                thicknessToDiameter = array("thickness_to_diameter").map { it.asDouble },
                anglesRad = array("angles_deg").map { Math.toRadians(it.asDouble) },
                factors = array("factors").map { row -> row.asJsonArray.map { it.asDouble } },
            )
        }
    }
}
