// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

/**
 * What a "ballistic limit" in a calibration data set means. Limits of different definitions are never converted into each
 * other: data with another definition is a separate data set with its own preset.
 *
 * This is the definition of the *test observation*, not of the DNCity outcome: a [PROTECTION_LIMIT] (witness plate) says nothing
 * about whether the projectile itself got through, so using it to drive `PERFORATED` is a systematic uncertainty that
 * the preset's provenance must state.
 */
enum class BallisticLimitDefinition {
    MINIMUM_PERFORATION,
    V50,
    NAVY_LIMIT,
    PROTECTION_LIMIT,
    SOURCE_DEFINED,
}
