// AGENT-DONE(claude): radio-tuning-ui
package io.github.jwyoon1220.dncity.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioTuningTest {
    @Test
    fun `a VHF frequency typed as MHz but read as kHz is out of band, not silently fine`() {
        // 145.5 read as kHz is longwave: what the old screen sent for "145.5" on a VHF set
        assertEquals(RadioTuning.Problem.OUT_OF_BAND, RadioTuning.problem(RadioBandGroup.VHF_UHF, 5, 0, 145.5, RadioMode.NBFM))
        assertNull(RadioTuning.problem(RadioBandGroup.VHF_UHF, 5, 0, 145_500.0, RadioMode.NBFM))
    }

    @Test
    fun `slot, missing frequency and mode are reported`() {
        assertEquals(RadioTuning.Problem.BAD_SLOT, RadioTuning.problem(RadioBandGroup.MW, 5, 5, 1000.0, RadioMode.AM))
        assertEquals(RadioTuning.Problem.NO_FREQUENCY, RadioTuning.problem(RadioBandGroup.MW, 5, 0, null, RadioMode.AM))
        assertEquals(RadioTuning.Problem.MODE_UNSUPPORTED, RadioTuning.problem(RadioBandGroup.MW, 5, 0, 1000.0, RadioMode.NBFM))
    }

    @Test
    fun `ranges cover the band group`() {
        assertEquals(300.0..3000.0, RadioTuning.rangeKhz(RadioBandGroup.MW))
        assertEquals(30_000.0..3_000_000.0, RadioTuning.rangeKhz(RadioBandGroup.VHF_UHF))
    }
}
