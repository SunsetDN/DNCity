// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class FuzeStateTest {
    private fun payload(arming: Double, delay: Double) =
        PayloadSpec(1.0, null, null, FuzeSpec(armingDistanceM = arming, delayS = delay, minTriggerThicknessM = null, failureChance = 0.0))

    @Test
    fun `no fuze is inert and ignores everything`() {
        val s = FuzeState.initial(null)
        assertEquals(FuzePhase.INERT, s.phase)
        assertEquals(s, s.contact(null, failed = false).elapsed(1.0))
    }

    @Test
    fun `fuze with arming distance starts safe and arms after flying it`() {
        val spec = payload(arming = 50.0, delay = 0.0).fuze
        var s = FuzeState.initial(payload(50.0, 0.0))
        assertEquals(FuzePhase.SAFE, s.phase)
        s = s.flown(30.0, spec)
        assertEquals(FuzePhase.SAFE, s.phase)
        s = s.flown(20.0, spec)
        assertEquals(FuzePhase.ARMED, s.phase)
    }

    @Test
    fun `contact while safe does nothing`() {
        val spec = payload(50.0, 0.0).fuze
        val s = FuzeState.initial(payload(50.0, 0.0))
        assertEquals(FuzePhase.SAFE, s.contact(spec, failed = false).phase)
    }

    @Test
    fun `armed instant fuze detonates on contact`() {
        val spec = payload(0.0, 0.0).fuze
        assertEquals(FuzePhase.DETONATED, FuzeState.initial(payload(0.0, 0.0)).contact(spec, failed = false).phase)
    }

    @Test
    fun `delay fuze triggers on contact and detonates when the delay has run out`() {
        val spec = payload(0.0, 0.02).fuze
        var s = FuzeState.initial(payload(0.0, 0.02)).contact(spec, failed = false)
        assertEquals(FuzePhase.TRIGGERED, s.phase)
        s = s.elapsed(0.01)
        assertEquals(FuzePhase.TRIGGERED, s.phase)
        s = s.elapsed(0.01)
        assertEquals(FuzePhase.DETONATED, s.phase)
        assertEquals(true, s.isFinal)
    }

    @Test
    fun `failed trigger ends in FAILED and stays there`() {
        val spec = payload(0.0, 0.0).fuze
        val s = FuzeState.initial(payload(0.0, 0.0)).contact(spec, failed = true)
        assertEquals(FuzePhase.FAILED, s.phase)
        assertEquals(s, s.contact(spec, failed = false).elapsed(5.0))
    }
}
