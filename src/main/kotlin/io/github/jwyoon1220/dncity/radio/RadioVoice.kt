package io.github.jwyoon1220.dncity.radio

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Signal-quality math for the radio voice tier (PTT, half-duplex -- see the radio voice-chat
 * design plan's tier table). All of it operates on a single server-computed
 * [effectiveMaxRangeBlocks] per (sender, listener) pair rather than branching on
 * [RadioBand.maxRangeBlocks] directly, since that number already folds in everything real-world
 * propagation depends on for that specific pair -- line-of-sight terrain obstruction, antenna
 * height (any band, not just VHF-and-up -- see [elevationBonusBlocks]), transmit power (see
 * [powerRangeMultiplier]), and day/night ground-wave-vs-skywave for LW/MW/SW -- computed
 * server-side in [io.github.jwyoon1220.dncity.voice.RadioRelay] (which has access to the
 * world/terrain) and carried down in the relay payload; see that object's doc comment for the
 * actual formulas, including [isDrownedOutBy]'s co-channel interference check for when more than
 * one transmitter is on the same frequency at once.
 */
object RadioVoice {

    /**
     * Carrier gain for a listener [distanceBlocks] from the transmitter, in `0f..1f`, given this
     * specific transmission's [effectiveMaxRangeBlocks] (already folds in terrain/elevation/
     * day-night -- see class doc). Quadratic falloff, same shape [CloseRangeVoice.gain] uses.
     */
    fun gain(distanceBlocks: Double, effectiveMaxRangeBlocks: Double): Float {
        if (effectiveMaxRangeBlocks <= 0.0) return 0f
        if (distanceBlocks <= 0.0) return 1f
        if (distanceBlocks >= effectiveMaxRangeBlocks) return 0f
        val linear = 1.0 - (distanceBlocks / effectiveMaxRangeBlocks)
        return (linear * linear).toFloat()
    }

    /**
     * How much static to mix under the decoded signal, in `0f..1f`. Always at least
     * [RadioMode.baseNoiseLevel] (real radio modes are never perfectly clean), rising further as
     * [gain] drops toward the edge of range -- the shape of that rise is mode-specific, since
     * real AM/SAM/USB/LSB/CW/NBFM receivers degrade differently rather than all sharing one
     * static curve (see [RadioMode]'s doc comment for the real-world basis of each).
     *
     * [obstructed] adds a flat penalty for VHF-and-up transmissions arriving despite (partial)
     * terrain blockage -- real non-line-of-sight VHF/UHF reception happens via diffraction/
     * reflection off other terrain, not a clean signal, even when it's strong enough in raw
     * distance terms to still be in range. [stormNoise] adds a penalty for LW/MW/SW during a
     * thunderstorm -- lightning is the dominant real source of atmospheric static (QRN) on those
     * bands, far more than on VHF-and-up, which is why it's only ever passed `true` for the
     * ground-wave family (see [io.github.jwyoon1220.dncity.voice.RadioRelay]).
     */
    fun staticLevel(mode: RadioMode, gain: Float, obstructed: Boolean = false, stormNoise: Boolean = false): Float {
        val weak = (1f - gain).coerceIn(0f, 1f)
        val base = mode.baseNoiseLevel.toFloat()
        val fromRange = when (mode) {
            // FM/NBFM's "capture effect": a demodulator locks onto whichever signal is strongest,
            // so quality stays near-clean until the carrier gets quite weak, then static ramps up
            // sharply rather than gradually.
            RadioMode.NBFM, RadioMode.FM -> weak.pow(3) * 1.4f
            // AM has no capture effect and no fade immunity -- amplitude (and therefore noise)
            // tracks signal strength close to linearly, so it fades gradually rather than holding
            // clean then collapsing.
            RadioMode.AM -> weak * 0.6f
            // Synchronous detection is immune to the selective-fading distortion that makes plain
            // AM "growl" as it weakens -- degrades more gently than AM at the same gain.
            RadioMode.SAM -> weak * 0.35f
            // USB/LSB carry no carrier/sidebands to begin with, so their clean-signal noise floor
            // is already the lowest of the voice-bandwidth modes; degrades a bit more gently than
            // AM.
            RadioMode.USB, RadioMode.LSB -> weak * 0.45f
            // CW's narrow filter (see RadioMode's doc) gives it by far the best noise performance
            // of any mode for a given signal strength -- it holds together far longer as gain
            // drops than any voice mode does.
            RadioMode.CW -> weak.pow(2) * 0.2f
        }
        val obstructedPenalty = if (obstructed) OBSTRUCTED_STATIC_BONUS else 0f
        val stormPenalty = if (stormNoise) STORM_STATIC_BONUS else 0f
        return (base + fromRange + obstructedPenalty + stormPenalty).coerceIn(0f, 1f)
    }

    /**
     * Antenna-height bonus for a transmitter/listener pair against a [nominalRangeBlocks] base
     * (VHF-and-up's [RadioBand.maxRangeBlocks], or a ground-wave-family band's own ground-wave/
     * skywave range -- height helps a broadcast tower's takeoff angle same as it helps a handheld's
     * line of sight, so this isn't restricted to LOS bands). Modeled on the real radio-horizon
     * formula (distance ≈ k * (√h1 + √h2), h in meters) -- raising either end's antenna extends
     * range in the real world by pushing the horizon further out; here "height" is height above
     * the terrain surface directly below (a radio at ground level in a valley gets no bonus, one
     * on a hilltop or tower does), and the bonus is capped so a sufficiently tall tower can
     * meaningfully extend but not trivialize a band's nominal range.
     */
    fun elevationBonusBlocks(nominalRangeBlocks: Double, senderHeightAboveTerrain: Double, listenerHeightAboveTerrain: Double): Double {
        val raw = ELEVATION_BONUS_PER_SQRT_BLOCK * (sqrt(senderHeightAboveTerrain.coerceAtLeast(0.0)) + sqrt(listenerHeightAboveTerrain.coerceAtLeast(0.0)))
        return raw.coerceAtMost(nominalRangeBlocks * MAX_ELEVATION_BONUS_FRACTION)
    }

    /**
     * The ground-wave-family (LW/MW/SW) effective range for the current time of day:
     * [RadioBand.groundWaveRangeBlocks] always, extended out to [RadioBand.skywaveRangeBlocks]
     * only at night, once the ionosphere's D layer (which absorbs these bands' energy in
     * daylight) has dissipated -- see [RadioBand]'s doc comment. `null` band fields (bands no
     * radio item actually uses) fall back to `0.0`, never crashing.
     */
    fun groundWaveFamilyRangeBlocks(band: RadioBand, isNight: Boolean): Double {
        val groundWave = band.groundWaveRangeBlocks ?: return 0.0
        if (!isNight) return groundWave
        return band.skywaveRangeBlocks ?: groundWave
    }

    /**
     * How much a transmitter's [io.github.jwyoon1220.dncity.item.RadioItem.power] scales its
     * nominal range by, before the elevation bonus is added. Real range scales with the square
     * root of transmit power for a fixed receiver sensitivity (doubling power only gets you
     * ~41% farther, not 2x) -- this is also why a dedicated broadcast station (high power) reaches
     * dramatically further than a handheld even though neither's antenna height changed.
     */
    fun powerRangeMultiplier(power: Double): Double = sqrt(power.coerceAtLeast(MIN_POWER))

    /**
     * True if [competitorReceivedPower] should drown out [ownReceivedPower] at a listener tuned to
     * both -- real co-channel interference: two transmitters close in received signal strength mix
     * together audibly (static/overlap, or FM's own capture effect fighting to lock onto either),
     * but once one is clearly stronger, receivers (especially FM ones, but modeled uniformly here
     * for simplicity) lock onto it and the weaker one effectively disappears. Received power is
     * approximated as transmit power scaled by the already-computed distance/terrain [gain] --
     * see [io.github.jwyoon1220.dncity.voice.RadioRelay] for how competing transmissions are
     * tracked and compared per listener.
     */
    fun isDrownedOutBy(ownReceivedPower: Double, competitorReceivedPower: Double): Boolean =
        competitorReceivedPower > ownReceivedPower * DOMINANCE_RATIO

    private const val OBSTRUCTED_STATIC_BONUS = 0.3f
    private const val STORM_STATIC_BONUS = 0.25f
    private const val ELEVATION_BONUS_PER_SQRT_BLOCK = 40.0
    private const val MAX_ELEVATION_BONUS_FRACTION = 1.0
    private const val MIN_POWER = 0.01
    private const val DOMINANCE_RATIO = 2.5
}
