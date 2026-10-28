package io.github.jwyoon1220.dncity.radio

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
