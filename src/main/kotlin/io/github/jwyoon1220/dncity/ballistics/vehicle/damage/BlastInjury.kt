// AGENT-DONE(claude): blast-injury
package io.github.jwyoon1220.dncity.ballistics.vehicle.damage

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a warhead's blast does to a person: peak overpressure at a distance, then the share of their health it takes.
 *
 * Overpressure: Kinney & Graham (1985) free-air relation for a TNT charge, scaled distance Z = R / W^(1/3) (m, kg),
 * `dP/P0 = 808 (1 + (Z/4.5)^2) / sqrt((1 + (Z/0.048)^2)(1 + (Z/0.32)^2)(1 + (Z/1.35)^2))`, P0 = 101.325 kPa.
 *
 * Injury anchors (US Army FM 8-9, part I ch. 4, simple unreflected wave): eardrum rupture starts at about 22 kPa, lung damage at
 * 68.9 kPa, deaths from 193 kPa; Glasstone & Dolan's table as commonly quoted puts 100 % lethality at about 92 psi (634 kPa).
 * Those figures are for long-duration (nuclear) waves; a shell's short wave needs more for the same harm, so this errs harsh.
 * The share of health between the anchors (straight lines through 0 / 10 / 40 / 100 %) is DESIGN.
 */
object BlastInjury {
    const val P0_KPA = 101.325

    /** A charge going off inside a closed hull reflects off the walls: at least double (the acoustic limit of normal reflection). */
    const val CONFINED_FACTOR = 2.0

    /**
     * A closed hull that held still passes part of the blast on: the struck plate flexes and sets up a pressure wave inside, and
     * the jolt throws the crew against the hull (mechanisms: KONES 2015 vol. 22 no. 4, Slawinski; the Polish internal-wave
     * paper). No published share exists, so the numbers are DESIGN: what gets through falls off with the plate's thickness
     * ([TRANSMISSION_E_FOLD_MM] per e-fold) but never below [TRANSMISSION_FLOOR] -- a helmet does not make a hammer blow painless.
     */
    const val TRANSMISSION_FLOOR = 0.1
    const val TRANSMISSION_E_FOLD_MM = 25.0

    /** Share of the free-air overpressure that reaches the crew behind [armorMm] of plate that was not penetrated, 0.1..1. */
    fun transmitted(armorMm: Double): Double =
        TRANSMISSION_FLOOR + (1 - TRANSMISSION_FLOOR) * exp(-max(armorMm, 0.0) / TRANSMISSION_E_FOLD_MM)

    /** Nobody stands closer to a burst than this (the shell's own body is in the way). */
    private const val MIN_DISTANCE_M = 0.3

    private val ANCHORS = listOf(
        22.0 to 0.0,    // eardrum rupture threshold
        68.9 to 0.1,    // lung damage threshold
        193.0 to 0.4,   // lethality threshold
        634.0 to 1.0,   // ~100 % lethality
    )

    /** Peak overpressure (kPa) [distanceM] from [tntKg] of explosive in free air, times [CONFINED_FACTOR] when [confined]. */
    fun overpressureKpa(distanceM: Double, tntKg: Double, confined: Boolean = false): Double {
        if (tntKg <= 0) return 0.0
        val z = max(distanceM, MIN_DISTANCE_M) / cbrt(tntKg)
        fun sq(x: Double) = x * x
        val ratio = 808 * (1 + sq(z / 4.5)) / sqrt((1 + sq(z / 0.048)) * (1 + sq(z / 0.32)) * (1 + sq(z / 1.35)))
        return ratio * P0_KPA * if (confined) CONFINED_FACTOR else 1.0
    }

    /** Share of a person's maximum health [kpa] of peak overpressure takes, 0..1. */
    fun healthShare(kpa: Double): Double {
        if (kpa <= ANCHORS.first().first) return 0.0
        if (kpa >= ANCHORS.last().first) return 1.0
        val (hi, i) = ANCHORS.withIndex().first { it.value.first >= kpa }.let { it.value to it.index }
        val lo = ANCHORS[i - 1]
        return lo.second + (hi.second - lo.second) * (kpa - lo.first) / (hi.first - lo.first)
    }
}
