// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelInvariantTest {
    private val ok = Fx.headOn()

    @Test
    fun `projectile state rejects values that make no physical sense`() {
        assertFailsWith<IllegalArgumentException> { ok.copy(massRemainingKg = -0.1) }
        assertFailsWith<IllegalArgumentException> { ok.copy(penetratorLengthRemainingM = -1.0) }
        assertFailsWith<IllegalArgumentException> { ok.copy(integrity = 1.5) }
        assertFailsWith<IllegalArgumentException> { ok.copy(integrity = -0.1) }
        assertFailsWith<IllegalArgumentException> { ok.copy(deformation = -1.0) }
        assertFailsWith<IllegalArgumentException> { ok.copy(deformation = 1.5) } // normalized: 0..1
        assertFailsWith<IllegalArgumentException> { ok.copy(velocity = V3(Double.NaN, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(position = V3(0.0, Double.POSITIVE_INFINITY, 0.0)) }
    }

    @Test
    fun `the axis is always a unit vector`() {
        assertFailsWith<IllegalArgumentException> { ok.copy(axis = V3(100.0, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(axis = V3.ZERO) }
        assertFailsWith<IllegalArgumentException> { ProjectileState.launch(Fx.fuzed(null), V3.ZERO, V3.ZERO, 100.0) }
        // yaw is right because the axis is a unit vector
        assertEquals(Math.PI / 2, ok.copy(axis = V3(0.0, 1.0, 0.0)).yawRad, 1e-9)
    }

    @Test
    fun `speed is never negative and a spent projectile is allowed`() {
        assertTrue(ok.speedMps >= 0.0)
        assertEquals(0.0, ok.copy(massRemainingKg = 0.0, integrity = 0.0).kineticEnergyJ, 0.0)
    }

    @Test
    fun `a penetration result must be consistent with its outcome`() {
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.PERFORATED, null, 1.0, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.PERFORATED, ok, 1.0, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.RICOCHET, null, 1.0, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.STOPPED, ok, 1.0, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.STOPPED, null, 1.0, V3.ZERO, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult.stopped(-1.0) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult.stopped(Double.NaN) }
    }

    @Test
    fun `a perforation needs a finite point and a moving residual`() {
        assertFailsWith<IllegalArgumentException> { PenetrationResult.perforated(ok, 0.0, V3(Double.NaN, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult.perforated(ok, 0.0, V3(Double.POSITIVE_INFINITY, 0.0, 0.0)) }
        val still = ok.copy(velocity = V3.ZERO)
        assertFailsWith<IllegalArgumentException> { PenetrationResult.perforated(still, 0.0, V3.ZERO) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult.ricochet(still, 0.0) }
    }

    @Test
    fun `the exit direction is derived from the residual velocity`() {
        val r = PenetrationResult.perforated(ok.copy(velocity = V3(0.0, 300.0, 0.0)), 0.0, V3.ZERO)
        assertEquals(V3(0.0, 1.0, 0.0), r.exitDirection)
    }

    @Test
    fun `spall and blast reject values that make no sense`() {
        fun spall(axis: V3 = V3(1.0, 0.0, 0.0), half: Double = 0.3, energy: Double = 1.0, mass: Double = 0.001, origin: V3 = V3.ZERO) =
            SpallSource(origin, axis, half, energy, mass, Fx.STEEL)
        spall() // sound
        assertFailsWith<IllegalArgumentException> { spall(energy = -1.0) }
        assertFailsWith<IllegalArgumentException> { spall(mass = -0.001) }
        assertFailsWith<IllegalArgumentException> { spall(axis = V3.ZERO) }
        assertFailsWith<IllegalArgumentException> { spall(axis = V3(5.0, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { spall(half = -0.1) }
        assertFailsWith<IllegalArgumentException> { spall(half = Math.PI + 0.1) }
        assertFailsWith<IllegalArgumentException> { spall(energy = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { spall(energy = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { spall(mass = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { spall(origin = V3(Double.NaN, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { spall(energy = 5.0, mass = 0.0) } // energy needs mass to be carried by
        spall(energy = 0.0, mass = 0.0)
        assertFailsWith<IllegalArgumentException> { BlastSource(V3.ZERO, -1.0) }
        assertFailsWith<IllegalArgumentException> { BlastSource(V3.ZERO, Double.NaN) }
    }

    @Test
    fun `tnt mass is converted to joules explicitly`() {
        assertEquals(4.184e6, EnergyAccounting.blastJ(BlastSource(V3.ZERO, 1.0)), 1.0)
        assertEquals(0.0, EnergyAccounting.blastJ(null), 0.0)
    }

    @Test
    fun `an energy budget separates available energy from what is made`() {
        val sound = EnergyBudget(100.0, 50.0, 60.0, 40.0, 30.0, 10.0)
        assertTrue(sound.isSound)
        assertEquals(10.0, sound.lostJ, 1e-9)
        assertTrue(!EnergyBudget(100.0, 0.0, 60.0, 40.0, 30.0, 10.0).isSound) // 140 out of 100 in
    }

    @Test
    fun `an effect result says explicitly what happened to the projectile`() {
        assertEquals(ProjectileChange.Untouched, EffectInteractionResult.untouched().projectile)
        assertEquals(ProjectileChange.Destroyed, EffectInteractionResult.destroyed().projectile)
        assertEquals(ProjectileChange.Replaced(ok), EffectInteractionResult.replaced(ok).projectile)
        assertTrue(EffectInteractionResult.untouched(consume = true).consumeRuntimeEffect)
    }

    @Test
    fun `energy accounting flags created energy and tolerates float noise`() {
        assertTrue(EnergyAccounting.createsEnergy(100.0, 101.0))
        assertTrue(!EnergyAccounting.createsEnergy(100.0, 100.0 * (1 + 1e-9)))
        assertTrue(!EnergyAccounting.createsEnergy(100.0, 90.0))
    }
}
