// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraversalTest {
    private fun run(model: PenetratorModel, vararg e: ArmorElement, projectile: ProjectileState = Fx.headOn(), runtime: ArmorRuntimeView = ArmorRuntimeView.PRISTINE, effect: ArmorEffectModel? = null, seed: Long = 0L) =
        Fx.traverser(model, effect).traverse(Fx.construction(*e).stack(Fx.facingMinusX), projectile, runtime, seed)

    // A. RHA -> RHA: the residual of the first layer is exactly the input of the second
    @Test
    fun `residual of one layer is the input of the next`() {
        val m = Fx.ThicknessModel()
        val r = run(m, Fx.solid(0.1), Fx.solid(0.1))
        assertEquals(2, m.inputs.size)
        assertEquals(1000.0, m.inputs[0].speedMps, 1e-9)
        assertEquals(900.0, m.inputs[1].speedMps, 1e-9)
        assertEquals(0.1, m.inputs[1].deformation, 1e-12) // the worn state, not the original definition
        assertEquals(TraversalOutcome.EXITED_CONSTRUCTION, r.outcome)
        assertEquals(800.0, r.residual!!.speedMps, 1e-9)
        assertEquals(0.2, r.residual!!.deformation, 1e-12)
    }

    @Test
    fun `energy is conserved across the traversal`() {
        val start = Fx.headOn()
        val r = run(Fx.ThicknessModel(), Fx.solid(0.1), Fx.solid(0.1), projectile = start)
        assertEquals(start.kineticEnergyJ, r.residual!!.kineticEnergyJ + r.state.depositedEnergyJ, start.kineticEnergyJ * 1e-9)
    }

    // B. RHA -> gap -> RHA: the gap distance counts
    @Test
    fun `gap distance is part of the accumulated distance`() {
        val r = run(Fx.ThicknessModel(), Fx.solid(0.05), ArmorElement.Gap(0.3), Fx.solid(0.05))
        assertEquals(0.05 + 0.3 + 0.05, r.state.accumulatedDistanceM, 1e-9)
        assertEquals(listOf(EventKind.LAYER_SOLVED, EventKind.GAP_CROSSED, EventKind.LAYER_SOLVED), r.events.map { it.kind })
    }

    @Test
    fun `an oblique ray flies a longer path through layers and gap`() {
        val r = run(Fx.ThicknessModel(), Fx.solid(0.05), ArmorElement.Gap(0.3), Fx.solid(0.05), projectile = Fx.oblique(PI / 3))
        assertEquals(0.05 / 0.5 + 0.3 / 0.5 + 0.05 / 0.5, r.state.accumulatedDistanceM, 1e-6)
    }

    @Test
    fun `flying through a gap counts towards the fuze arming distance`() {
        val def = Fx.fuzed(arming = 0.25)
        val r = Fx.traverser(Fx.ThicknessModel(), catalog = Fx.Catalog(def)).traverse(
            Fx.construction(Fx.solid(0.05), ArmorElement.Gap(0.3), Fx.solid(0.05)).stack(Fx.facingMinusX),
            Fx.headOn(def = def),
        )
        assertEquals(FuzePhase.ARMED, r.residual!!.fuze.phase)
        val short = Fx.traverser(Fx.ThicknessModel(), catalog = Fx.Catalog(def)).traverse(
            Fx.construction(Fx.solid(0.05), Fx.solid(0.05)).stack(Fx.facingMinusX), Fx.headOn(def = def),
        )
        assertEquals(FuzePhase.SAFE, short.residual!!.fuze.phase)
    }

    @Test
    fun `the impact angle is derived again at every layer`() {
        val m = Fx.ThicknessModel()
        // element 0 faces -x, element 2 is tilted 30 degrees away from that
        val tilted = V3(-Math.cos(PI / 6), Math.sin(PI / 6), 0.0)
        val surface = SurfaceModel { index, _ -> if (index == 0) V3(-1.0, 0.0, 0.0) else tilted }
        val r = Fx.traverser(m).traverse(Fx.construction(Fx.solid(0.01), ArmorElement.Gap(0.1), Fx.solid(0.01)).stack(surface), Fx.headOn())
        val angles = r.events.filter { it.kind == EventKind.LAYER_SOLVED }.map { it.localAngleRad!! }
        assertEquals(0.0, angles[0], 1e-9)
        assertEquals(PI / 6, angles[1], 1e-6)
    }

    // C. effect package: deterministic hook order
    @Test
    fun `effect hooks run in the fixed order around each layer`() {
        val log = ArrayList<String>()
        val effect = Fx.RecordingEffect(EffectPhase.entries.toSet(), log)
        val m = Fx.ThicknessModel(log)
        run(m, Fx.pack(0.01, 0.02), effect = effect)
        assertEquals(
            listOf("BEFORE_PACKAGE", "BEFORE_LAYER(0)", "layer(0.01)", "AFTER_LAYER(0)", "BEFORE_LAYER(1)", "layer(0.02)", "AFTER_LAYER(1)", "AFTER_PACKAGE"),
            log,
        )
    }

    @Test
    fun `the same input gives the same order and result every time`() {
        fun once(): Pair<List<String>, TraversalResult> {
            val log = ArrayList<String>()
            val r = run(Fx.ThicknessModel(log), Fx.pack(0.01, 0.02), Fx.solid(0.01), effect = Fx.RecordingEffect(EffectPhase.entries.toSet(), log), seed = 42L)
            return log to r
        }
        val (l1, r1) = once()
        val (l2, r2) = once()
        assertEquals(l1, l2)
        assertEquals(r1, r2)
    }

    @Test
    fun `an effect is only called for the phases it asks for`() {
        val log = ArrayList<String>()
        run(Fx.ThicknessModel(log), Fx.pack(0.01, 0.01), effect = Fx.RecordingEffect(setOf(EffectPhase.BEFORE_LAYER), log))
        assertEquals(listOf("BEFORE_LAYER(0)", "layer(0.01)", "BEFORE_LAYER(1)", "layer(0.01)"), log)
    }

    @Test
    fun `a stopped layer in a package still gets its after hook, and the rest of the package is skipped`() {
        val log = ArrayList<String>()
        val r = run(Fx.ThicknessModel(log), Fx.pack(0.01, 5.0, 0.01), effect = Fx.RecordingEffect(EffectPhase.entries.toSet(), log))
        assertEquals(TraversalOutcome.STOPPED_IN_ARMOR, r.outcome)
        assertEquals(
            listOf("BEFORE_PACKAGE", "BEFORE_LAYER(0)", "layer(0.01)", "AFTER_LAYER(0)", "BEFORE_LAYER(1)", "layer(5.0)", "AFTER_LAYER(1)"),
            log, // no AFTER_PACKAGE: the package was not passed
        )
    }

    @Test
    fun `using up an effect is reported, not done, and only commit changes the vehicle`() {
        val log = ArrayList<String>()
        val effect = Fx.RecordingEffect(setOf(EffectPhase.BEFORE_PACKAGE), log) { EffectInteractionResult(it.context.projectile, consumeRuntimeEffect = true) }
        val vehicleA = ArmorRuntimeState()
        val vehicleB = ArmorRuntimeState()
        val slot = EffectSlot(Ident("test", "c"), 0)
        val r = run(Fx.ThicknessModel(), Fx.pack(0.01), runtime = vehicleA, effect = effect)
        assertEquals(listOf(slot), r.state.consumedEffects)
        assertEquals(EffectRuntimeState.INTACT, vehicleA.effectState(slot)) // the calculation changed nothing
        vehicleA.commit(r)
        assertEquals(EffectRuntimeState.SPENT, vehicleA.effectState(slot))
        assertEquals(EffectRuntimeState.INTACT, vehicleB.effectState(slot)) // another vehicle with the same construction is untouched
        // and a spent tile no longer reacts, its layers are still there
        log.clear()
        val m = Fx.ThicknessModel(log)
        val again = run(m, Fx.pack(0.01), runtime = vehicleA, effect = effect)
        assertEquals(listOf("layer(0.01)"), log)
        assertTrue(again.events.any { it.kind == EventKind.EFFECT_SKIPPED_SPENT })
        assertEquals(TraversalOutcome.EXITED_CONSTRUCTION, again.outcome)
    }

    @Test
    fun `an effect can end the traversal`() {
        val effect = Fx.RecordingEffect(setOf(EffectPhase.BEFORE_PACKAGE), ArrayList()) { EffectInteractionResult(null, consumeRuntimeEffect = true, continueTraversal = false) }
        val m = Fx.ThicknessModel()
        val r = run(m, Fx.pack(0.01), Fx.solid(0.01), effect = effect)
        assertEquals(TraversalOutcome.DEFEATED_BY_EFFECT, r.outcome)
        assertNull(r.residual)
        assertEquals(0, m.inputs.size)
    }

    // D. STOPPED: nothing after it is called
    @Test
    fun `layers after a stopped layer are not called`() {
        val m = Fx.ThicknessModel()
        val r = run(m, Fx.solid(0.1), Fx.solid(5.0), Fx.solid(0.1))
        assertEquals(2, m.inputs.size)
        assertEquals(TraversalOutcome.STOPPED_IN_ARMOR, r.outcome)
        assertEquals(PenetrationOutcome.STOPPED, r.lastLayerOutcome)
        assertNull(r.residual)
    }

    // E. RICOCHET: ends, hands back the new state
    @Test
    fun `a ricochet ends the traversal and returns the deflected projectile`() {
        val m = Fx.RicochetModel()
        val r = run(m, Fx.solid(0.1), Fx.solid(0.1))
        assertEquals(1, m.calls)
        assertEquals(TraversalOutcome.RICOCHETED, r.outcome)
        val out = assertNotNull(r.residual)
        assertEquals(500.0, out.velocity.x, 1e-9)
        assertEquals(0.1, out.velocity.y, 1e-9)
    }

    // F. internal space: ends the armor traversal and says so
    @Test
    fun `reaching the internal space ends the armor traversal`() {
        val m = Fx.ThicknessModel()
        val r = run(m, Fx.solid(0.1), ArmorElement.InternalSpace(1.0))
        assertEquals(TraversalOutcome.ENTERED_INTERNAL_SPACE, r.outcome)
        assertEquals(TraversalPhase.INTERNAL_SPACE, r.state.phase)
        assertEquals(EventKind.INTERNAL_SPACE_ENTERED, r.events.last().kind)
        assertEquals(900.0, r.residual!!.speedMps, 1e-9)
        assertEquals(1, m.inputs.size)
    }

    @Test
    fun `a projectile stopped before the internal space never gets there`() {
        val r = run(Fx.ThicknessModel(), Fx.solid(5.0), ArmorElement.InternalSpace(1.0))
        assertEquals(TraversalOutcome.STOPPED_IN_ARMOR, r.outcome)
        assertEquals(TraversalPhase.ARMOR_STACK, r.state.phase)
    }

    // models must not create energy
    @Test
    fun `a penetrator model that creates energy is rejected`() {
        val cheat = object : PenetratorModel {
            override val id = Fx.MODEL
            override fun solve(context: ImpactContext, layer: ArmorLayer): PenetrationResult {
                val p = context.projectile
                val faster = p.withSpeed(p.speedMps * 2)
                return PenetrationResult.perforated(faster, 0.0, p.position)
            }
        }
        assertFailsWith<IllegalStateException> { run(cheat, Fx.solid(0.1)) }
    }

    @Test
    fun `an effect model that creates energy is rejected`() {
        val effect = Fx.RecordingEffect(setOf(EffectPhase.BEFORE_PACKAGE), ArrayList()) {
            EffectInteractionResult(it.context.projectile.withSpeed(it.context.projectile.speedMps * 2), consumeRuntimeEffect = false)
        }
        assertFailsWith<IllegalStateException> { run(Fx.ThicknessModel(), Fx.pack(0.01), effect = effect) }
    }

    // determinism: randomness only from the seed
    @Test
    fun `every layer gets its own reproducible seed derived from the shot seed`() {
        val a = Fx.ThicknessModel()
        run(a, Fx.solid(0.01), Fx.solid(0.01), seed = 7L)
        val b = Fx.ThicknessModel()
        run(b, Fx.solid(0.01), Fx.solid(0.01), seed = 7L)
        val c = Fx.ThicknessModel()
        run(c, Fx.solid(0.01), Fx.solid(0.01), seed = 8L)
        assertEquals(a.seeds, b.seeds)
        assertFalse(a.seeds == c.seeds)
        assertFalse(a.seeds[0] == a.seeds[1])
    }

    @Test
    fun `the energy balance of a sound result is not negative`() {
        val start = Fx.headOn()
        val ctx = ImpactContext.of(start, Fx.Catalog(), V3.ZERO, V3(-1.0, 0.0, 0.0))
        val result = Fx.ThicknessModel().solve(ctx, Fx.layer(0.1))
        assertTrue(EnergyAccounting.balance(start.kineticEnergyJ, result) > -start.kineticEnergyJ * 1e-9)
    }
}
