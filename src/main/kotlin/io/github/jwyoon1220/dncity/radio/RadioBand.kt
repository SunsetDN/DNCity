package io.github.jwyoon1220.dncity.radio

/**
 * Real-world-ish frequency bands (ITU designations, ELF through THF), keyed by frequency in
 * kHz. Propagation is per band and modeled on two real mechanisms rather than one flat rule:
 *
 * - **LW/MW/SW** ("ground-wave" bands, [maxRangeBlocks] == null): a real MF/HF signal reaches
 *   receivers two ways at once. Ground wave ([groundWaveRangeBlocks]) hugs the surface, is
 *   available day or night, but attenuates faster the *higher* the frequency -- SW's ground wave
 *   is shorter-ranged than MW's, mirroring how AM broadcast (MW) carries much further along the
 *   ground than a similar-power shortwave signal does. Sky wave ([skywaveRangeBlocks]) reflects
 *   off the ionosphere and can cover enormous distances, but only at night: the ionosphere's D
 *   layer, which absorbs HF/MF energy passing through it, exists only under sunlight and
 *   disappears after dusk, so daytime skywave on these bands is effectively dead (see
 *   [RadioVoice.effectiveMaxRangeBlocks] for how the two combine with an isNight flag). This is
 *   why real-world MW/shortwave stations audible for hundreds of miles at night vanish to a local
 *   ground-wave-only footprint by midday.
 * - **VHF-and-up** ([maxRangeBlocks] != null): genuinely line-of-sight -- range is a hard cutoff
 *   that gets tighter as frequency rises, further gated by real terrain obstruction and antenna
 *   height rather than distance alone (see [io.github.jwyoon1220.dncity.voice.RadioRelay]).
 *
 * VHF-and-up [maxRangeBlocks] are tuned against this project's actual play area, roughly a
 * 7.5km x 7.5km (7500x7500-block) map: VHF at 3000 blocks covers a substantial chunk of the map
 * (a "regional/squad" tier) without trivializing the ground-wave bands' role as the only tier
 * that reliably spans the whole map (at night). UHF/SHF/EHF/THF keep the same falloff *shape*
 * the original tuning had (each roughly 0.4x/0.15x/0.05x/0.015x of VHF) rather than a re-derived
 * curve, just rescaled to the new VHF baseline. [skywaveRangeBlocks] for LW/MW/SW is set past the
 * map's ~10600-block diagonal so a night skywave contact can, in principle, reach anywhere on it.
 */
enum class RadioBand(
    val minKhz: Double,
    val maxKhz: Double,
    val maxRangeBlocks: Double?,
    val tuningToleranceKhz: Double,
    val groundWaveRangeBlocks: Double? = null,
    val skywaveRangeBlocks: Double? = null,
) {
    ELF(minKhz = 0.003, maxKhz = 0.03, maxRangeBlocks = null, tuningToleranceKhz = 0.001),
    SLF(minKhz = 0.03, maxKhz = 0.3, maxRangeBlocks = null, tuningToleranceKhz = 0.005),
    ULF(minKhz = 0.3, maxKhz = 3.0, maxRangeBlocks = null, tuningToleranceKhz = 0.01),
    VLF(minKhz = 3.0, maxKhz = 30.0, maxRangeBlocks = null, tuningToleranceKhz = 0.1),
    LW(
        minKhz = 30.0, maxKhz = 300.0, maxRangeBlocks = null, tuningToleranceKhz = 1.0,
        groundWaveRangeBlocks = 4000.0, skywaveRangeBlocks = 12000.0,
    ),
    MW(
        minKhz = 300.0, maxKhz = 3000.0, maxRangeBlocks = null, tuningToleranceKhz = 2.0,
        groundWaveRangeBlocks = 2000.0, skywaveRangeBlocks = 12000.0,
    ),
    SW(
        minKhz = 3000.0, maxKhz = 30000.0, maxRangeBlocks = null, tuningToleranceKhz = 5.0,
        groundWaveRangeBlocks = 900.0, skywaveRangeBlocks = 12000.0,
    ),
    VHF(minKhz = 30000.0, maxKhz = 300000.0, maxRangeBlocks = 3000.0, tuningToleranceKhz = 25.0),
    UHF(minKhz = 300000.0, maxKhz = 3000000.0, maxRangeBlocks = 1200.0, tuningToleranceKhz = 25.0),
    SHF(minKhz = 3000000.0, maxKhz = 30000000.0, maxRangeBlocks = 450.0, tuningToleranceKhz = 50.0),
    EHF(minKhz = 30000000.0, maxKhz = 300000000.0, maxRangeBlocks = 150.0, tuningToleranceKhz = 100.0),
    THF(minKhz = 300000000.0, maxKhz = 3000000000.0, maxRangeBlocks = 45.0, tuningToleranceKhz = 250.0);

    /** True for LW/MW/SW -- the ground-wave/sky-wave bands whose range depends on day/night
     * rather than being a flat line-of-sight cutoff (see class doc). */
    val isGroundWaveFamily: Boolean get() = maxRangeBlocks == null && groundWaveRangeBlocks != null

    companion object {
        fun fromFrequencyKhz(khz: Double): RadioBand? = entries.firstOrNull { khz in it.minKhz..it.maxKhz }
    }
}
