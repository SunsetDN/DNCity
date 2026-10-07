// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fake models that break the rules on purpose: the traversal has to refuse them (or fix what it owns), not pass them on. */
class AdversarialModelTest {
    private val phaseAll = EffectPhase.entries.toSet()

    private fun run(
        model: PenetratorModel,
        vararg e: ArmorElement,
        effect: ArmorEffectModel? = null,
        spec: ArmorEffectSpec = Fx.eraSpec,
        projectile: ProjectileState = Fx.headOn(),
        runtime: ArmorRuntimeView = ArmorRuntimeView.PRISTINE,
    ) = Fx.traverser(model, effect, Fx.Catalog(effect = spec)).traverse(Fx.construction(*e).stack(Fx.facingMinusX), projectile, runtime)

    /** A 1 kg projectile with 10 kJ: speed sqrt(2 * 10000 / 1). */
    private val tenKj = Fx.headOn(speed = Math.sqrt(2 * 10_000.0))

    private fun effectDoing(phase: EffectPhase, f: (EffectInvocation) -> EffectInteractionResult) = Fx.RecordingEffect(setOf(phase), ArrayList(), f)

    // 1. energy sources
    @Test
    fun `a passive effect that turns 10 kJ into 1 MJ of spall is rejected`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) {
            EffectInteractionResult(it.context.projectile.withSpeed(it.context.projectile.speedMps * 0.95), consumeRuntimeEffect = false, generatedSpall = listOf(Fx.spall(energyJ = 1e6)))
        }
        val e = assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect, projectile = tenKj) }
        assertTrue("created energy" in e.message!!)
    }

    @Test
    fun `a passive effect that blasts is rejected even when it uses itself up`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = true, generatedBlast = BlastSource(V3.ZERO, 0.5)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect, projectile = tenKj) }
    }

    @Test
    fun `an active armor may release its stored energy as a blast within its budget`() {
        // 0.2 kg TNT = 836.8 kJ <= 10 kJ of the projectile + 1 MJ stored
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = true, generatedBlast = BlastSource(V3.ZERO, 0.2)) }
        val r = run(Fx.ThicknessModel(), Fx.pack(0.001), effect = effect, spec = Fx.activeEra(1e6), projectile = tenKj)
        assertEquals(1, r.state.blasts.size)
        assertEquals(listOf(EffectSlot(Ident("test", "c"), 0)), r.state.consumedEffects)
    }

    @Test
    fun `an active armor cannot release more than it stores`() {
        // 0.5 kg TNT = 2.092 MJ > 1 MJ stored + 10 kJ
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = true, generatedBlast = BlastSource(V3.ZERO, 0.5)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001), effect = effect, spec = Fx.activeEra(1e6), projectile = tenKj) }
    }

    @Test
    fun `stored energy is only released when the effect uses itself up`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = false, generatedBlast = BlastSource(V3.ZERO, 0.2)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001), effect = effect, spec = Fx.activeEra(1e6), projectile = tenKj) }
    }

    @Test
    fun `an effect cannot release its stored energy twice in one traversal`() {
        val effect = Fx.RecordingEffect(setOf(EffectPhase.BEFORE_LAYER), ArrayList()) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = true) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001, 0.001), effect = effect, spec = Fx.activeEra(1e3)) }
    }

    // 2. canonical position
    @Test
    fun `a perforation puts the residual at the perforation point whatever the solver wrote`() {
        val m = Fx.WrongPositionModel(step = 0.1, residualAt = V3(-5.0, 7.0, 0.0))
        val r = run(m, Fx.solid(0.1), Fx.solid(0.1))
        assertEquals(V3.ZERO, m.inputs[0].position)
        assertEquals(V3(0.1, 0.0, 0.0), m.inputs[1].position) // the second layer starts where the first was left, not at (-5, 7, 0)
        assertEquals(V3(0.2, 0.0, 0.0), r.residual!!.position)
        assertEquals(0.2, r.state.accumulatedDistanceM, 1e-9)
    }

    // 3. path
    @Test
    fun `a perforation point equal to the entry point is an impossible path`() {
        val m = Fx.WrongPositionModel(step = 0.0, residualAt = V3.ZERO)
        assertFailsWith<IllegalStateException> { run(m, Fx.solid(0.1)) }
    }

    @Test
    fun `a non finite perforation point cannot even be built`() {
        assertFailsWith<IllegalArgumentException> { PenetrationResult.perforated(Fx.headOn(), 0.0, V3(Double.NaN, 0.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> { PenetrationResult.perforated(Fx.headOn(), 0.0, V3(0.0, Double.POSITIVE_INFINITY, 0.0)) }
    }

    // 6. AFTER_LAYER for every solved outcome
    @Test
    fun `the after layer hook sees every outcome`() {
        for (outcome in PenetrationOutcome.entries) {
            val seen = ArrayList<PenetrationOutcome?>()
            val effect = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { inv ->
                seen += inv.layerResult?.outcome
                if (inv.isObservationOnly) EffectInteractionResult.observation() else EffectInteractionResult(inv.context.projectile, false)
            }
            run(Fx.FixedOutcomeModel(outcome), Fx.pack(0.01), effect = effect)
            assertEquals(listOf<PenetrationOutcome?>(outcome), seen, "AFTER_LAYER after $outcome")
        }
    }

    @Test
    fun `the layers verdict outranks an observing effect`() {
        val effect = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { EffectInteractionResult.observation(spall = listOf(Fx.spall())) }
        val r = run(Fx.FixedOutcomeModel(PenetrationOutcome.STOPPED), Fx.pack(0.01), effect = effect)
        assertEquals(TraversalOutcome.STOPPED_IN_ARMOR, r.outcome) // not DEFEATED_BY_EFFECT
        assertEquals(1, r.state.spall.size) // but what the effect produced is kept
    }

    @Test
    fun `an effect cannot revive a projectile the layer stopped`() {
        for (outcome in listOf(PenetrationOutcome.STOPPED, PenetrationOutcome.PARTIAL, PenetrationOutcome.RICOCHET, PenetrationOutcome.SHATTERED)) {
            val reviver = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { EffectInteractionResult(Fx.headOn(), false) }
            val e = assertFailsWith<IllegalStateException> { run(Fx.FixedOutcomeModel(outcome), Fx.pack(0.01), effect = reviver) }
            assertTrue("revive" in e.message!!, outcome.toString())
        }
    }

    @Test
    fun `after a perforation an effect may still change the projectile`() {
        val slower = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { EffectInteractionResult(it.context.projectile.withSpeed(10.0), false) }
        val r = run(Fx.FixedOutcomeModel(PenetrationOutcome.PERFORATED), Fx.pack(0.01), effect = slower)
        assertEquals(10.0, r.residual!!.speedMps, 1e-9)
    }

    // 7. singleUse / consume
    @Test
    fun `an effect that is not single use cannot report consumption`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = true) }
        val e = assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect, spec = Fx.reusable) }
        assertTrue("single use" in e.message!!)
    }

    @Test
    fun `a single use effect is not consumed automatically`() {
        val r = run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult(it.context.projectile, false) })
        assertTrue(r.state.consumedEffects.isEmpty())
    }

    // 4/5 are in ModelInvariantTest (axis, deformation)

    // 11. seeds
    private val c = Ident("test", "c")

    @Test
    fun `the same semantic input gives the same seed`() {
        assertEquals(
            ImpactContext.seedFor(7L, c, 2, 1, SeedDomain.PENETRATION),
            ImpactContext.seedFor(7L, c, 2, 1, SeedDomain.PENETRATION),
        )
    }

    @Test
    fun `every part of the meaning changes the seed`() {
        val base = ImpactContext.seedFor(7L, c, 2, 1, SeedDomain.PENETRATION)
        val others = listOf(
            ImpactContext.seedFor(8L, c, 2, 1, SeedDomain.PENETRATION),
            ImpactContext.seedFor(7L, Ident("test", "d"), 2, 1, SeedDomain.PENETRATION),
            ImpactContext.seedFor(7L, c, 3, 1, SeedDomain.PENETRATION),
            ImpactContext.seedFor(7L, c, 2, 2, SeedDomain.PENETRATION),
            ImpactContext.seedFor(7L, c, 2, 1, SeedDomain.SPALL),
        )
        assertEquals(others.size, (others + base).toSet().size - 1)
    }

    @Test
    fun `every domain has its own stream`() {
        val seeds = SeedDomain.entries.map { ImpactContext.seedFor(1L, c, 0, 0, it) }
        assertEquals(SeedDomain.entries.size, seeds.toSet().size)
    }

    @Test
    fun `the phases of one effect get different seeds in the traversal`() {
        val seen = ArrayList<Long>()
        val effect = Fx.RecordingEffect(phaseAll, ArrayList()) { inv ->
            seen += inv.context.seed
            if (inv.isObservationOnly) EffectInteractionResult.observation() else EffectInteractionResult(inv.context.projectile, false)
        }
        run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect)
        assertEquals(4, seen.size)
        assertEquals(4, seen.toSet().size)
        assertFalse(seen.contains(0L))
    }
}
