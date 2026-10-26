// AGENT-DONE(claude): external-ballistics-si
package io.github.jwyoon1220.dncity.ballistics.flight

import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closed-form checks only. Masses, diameters and Cd here are made up for the arithmetic, not ammunition data. */
class FlightIntegratorTest {
    private fun ok(s: FlightStep) = assertIs<FlightStep.Ok>(s).state

    @Test fun `ISA matches the standard at sea level and at 11 km`() {
        assertEquals(1.2250, Atmosphere.Isa.densityKgM3(0.0), 5e-4)
        assertEquals(340.29, Atmosphere.Isa.speedOfSoundMps(0.0), 0.05)
        assertEquals(216.65, Atmosphere.Isa.temperatureK(11_000.0), 1e-9)
        assertEquals(0.3639, Atmosphere.Isa.densityKgM3(11_000.0), 1e-3)
        assertFailsWith<IllegalArgumentException> { Atmosphere.Isa.densityKgM3(12_000.0) } // no silent clamp
    }

    @Test fun `without drag the path is the exact parabola`() {
        // Cd tiny but positive, density tiny: effectively vacuum; RK4 is exact for a quadratic path.
        val f = FlightIntegrator(1.0, 0.01, DragModel.Constant(1e-12), Atmosphere.Constant(1e-12))
        val s0 = FlightState(V3.ZERO, V3(100.0, 50.0, 0.0), 0.0, 0.0)
        val s = ok(f.advance(s0, 2.0, 0.01))
        val g = Atmosphere.Isa.G
        assertEquals(200.0, s.position.x, 1e-6)
        assertEquals(100.0 - 0.5 * g * 4.0, s.position.y, 1e-6)
        assertEquals(50.0 - g * 2.0, s.velocity.y, 1e-6)
    }

    @Test fun `horizontal flight without gravity matches the exponential speed decay`() {
        val m = 2.0; val d = 0.05; val cd = 0.3; val rho = 1.2
        val f = FlightIntegrator(m, d, DragModel.Constant(cd), Atmosphere.Constant(rho), gravityMps2 = 0.0)
        val k = rho * cd * (PI * d * d / 4.0) / (2.0 * m)
        val s = ok(f.advance(FlightState(V3.ZERO, V3(800.0, 0.0, 0.0), 0.0, 0.0), 1.0, 0.001))
        val analytic = 800.0 * exp(-k * s.pathM) // v(x) = v0 exp(-k x)
        assertEquals(analytic, s.speedMps, 1e-6 * 800.0)
    }

    @Test fun `a heavier projectile is slowed less`() {
        fun speedAfter(m: Double) = ok(FlightIntegrator(m, 0.05, DragModel.Constant(0.3), Atmosphere.Constant(1.2), gravityMps2 = 0.0)
            .advance(FlightState(V3.ZERO, V3(800.0, 0.0, 0.0), 0.0, 0.0), 1.0)).speedMps
        assertTrue(speedAfter(4.0) > speedAfter(1.0))
    }

    @Test fun `the same inputs give the same bits`() {
        fun run() = ok(FlightIntegrator(1.0, 0.03, DragModel.Constant(0.4)).advance(FlightState(V3(0.0, 100.0, 0.0), V3(300.0, 20.0, 5.0), 0.0, 0.0), 3.0))
        assertEquals(run(), run())
    }

    @Test fun `a drag curve that does not cover the Mach number stops the flight instead of extrapolating`() {
        val drag = DragModel.Tabulated(doubleArrayOf(0.5, 1.5), doubleArrayOf(0.3, 0.4))
        assertNull(drag.cd(0.4))
        assertEquals(0.35, drag.cd(1.0)!!, 1e-12)
        val f = FlightIntegrator(1.0, 0.03, drag, Atmosphere.Constant(1.2, 340.0), gravityMps2 = 0.0)
        // Mach 3: not covered
        val r = f.advance(FlightState(V3.ZERO, V3(1020.0, 0.0, 0.0), 0.0, 0.0), 1.0)
        assertIs<FlightStep.OutOfModel>(r)
        assertEquals(0.0, r.last.timeS, 0.0)
    }

    @Test fun `leaving the troposphere model is reported`() {
        val f = FlightIntegrator(1.0, 0.03, DragModel.Constant(0.3))
        val r = f.advance(FlightState(V3(0.0, 10_990.0, 0.0), V3(0.0, 300.0, 0.0), 0.0, 0.0), 1.0)
        assertIs<FlightStep.OutOfModel>(r)
    }

    @Test fun `unit conversion has one definition`() {
        assertEquals(20.0, MinecraftUnits.blocksPerTickToMps(1.0), 0.0)
        assertEquals(1.5, MinecraftUnits.mpsToBlocksPerTick(MinecraftUnits.blocksPerTickToMps(1.5)), 1e-12)
        assertEquals(10.0, MinecraftUnits.altitudeM(73.0), 0.0)
        assertTrue(abs(MinecraftUnits.TICK_S * MinecraftUnits.TICKS_PER_SECOND - 1.0) < 1e-12)
    }
}
