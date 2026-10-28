// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Why a table answer is not a plain in-range interpolation. */
enum class RangeFlag { DISTANCE_CLAMPED, ANGLE_CLAMPED, DISTANCE_EXTRAPOLATED, ANGLE_EXTRAPOLATED }

sealed interface TableAnswer {
    data class Limit(val thicknessM: Double, val flags: Set<RangeFlag>) : TableAnswer
    data class OutOfRange(val reason: String) : TableAnswer
}

/**
 * Bilinear lookup of a [PenetrationTable] under the author's [TableMeta.interpolation] and [TableMeta.rangePolicy]. Inside the
 * grid it interpolates; outside it does exactly what the policy says and nothing else: UNRESOLVED refuses, CLAMP holds the edge
 * value (flagged), EXTRAPOLATE continues the last segment but only within the stated margin (flagged). It never decides on its own.
 * Pure and stateless.
 */
object TableLookup {
    private const val EDGE_EPS = 1e-9

    fun limit(table: PenetrationTable, meta: TableMeta, distanceM: Double, angleRad: Double): TableAnswer {
        if (!distanceM.isFinite() || !angleRad.isFinite()) return TableAnswer.OutOfRange("non-finite query")
        val policy = meta.rangePolicy
        val flags = HashSet<RangeFlag>()
        val d = when (val r = locate(table.distancesM, distanceM, policy, policy.distanceMarginM, "distance", "m")) {
            is Located.Refused -> return TableAnswer.OutOfRange(r.reason)
            is Located.At -> r.also { if (it.clamped) flags += RangeFlag.DISTANCE_CLAMPED; if (it.extrapolated) flags += RangeFlag.DISTANCE_EXTRAPOLATED }
        }
        val a = when (val r = locate(table.anglesRad, angleRad, policy, policy.angleMarginRad, "angle", "rad")) {
            is Located.Refused -> return TableAnswer.OutOfRange(r.reason)
            is Located.At -> r.also { if (it.clamped) flags += RangeFlag.ANGLE_CLAMPED; if (it.extrapolated) flags += RangeFlag.ANGLE_EXTRAPOLATED }
        }

        val log = meta.interpolation == Interpolation.LOG_LINEAR
        fun v(x: Double) = if (log) ln(x) else x
        fun lerp(a0: Double, a1: Double, t: Double) = a0 + (a1 - a0) * t
        fun rowAt(di: Int): Double {
            val r = table.limitsM[di]
            return if (r.size == 1) v(r[0]) else lerp(v(r[a.i]), v(r[a.i + 1]), a.t)
        }
        val value = if (table.distancesM.size == 1) rowAt(0) else lerp(rowAt(d.i), rowAt(d.i + 1), d.t)
        val result = if (log) exp(value) else value
        if (!result.isFinite() || result <= 0.0) return TableAnswer.OutOfRange("extrapolation produced a non-positive limit")
        return TableAnswer.Limit(result, flags)
    }

    private sealed interface Located {
        /** Segment [i]..[i+1] and parameter [t] (0..1 inside the grid, outside that only when extrapolating). */
        data class At(val i: Int, val t: Double, val clamped: Boolean, val extrapolated: Boolean) : Located
        data class Refused(val reason: String) : Located
    }

    private fun locate(grid: DoubleArray, x: Double, policy: RangePolicy, margin: Double, name: String, unit: String): Located {
        val lo = grid.first()
        val hi = grid.last()
        if (grid.size == 1) {
            if (abs(x - lo) <= EDGE_EPS) return Located.At(0, 0.0, clamped = false, extrapolated = false)
            return when (policy.mode) {
                RangeMode.CLAMP -> Located.At(0, 0.0, clamped = true, extrapolated = false)
                RangeMode.UNRESOLVED -> Located.Refused("$name $x $unit is outside the one-point table at $lo")
                RangeMode.EXTRAPOLATE -> Located.Refused("$name $x $unit is outside a one-point table, which cannot be extrapolated")
            }
        }
        if (x >= lo - EDGE_EPS && x <= hi + EDGE_EPS) return segment(grid, x.coerceIn(lo, hi), clamped = false, extrapolated = false)
        val beyond = if (x < lo) lo - x else x - hi
        return when (policy.mode) {
            RangeMode.UNRESOLVED -> Located.Refused("$name $x $unit is outside the table range [$lo, $hi]")
            RangeMode.CLAMP -> segment(grid, x.coerceIn(lo, hi), clamped = true, extrapolated = false)
            RangeMode.EXTRAPOLATE ->
                if (beyond > margin) Located.Refused("$name $x $unit is $beyond beyond the table, more than the allowed extrapolation margin $margin")
                else segment(grid, x, clamped = false, extrapolated = true)
        }
    }

    private fun segment(grid: DoubleArray, x: Double, clamped: Boolean, extrapolated: Boolean): Located.At {
        // Last segment whose start is <= x; x past either end continues the first/last segment.
        val i = grid.indexOfLast { it <= x }.coerceIn(0, grid.size - 2)
        return Located.At(i, (x - grid[i]) / (grid[i + 1] - grid[i]), clamped, extrapolated)
    }
}
