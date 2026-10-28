package io.github.jwyoon1220.dncity.radio

/**
 * Which [RadioBand]s a physical radio item can actually be tuned to, and which [RadioMode]s it
 * can use there -- a real radio is built for one family of bands with one family of
 * demodulators, not the whole spectrum [RadioBand]/[RadioMode] model end to end. [translationKey]
 * is shown on the item's tooltip (see `RadioItem.appendHoverText`) so players can tell which one
 * they're holding without opening the tuning screen.
 *
 * Each entry matches a real, distinct class of radio set (the first three are handheld
 * [io.github.jwyoon1220.dncity.item.RadioItem]s; the last two are not, see their own entries):
 * - **MW** ("중파 무전기") -- a broadcast-band-style set: AM/SAM only, the modes real mediumwave
 *   receivers use. Ground-wave range is generous (MW's ground wave carries furthest of the three
 *   -- see [RadioBand.groundWaveRangeBlocks]) and it develops the biggest night skywave DX jump.
 * - **HF** ("단파 무전기") -- a shortwave/amateur-style set: USB, LSB, CW, and AM (shortwave
 *   broadcast still runs AM). Shorter ground wave than MW but the same map-spanning night skywave,
 *   and CW's narrow filter gives it the best raw range of any mode here for a given transmit power.
 * - **VHF_UHF** ("VHF/UHF 무전기") -- a line-of-sight handheld: NBFM (the real land-mobile/marine
 *   voice mode) plus AM (the mode VHF aviation band actually uses, historically kept instead of
 *   FM specifically because FM's capture effect would let one strong signal blot out another
 *   aircraft transmitting at the same time). No sky-wave/ground-wave story here -- see
 *   [RadioBand.isGroundWaveFamily] and [io.github.jwyoon1220.dncity.voice.RadioRelay]'s
 *   line-of-sight handling instead.
 * - **BROADCAST** ("라디오 방송국") -- not a handheld at all: validates tuning for
 *   [io.github.jwyoon1220.dncity.block.RadioStationBlockEntity] (a placed block players join and
 *   broadcast through, see that class and [io.github.jwyoon1220.dncity.voice.RadioRelay.relayFromStation])
 *   rather than a [io.github.jwyoon1220.dncity.item.RadioItem]. AM on [RadioBand.MW] (like a real
 *   AM broadcast station) or wideband [RadioMode.FM] on [RadioBand.VHF] (real FM broadcast sits at
 *   88-108MHz, inside this project's VHF band -- see [RadioBand]). A station's fixed high transmit
 *   power (see [io.github.jwyoon1220.dncity.voice.RadioRelay]'s `STATION_POWER`) is what actually
 *   makes it reach like a real station rather than a handheld (see [RadioVoice.powerRangeMultiplier]).
 * - **SCANNER** ("라디오 수신기") -- every mode/band any radio actually transmits on, for a
 *   receive-only "listen to anything" set (`RadioItem.canTransmit == false` is what actually
 *   blocks it from transmitting -- see [io.github.jwyoon1220.dncity.radio.RadioActions.transmittableHotbarRadio] --
 *   not this band group; a receiver just needs the widest possible dial).
 */
enum class RadioBandGroup(val translationKey: String, val bands: Set<RadioBand>, val modes: Set<RadioMode>) {
    MW("radio.dncity.band_mw", setOf(RadioBand.MW), setOf(RadioMode.AM, RadioMode.SAM)),

    HF("radio.dncity.band_hf", setOf(RadioBand.SW), setOf(RadioMode.USB, RadioMode.LSB, RadioMode.CW, RadioMode.AM)),

    VHF_UHF(
        "radio.dncity.band_vhf_uhf",
        setOf(RadioBand.VHF, RadioBand.UHF),
        setOf(RadioMode.NBFM, RadioMode.AM),
    ),

    BROADCAST("radio.dncity.band_broadcast", setOf(RadioBand.MW, RadioBand.VHF), setOf(RadioMode.AM, RadioMode.FM)),

    SCANNER("radio.dncity.band_scanner", setOf(RadioBand.MW, RadioBand.SW, RadioBand.VHF, RadioBand.UHF), RadioMode.entries.toSet());

    fun supports(band: RadioBand): Boolean = band in bands
    fun supportsMode(mode: RadioMode): Boolean = mode in modes
}
