// AGENT-DONE(claude): external-ballistics-si
package io.github.jwyoon1220.dncity.ballistics.flight

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Air state at an altitude. [Isa] is the International Standard Atmosphere, troposphere only (these are the defining constants
 * of the standard, not tuned numbers); a fixed-density atmosphere exists for tests and for comparing against closed-form drag.
 */
sealed interface Atmosphere {
    fun densityKgM3(altitudeM: Double): Double
    fun speedOfSoundMps(altitudeM: Double): Double

    /** ISA: T = T0 - L h, p = p0 (T/T0)^(g/(L R)), rho = p / (R T). Valid from MIN_ALTITUDE_M to MAX_ALTITUDE_M; outside it throws (no silent clamp). */
    data object Isa : Atmosphere {
        const val T0 = 288.15
        const val P0 = 101_325.0
        const val LAPSE = 0.0065
        const val R = 287.052_87
        const val G = 9.806_65
        const val GAMMA = 1.4
        const val MIN_ALTITUDE_M = -500.0
        const val MAX_ALTITUDE_M = 11_000.0

        private fun checked(h: Double): Double {
            require(h.isFinite() && h in MIN_ALTITUDE_M..MAX_ALTITUDE_M) { "altitude $h m is outside the ISA troposphere model" }
            return h
        }

        fun temperatureK(altitudeM: Double): Double = T0 - LAPSE * checked(altitudeM)

        fun pressurePa(altitudeM: Double): Double = P0 * (temperatureK(altitudeM) / T0).pow(G / (LAPSE * R))

        override fun densityKgM3(altitudeM: Double): Double = pressurePa(altitudeM) / (R * temperatureK(altitudeM))

        override fun speedOfSoundMps(altitudeM: Double): Double = sqrt(GAMMA * R * temperatureK(altitudeM))
    }

    data class Constant(val rho: Double, val soundMps: Double = 340.0) : Atmosphere {
        init {
            require(rho > 0.0 && soundMps > 0.0) { "density and speed of sound must be positive" }
        }

        override fun densityKgM3(altitudeM: Double) = rho
        override fun speedOfSoundMps(altitudeM: Double) = soundMps
    }
}
