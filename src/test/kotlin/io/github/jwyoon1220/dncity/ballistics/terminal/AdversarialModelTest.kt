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
            EffectInteractionResult.replaced(it.context.projectile.withSpeed(it.context.projectile.speedMps * 0.95), spall = listOf(Fx.spall(energyJ = 1e6)))
        }
        val e = assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect, projectile = tenKj) }
        assertTrue("created energy" in e.message!!)
    }

    @Test
    fun `a passive effect that blasts is rejected even when it uses itself up`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(consume = true, blast = BlastSource(V3.ZERO, 0.5)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect, projectile = tenKj) }
    }

    @Test
    fun `an active armor may release its stored energy as a blast within its budget`() {
        // 0.2 kg TNT = 836.8 kJ <= 10 kJ of the projectile + 1 MJ stored
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(consume = true, blast = BlastSource(V3.ZERO, 0.2)) }
        val r = run(Fx.ThicknessModel(), Fx.pack(0.001), effect = effect, spec = Fx.activeEra(1e6), projectile = tenKj)
        assertEquals(1, r.state.blasts.size)
        assertEquals(listOf(EffectSlot(Ident("test", "c"), 0)), r.state.consumedEffects)
    }

    @Test
    fun `an active armor cannot release more than it stores`() {
        // 0.5 kg TNT = 2.092 MJ > 1 MJ stored + 10 kJ
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(consume = true, blast = BlastSource(V3.ZERO, 0.5)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001), effect = effect, spec = Fx.activeEra(1e6), projectile = tenKj) }
    }

    @Test
    fun `stored energy is only released when the effect uses itself up`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(blast = BlastSource(V3.ZERO, 0.2)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001), effect = effect, spec = Fx.activeEra(1e6), projectile = tenKj) }
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
                EffectInteractionResult.untouched()
            }
            run(Fx.FixedOutcomeModel(outcome), Fx.pack(0.01), effect = effect)
            assertEquals(listOf<PenetrationOutcome?>(outcome), seen, "AFTER_LAYER after $outcome")
        }
    }

    @Test
    fun `the layers verdict outranks an observing effect`() {
        val effect = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { EffectInteractionResult.untouched(consume = true, spall = listOf(Fx.spall(energyJ = 0.0))) }
        val r = run(Fx.FixedOutcomeModel(PenetrationOutcome.STOPPED), Fx.pack(0.01), effect = effect)
        assertEquals(TraversalOutcome.STOPPED_IN_ARMOR, r.outcome) // not DEFEATED_BY_EFFECT
        assertEquals(1, r.state.spall.size) // but what the effect produced is kept
    }

    @Test
    fun `an effect cannot revive a projectile the layer stopped`() {
        for (outcome in listOf(PenetrationOutcome.STOPPED, PenetrationOutcome.PARTIAL, PenetrationOutcome.RICOCHET, PenetrationOutcome.SHATTERED)) {
            val reviver = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { EffectInteractionResult.replaced(Fx.headOn()) }
            val e = assertFailsWith<IllegalStateException> { run(Fx.FixedOutcomeModel(outcome), Fx.pack(0.01), effect = reviver) }
            assertTrue("revive" in e.message!!, outcome.toString())
        }
    }

    @Test
    fun `after a perforation an effect may still change the projectile`() {
        val slower = Fx.RecordingEffect(setOf(EffectPhase.AFTER_LAYER), ArrayList()) { EffectInteractionResult.replaced(it.context.projectile.withSpeed(10.0)) }
        val r = run(Fx.FixedOutcomeModel(PenetrationOutcome.PERFORATED), Fx.pack(0.01), effect = slower)
        assertEquals(10.0, r.residual!!.speedMps, 1e-9)
    }

    // 7. singleUse / consume
    @Test
    fun `an effect that is not single use cannot report consumption`() {
        val effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(consume = true) }
        val e = assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect, spec = Fx.reusable) }
        assertTrue("single use" in e.message!!)
    }

    @Test
    fun `a single use effect is not consumed automatically`() {
        val r = run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched() })
        assertTrue(r.state.consumedEffects.isEmpty())
    }

    // observation semantics: only an explicit Destroyed ends a traversal as DEFEATED_BY_EFFECT
    @Test
    fun `an effect that leaves the projectile untouched never ends the traversal`() {
        val effect = Fx.RecordingEffect(phaseAll, ArrayList()) { EffectInteractionResult.untouched() }
        assertEquals(TraversalOutcome.EXITED_CONSTRUCTION, run(Fx.ThicknessModel(), Fx.pack(0.01, 0.01), effect = effect).outcome)
    }

    @Test
    fun `the verdict of a layer stands when the effect only observes`() {
        val expected = mapOf(
            PenetrationOutcome.STOPPED to TraversalOutcome.STOPPED_IN_ARMOR,
            PenetrationOutcome.PARTIAL to TraversalOutcome.STOPPED_IN_ARMOR,
            PenetrationOutcome.RICOCHET to TraversalOutcome.RICOCHETED,
            PenetrationOutcome.SHATTERED to TraversalOutcome.SHATTERED,
        )
        for ((layerOutcome, traversalOutcome) in expected) {
            val effect = Fx.RecordingEffect(phaseAll, ArrayList()) { EffectInteractionResult.untouched() }
            assertEquals(traversalOutcome, run(Fx.FixedOutcomeModel(layerOutcome), Fx.pack(0.01), effect = effect).outcome, layerOutcome.toString())
        }
    }

    @Test
    fun `only an explicit destroyed result is a defeat by the effect`() {
        val destroyer = effectDoing(EffectPhase.BEFORE_LAYER) { EffectInteractionResult.destroyed() }
        assertEquals(TraversalOutcome.DEFEATED_BY_EFFECT, run(Fx.ThicknessModel(), Fx.pack(0.01), effect = destroyer).outcome)
        val replacer = effectDoing(EffectPhase.BEFORE_LAYER) { EffectInteractionResult.replaced(it.context.projectile.withSpeed(500.0)) }
        assertEquals(TraversalOutcome.EXITED_CONSTRUCTION, run(Fx.ThicknessModel(), Fx.pack(0.01), effect = replacer).outcome)
    }

    @Test
    fun `an observer cannot destroy or replace a projectile that a layer already ended`() {
        val destroyer = effectDoing(EffectPhase.AFTER_LAYER) { EffectInteractionResult.destroyed() }
        assertFailsWith<IllegalStateException> { run(Fx.FixedOutcomeModel(PenetrationOutcome.STOPPED), Fx.pack(0.01), effect = destroyer) }
    }

    // single use: after it has been used up, its later hooks do not run in the same traversal
    private val slot = EffectSlot(Ident("test", "c"), 0)

    private fun consumeAt(phase: EffectPhase, layer: Int?) = { inv: EffectInvocation ->
        if (inv.phase == phase && inv.layerIndex == layer) EffectInteractionResult.untouched(consume = true) else EffectInteractionResult.untouched()
    }

    @Test
    fun `a single use effect that is consumed before the package runs none of its later hooks`() {
        val log = ArrayList<String>()
        val effect = Fx.RecordingEffect(phaseAll, log, consumeAt(EffectPhase.BEFORE_PACKAGE, null))
        val r = run(Fx.ThicknessModel(log), Fx.pack(0.01, 0.02), effect = effect)
        assertEquals(listOf("BEFORE_PACKAGE", "layer(0.01)", "layer(0.02)"), log)
        assertEquals(listOf(slot), r.state.consumedEffects)
        val skipped = r.events.filter { it.kind == EventKind.EFFECT_SKIPPED_CONSUMED_THIS_TRAVERSAL }.map { it.effectPhase to it.layerIndex }
        assertEquals(
            listOf(EffectPhase.BEFORE_LAYER to 0, EffectPhase.AFTER_LAYER to 0, EffectPhase.BEFORE_LAYER to 1, EffectPhase.AFTER_LAYER to 1, EffectPhase.AFTER_PACKAGE to null),
            skipped,
        )
    }

    @Test
    fun `an effect consumed in the middle of the package stops reacting from there on`() {
        val log = ArrayList<String>()
        val effect = Fx.RecordingEffect(phaseAll, log, consumeAt(EffectPhase.BEFORE_LAYER, 0))
        run(Fx.ThicknessModel(log), Fx.pack(0.01, 0.02), effect = effect)
        assertEquals(listOf("BEFORE_PACKAGE", "BEFORE_LAYER(0)", "layer(0.01)", "layer(0.02)"), log)
    }

    @Test
    fun `a consumed effect leaves the armor layers of the package in place`() {
        val m = Fx.ThicknessModel()
        val effect = Fx.RecordingEffect(phaseAll, ArrayList(), consumeAt(EffectPhase.BEFORE_PACKAGE, null))
        val r = run(m, Fx.pack(0.01, 0.02), Fx.solid(0.01), effect = effect)
        assertEquals(3, m.inputs.size) // both plates of the package and the plate behind it were still solved
        assertEquals(1000.0 - 10.0 - 20.0 - 10.0, r.residual!!.speedMps, 1e-9)
        assertEquals(TraversalOutcome.EXITED_CONSTRUCTION, r.outcome)
    }

    // storedEnergyJ is an accounting upper bound
    @Test
    fun `stored energy is an upper bound on what an effect may add, not a gift`() {
        // 10 kJ in the projectile + 1 MJ stored = 1.01 MJ at most. The projectile is destroyed, so everything may go into the
        // blast: 0.24 kg TNT = 1_004_160 J fits, 0.25 kg = 1_046_000 J does not.
        fun blast(kg: Double) = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.destroyed(consume = true, blast = BlastSource(V3.ZERO, kg)) }
        val within = run(Fx.ThicknessModel(), Fx.pack(0.001), effect = blast(0.24), spec = Fx.activeEra(1e6), projectile = tenKj)
        assertEquals(1, within.state.blasts.size)
        // and while the projectile keeps its 10 kJ, only what remains of the budget is left for the blast
        val keeps = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(consume = true, blast = BlastSource(V3.ZERO, 0.24)) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001), effect = keeps, spec = Fx.activeEra(1e6), projectile = tenKj) }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.001), effect = blast(0.25), spec = Fx.activeEra(1e6), projectile = tenKj) }
    }

    @Test
    fun `releasing stored energy credits nothing to the projectile or the armor on its own`() {
        val consumeOnly = effectDoing(EffectPhase.BEFORE_PACKAGE) { EffectInteractionResult.untouched(consume = true) }
        val withEffect = run(Fx.ThicknessModel(), Fx.pack(0.01), effect = consumeOnly, spec = Fx.activeEra(1e6), projectile = tenKj)
        val idle = Fx.RecordingEffect(emptySet(), ArrayList())
        val without = run(Fx.ThicknessModel(), Fx.pack(0.01), effect = idle, projectile = tenKj, spec = Fx.activeEra(1e6))
        assertEquals(without.residual!!.kineticEnergyJ, withEffect.residual!!.kineticEnergyJ, 1e-9)
        assertEquals(without.state.depositedEnergyJ, withEffect.state.depositedEnergyJ, 1e-9)
    }

    // a layer's absorbed energy is not available to an observer
    @Test
    fun `a passive observer cannot turn the energy a layer absorbed into new spall or blast`() {
        val fast = Fx.headOn(speed = 1000.0) // 500 kJ, all of it deposited by a stopping layer
        val spallMaker = effectDoing(EffectPhase.AFTER_LAYER) { EffectInteractionResult.untouched(spall = listOf(Fx.spall(energyJ = 1.0))) }
        val e = assertFailsWith<IllegalStateException> { run(Fx.FixedOutcomeModel(PenetrationOutcome.STOPPED), Fx.pack(0.01), effect = spallMaker, projectile = fast) }
        assertTrue("created energy" in e.message!!)
        val blaster = effectDoing(EffectPhase.AFTER_LAYER) { EffectInteractionResult.untouched(blast = BlastSource(V3.ZERO, 1e-4)) }
        assertFailsWith<IllegalStateException> { run(Fx.FixedOutcomeModel(PenetrationOutcome.PARTIAL), Fx.pack(0.01), effect = blaster, projectile = fast) }
    }

    @Test
    fun `an observer may only use the energy of its own armor`() {
        fun spall(j: Double) = effectDoing(EffectPhase.AFTER_LAYER) { EffectInteractionResult.untouched(consume = true, spall = listOf(Fx.spall(energyJ = j))) }
        val fast = Fx.headOn(speed = 1000.0)
        run(Fx.FixedOutcomeModel(PenetrationOutcome.STOPPED), Fx.pack(0.01), effect = spall(500.0), spec = Fx.activeEra(1e3), projectile = fast)
        assertFailsWith<IllegalStateException> { run(Fx.FixedOutcomeModel(PenetrationOutcome.STOPPED), Fx.pack(0.01), effect = spall(2000.0), spec = Fx.activeEra(1e3), projectile = fast) }
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
            EffectInteractionResult.untouched()
        }
        run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect)
        assertEquals(4, seen.size)
        assertEquals(4, seen.toSet().size)
        assertFalse(seen.contains(0L))
    }
}
