package io.github.jwyoon1220.dncity.radio

/**
 * Which [RadioBand]s a physical radio item can actually be tuned to -- a real radio is built for
 * one family of bands, not the whole spectrum [RadioBand] models end to end.
 * [translationKey] is shown on the item's tooltip (see `RadioItem.appendHoverText`) so players
 * can tell which one they're holding without opening the tuning screen.
 */
enum class RadioBandGroup(val translationKey: String, val bands: Set<RadioBand>) {
    /** "단파/중파 무전기" -- ground-wave, effectively unlimited range (see [RadioBand]). */
    HF_MF("radio.dncity.band_hf_mf", setOf(RadioBand.MW, RadioBand.SW)),

    /** "VHF/UHF무전기" -- line-of-sight, limited range (see [RadioBand]). */
    VHF_UHF("radio.dncity.band_vhf_uhf", setOf(RadioBand.VHF, RadioBand.UHF));

    fun supports(band: RadioBand): Boolean = band in bands
}
