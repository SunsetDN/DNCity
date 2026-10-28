// AGENT-DONE(claude): radio-tuning-ui
package io.github.jwyoon1220.dncity.radio

import io.github.jwyoon1220.dncity.item.RadioItem

/**
 * The checks a tune must pass, shared by the server ([RadioActions.tune], the authority) and the tuning screen, which
 * runs them first so it can say *why* a frequency is refused instead of the server dropping it silently.
 */
object RadioTuning {

    enum class Problem(val translationKey: String) {
        NO_FREQUENCY("radio.dncity.tune.no_frequency"),
        BAD_SLOT("radio.dncity.tune.bad_slot"),
        OUT_OF_BAND("radio.dncity.tune.out_of_band"),
        MODE_UNSUPPORTED("radio.dncity.tune.mode_unsupported"),
    }

    fun problem(radio: RadioItem, slot: Int, frequencyKhz: Double?, mode: RadioMode): Problem? =
        problem(radio.bandGroup, radio.tier, slot, frequencyKhz, mode)

    fun problem(group: RadioBandGroup, tier: Int, slot: Int, frequencyKhz: Double?, mode: RadioMode): Problem? {
        if (slot !in 0 until tier) return Problem.BAD_SLOT
        if (frequencyKhz == null || !frequencyKhz.isFinite()) return Problem.NO_FREQUENCY
        val band = RadioBand.fromFrequencyKhz(frequencyKhz)
        if (band == null || !group.supports(band)) return Problem.OUT_OF_BAND
        if (!group.supportsMode(mode)) return Problem.MODE_UNSUPPORTED
        return null
    }

    /** The lowest and highest frequency (kHz) [radio] can be tuned to, over all of its bands. */
    fun rangeKhz(radio: RadioItem): ClosedFloatingPointRange<Double> = rangeKhz(radio.bandGroup)

    fun rangeKhz(group: RadioBandGroup): ClosedFloatingPointRange<Double> =
        group.bands.minOf { it.minKhz }..group.bands.maxOf { it.maxKhz }

    /** [data] with exactly [tier] slots: a stack saved by an older build may hold fewer, and writing slot i of a shorter
     * list threw IndexOutOfBoundsException on the server. */
    fun normalized(data: RadioData, tier: Int): RadioData =
        if (data.slots.size == tier) data
        else data.copy(slots = List(tier) { data.slots.getOrNull(it) ?: RadioSlot.empty() })
}
