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
        assertFailsWith<IllegalArgumentException> { ok.copy(velocity = V3(Double.NaN, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(position = V3(0.0, Double.POSITIVE_INFINITY, 0.0)) }
    }

    @Test
    fun `speed is never negative and a spent projectile is allowed`() {
        assertTrue(ok.speedMps >= 0.0)
        assertEquals(0.0, ok.copy(massRemainingKg = 0.0, integrity = 0.0).kineticEnergyJ, 0.0)
    }

    @Test
    fun `a penetration result must be consistent with its outcome`() {
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.PERFORATED, null, 1.0, null, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.PERFORATED, ok, 1.0, null, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.RICOCHET, null, 1.0, null, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult(PenetrationOutcome.STOPPED, ok, 1.0, null, null, null) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult.stopped(-1.0) }
    }

    @Test
    fun `spall and blast reject negative quantities`() {
        assertFailsWith<IllegalArgumentException> { SpallSource(V3.ZERO, V3(1.0, 0.0, 0.0), 0.3, -1.0, 0.001, Fx.STEEL) }
        assertFailsWith<IllegalArgumentException> { SpallSource(V3.ZERO, V3(1.0, 0.0, 0.0), 0.3, 1.0, -0.001, Fx.STEEL) }
        assertFailsWith<IllegalArgumentException> { BlastSource(V3.ZERO, -1.0) }
    }

    @Test
    fun `an effect result with nothing left cannot ask to continue`() {
        assertFailsWith<IllegalArgumentException> { EffectInteractionResult(null, consumeRuntimeEffect = false, continueTraversal = true) }
    }

    @Test
    fun `energy accounting flags created energy and tolerates float noise`() {
        assertTrue(EnergyAccounting.createsEnergy(100.0, 101.0))
        assertTrue(!EnergyAccounting.createsEnergy(100.0, 100.0 * (1 + 1e-9)))
        assertTrue(!EnergyAccounting.createsEnergy(100.0, 90.0))
    }
}
