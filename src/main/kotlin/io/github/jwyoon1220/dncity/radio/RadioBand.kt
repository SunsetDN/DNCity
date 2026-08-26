package io.github.jwyoon1220.dncity.radio

/**
 * Real-world-ish frequency bands (ITU designations, ELF through THF), keyed by frequency in
 * kHz. Propagation is per band: everything through SW behaves like ground-wave/HF (effectively
 * unlimited range within a Minecraft world, just a fixed static floor), while VHF and up are
 * limited-range line-of-sight bands with a hard cutoff that gets tighter as frequency rises.
 *
 * VHF-and-up [maxRangeBlocks] are tuned against this project's actual play area, roughly a
 * 7.5km x 7.5km (7500x7500-block) map: VHF at 3000 blocks covers a substantial chunk of the map
 * (a "regional/squad" tier) without trivializing the ground-wave bands' role as the only tier
 * that reliably spans the whole map. UHF/SHF/EHF/THF keep the same falloff *shape* the original
 * tuning had (each roughly 0.4x/0.15x/0.05x/0.015x of VHF) rather than a re-derived curve, just
 * rescaled to the new VHF baseline.
 */
enum class RadioBand(
    val minKhz: Double,
    val maxKhz: Double,
    val maxRangeBlocks: Double?,
    val tuningToleranceKhz: Double,
) {
    ELF(minKhz = 0.003, maxKhz = 0.03, maxRangeBlocks = null, tuningToleranceKhz = 0.001),
    SLF(minKhz = 0.03, maxKhz = 0.3, maxRangeBlocks = null, tuningToleranceKhz = 0.005),
    ULF(minKhz = 0.3, maxKhz = 3.0, maxRangeBlocks = null, tuningToleranceKhz = 0.01),
    VLF(minKhz = 3.0, maxKhz = 30.0, maxRangeBlocks = null, tuningToleranceKhz = 0.1),
    LW(minKhz = 30.0, maxKhz = 300.0, maxRangeBlocks = null, tuningToleranceKhz = 1.0),
    MW(minKhz = 300.0, maxKhz = 3000.0, maxRangeBlocks = null, tuningToleranceKhz = 2.0),
    SW(minKhz = 3000.0, maxKhz = 30000.0, maxRangeBlocks = null, tuningToleranceKhz = 5.0),
    VHF(minKhz = 30000.0, maxKhz = 300000.0, maxRangeBlocks = 3000.0, tuningToleranceKhz = 25.0),
    UHF(minKhz = 300000.0, maxKhz = 3000000.0, maxRangeBlocks = 1200.0, tuningToleranceKhz = 25.0),
    SHF(minKhz = 3000000.0, maxKhz = 30000000.0, maxRangeBlocks = 450.0, tuningToleranceKhz = 50.0),
    EHF(minKhz = 30000000.0, maxKhz = 300000000.0, maxRangeBlocks = 150.0, tuningToleranceKhz = 100.0),
    THF(minKhz = 300000000.0, maxKhz = 3000000000.0, maxRangeBlocks = 45.0, tuningToleranceKhz = 250.0);

    companion object {
        fun fromFrequencyKhz(khz: Double): RadioBand? = entries.firstOrNull { khz in it.minKhz..it.maxKhz }
    }
}
