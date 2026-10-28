// AGENT-DONE(claude): ammo-data-system
package io.github.jwyoon1220.dncity.ballistics.terminal.ammo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.Fx
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerGeometry
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerRole
import io.github.jwyoon1220.dncity.ballistics.terminal.ModelRegistry
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationOutcome
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationResult
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileState
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures in src/test/resources/ammo are FICTIONAL structure examples; no number in them is ballistics data. */
class AmmoSystemTest {
    private fun json(name: String): JsonObject =
        JsonParser.parseReader(javaClass.getResourceAsStream("/ammo/$name.json")!!.reader()).asJsonObject

    private fun load(name: String) = AmmoDefinition.fromJson(Ident("test", name), json(name))

    private val rha = Ident("dncity", "rha")
    private fun plate(mm: Double, material: Ident = rha) = ArmorLayer(material, mm / 1000.0, LayerGeometry.FLAT, LayerRole.ARMOR)

    private fun contextFor(ammo: AmmoDefinition, angleDeg: Double, speed: Double = ammo.projectile.muzzleVelocityMps, mutate: (ProjectileState) -> ProjectileState = { it }): ImpactContext {
        val def = AmmoCompiler.compile(ammo)
        val a = Math.toRadians(angleDeg)
        val state = mutate(ProjectileState.launch(def, V3.ZERO, V3(cos(a), sin(a), 0.0), speed))
        return ImpactContext.of(state, Fx.Catalog(projectile = def), V3.ZERO, V3(-1.0, 0.0, 0.0))
    }

    private fun resolverFor(vararg ammo: AmmoDefinition, solvers: ModelRegistry<AmmoSolver> = builtinSolvers()): AmmoImpactResolver {
        val byId = ammo.associateBy { it.id }
        return AmmoImpactResolver({ byId[it] }, solvers)
    }

    private fun builtinSolvers() = ModelRegistry<AmmoSolver>("test solver") { it.id }.also {
        it.register(TableLimitSolver)
        it.register(FullCaliberApAmmoSolver { emptyMap() })
    }

    // --- parsing and compile -------------------------------------------------------------------------------------------------

    @Test fun `all four example ammo parse and compile to a ProjectileDefinition`() {
        for (n in listOf("ap_example", "apcbc_example", "apfsds_example", "heat_example")) {
            val a = load(n)
            val p = AmmoCompiler.compile(a)
            assertEquals(a.id, p.id)
            assertEquals(a.compat.gunCaliberM, p.gunCaliberM)
            assertEquals(a.projectile.massKg, p.massKg)
            assertEquals(a.terminal.solver, p.terminalModel)
        }
        val fsds = load("apfsds_example")
        assertEquals(0.12, fsds.compat.gunCaliberM, 1e-12)
        assertEquals(0.025, fsds.projectile.penetrator!!.diameterM, 1e-12) // penetrator, body and gun are three different diameters
        assertEquals(0.12, fsds.projectile.diameterM, 1e-12)
        assertEquals(17500.0, fsds.projectile.penetrator!!.densityKgM3, 1e-9)
        val heat = load("heat_example")
        assertNull(AmmoCompiler.compile(heat).penetrator)
        assertEquals(0.09, AmmoCompiler.compile(heat).payload!!.coneDiameterM!!, 1e-12)
        assertEquals(Geometry.OGIVE, AmmoCompiler.compile(heat).geometry)
    }

    @Test fun `table thickness is converted to metres`() {
        val t = load("apcbc_example").table!!
        assertEquals(0.120, t.grid.limitsM[0][0], 1e-12)
        assertEquals(0.030, t.grid.limitsM[2][2], 1e-12)
        assertEquals(Math.toRadians(30.0), t.grid.anglesRad[1], 1e-12)
    }

    private fun mutated(name: String, edit: (JsonObject) -> Unit): JsonObject = json(name).also(edit)

    @Test fun `type specific rules reject wrong data`() {
        // APFSDS rod as wide as the gun
        assertFailsWith<IllegalArgumentException> {
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("apfsds_example") { it.getAsJsonObject("projectile").getAsJsonObject("penetrator").addProperty("diameter_mm", 120) })
        }
        // HEAT without cone
        assertFailsWith<IllegalArgumentException> {
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("heat_example") { it.getAsJsonObject("projectile").getAsJsonObject("payload").remove("cone_diameter_mm") })
        }
        // HEAT with a kinetic penetrator block
        assertFailsWith<IllegalArgumentException> {
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("heat_example") {
                it.getAsJsonObject("projectile").add("penetrator", json("ap_example").getAsJsonObject("projectile").getAsJsonObject("penetrator"))
            })
        }
        // APCBC without cap flags
        assertFailsWith<IllegalArgumentException> {
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("apcbc_example") { it.getAsJsonObject("projectile").addProperty("ballistic_cap", false) })
        }
        // projectile wider than the gun
        assertFailsWith<IllegalArgumentException> {
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("ap_example") { it.getAsJsonObject("compatibility").addProperty("gun_caliber_mm", 50) })
        }
    }

    @Test fun `schema rules reject typos, wrong angle convention and unlimited extrapolation`() {
        assertFailsWith<IllegalArgumentException> { AmmoDefinition.fromJson(Ident("t", "x"), mutated("ap_example") { it.add("penetraton", JsonObject()) }) }
        assertFailsWith<IllegalArgumentException> { AmmoDefinition.fromJson(Ident("t", "x"), mutated("ap_example") { it.addProperty("schema_version", 2) }) }
        assertFailsWith<IllegalArgumentException> { AmmoDefinition.fromJson(Ident("t", "x"), mutated("apcbc_example") { it.getAsJsonObject("penetration").addProperty("angle_convention", "FROM_SURFACE") }) }
        assertFailsWith<IllegalArgumentException> { AmmoDefinition.fromJson(Ident("t", "x"), mutated("apcbc_example") { it.getAsJsonObject("penetration").addProperty("out_of_range", "EXTRAPOLATE") }) }
        assertFailsWith<IllegalArgumentException> { // physics takes no table
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("ap_example") { it.getAsJsonObject("penetration").add("table", json("apcbc_example").getAsJsonObject("penetration").get("table")) })
        }
        assertFailsWith<IllegalArgumentException> { // ragged table
            AmmoDefinition.fromJson(Ident("t", "x"), mutated("apcbc_example") { it.getAsJsonObject("penetration").getAsJsonArray("table")[0].asJsonObject.getAsJsonObject("angles").remove("60") })
        }
    }

    // --- table lookup --------------------------------------------------------------------------------------------------------

    private fun lookup(name: String = "apcbc_example", mutate: (TableMeta) -> TableMeta = { it }, d: Double, deg: Double): TableAnswer {
        val t = load(name).table!!
        return TableLookup.limit(t.grid, mutate(t.meta), d, Math.toRadians(deg))
    }

    @Test fun `grid points and bilinear interpolation`() {
        assertEquals(0.110, (lookup(d = 500.0, deg = 0.0) as TableAnswer.Limit).thicknessM, 1e-12)
        assertEquals(0.115, (lookup(d = 250.0, deg = 0.0) as TableAnswer.Limit).thicknessM, 1e-12)   // 120..110
        assertEquals(0.1075, (lookup(d = 0.0, deg = 15.0) as TableAnswer.Limit).thicknessM, 1e-12)   // 120..95
    }

    @Test fun `out of range is unresolved unless the author chose otherwise`() {
        assertIs<TableAnswer.OutOfRange>(lookup(d = 1500.0, deg = 0.0))
        assertIs<TableAnswer.OutOfRange>(lookup(d = 100.0, deg = 70.0))
        val clamp = lookup(mutate = { it.copy(rangePolicy = RangePolicy(RangeMode.CLAMP, 0.0, 0.0)) }, d = 1500.0, deg = 0.0) as TableAnswer.Limit
        assertEquals(0.098, clamp.thicknessM, 1e-12)
        assertTrue(RangeFlag.DISTANCE_CLAMPED in clamp.flags)
        val ex = RangePolicy(RangeMode.EXTRAPOLATE, 200.0, Math.toRadians(10.0))
        val within = lookup(mutate = { it.copy(rangePolicy = ex) }, d = 1100.0, deg = 0.0) as TableAnswer.Limit
        assertEquals(0.0956, within.thicknessM, 1e-9) // last segment slope -12 mm per 500 m, 100 m beyond
        assertTrue(RangeFlag.DISTANCE_EXTRAPOLATED in within.flags)
        assertIs<TableAnswer.OutOfRange>(lookup(mutate = { it.copy(rangePolicy = ex) }, d = 1300.0, deg = 0.0)) // beyond the stated margin
    }

    // --- resolver: TABLE -----------------------------------------------------------------------------------------------------

    @Test fun `table verdict compares the plate with the limit and gives no invented residual`() {
        val a = load("apcbc_example")
        val r = resolverFor(a)
        val ctx = contextFor(a, 0.0)
        val perf = r.resolve(ctx, plate(97.0), flightDistanceM = 1000.0)
        assertIs<AmmoEvaluation.Resolved>(perf)
        assertEquals(Verdict.PERFORATED, perf.verdict)
        assertNull(perf.result)
        assertEquals(0.098, perf.limitThicknessM!!, 1e-12)
        assertTrue(AmmoDiagnostic.RESIDUAL_NOT_DERIVED_FROM_TABLE in perf.diagnostics)
        assertTrue(AmmoDiagnostic.UNCALIBRATED_TABLE in perf.diagnostics) // the example is FICTIONAL, not production data

        val stop = r.resolve(ctx, plate(120.0), flightDistanceM = 1000.0) as AmmoEvaluation.Resolved
        assertEquals(Verdict.STOPPED, stop.verdict)
        assertEquals(PenetrationOutcome.STOPPED, stop.result!!.outcome)
    }

    @Test fun `angle uses the angle from the armor normal`() {
        val a = load("apcbc_example")
        val r = resolverFor(a)
        // 60 degrees at 0 m: limit 45 mm. A 50 mm plate stops, 40 mm perforates.
        val ctx = contextFor(a, 60.0)
        assertEquals(Verdict.STOPPED, (r.resolve(ctx, plate(50.0), 0.0) as AmmoEvaluation.Resolved).verdict)
        assertEquals(Verdict.PERFORATED, (r.resolve(ctx, plate(40.0), 0.0) as AmmoEvaluation.Resolved).verdict)
    }

    @Test fun `flight distance is derived from speed when the shot does not know it`() {
        val a = load("apcbc_example")
        val r = resolverFor(a)
        val v = ExternalBallistics.speedAtDistance(a.projectile, 500.0)
        assertEquals(500.0, ExternalBallistics.distanceForSpeed(a.projectile, v)!!, 1e-6)
        val e = r.resolve(contextFor(a, 0.0, speed = v), plate(100.0)) as AmmoEvaluation.Resolved
        assertEquals(500.0, e.flightDistanceM!!, 1e-6)
        assertEquals(0.110, e.limitThicknessM!!, 1e-9)
        assertTrue(AmmoDiagnostic.DISTANCE_DERIVED_FROM_SPEED in e.diagnostics)
    }

    @Test fun `uncertainty band is reported`() {
        val a = load("apcbc_example")
        val e = resolverFor(a).resolve(contextFor(a, 0.0), plate(99.0), 1000.0) as AmmoEvaluation.Resolved // limit 98 mm, +-5 %
        assertTrue(AmmoDiagnostic.WITHIN_TABLE_UNCERTAINTY in e.diagnostics)
    }

    @Test fun `other materials are not converted through RHA`() {
        val a = load("apcbc_example")
        val e = resolverFor(a).resolve(contextFor(a, 0.0), plate(10.0, Ident("dncity", "aluminum_5083")), 0.0)
        assertIs<AmmoEvaluation.Unresolved>(e)
        assertEquals(UnresolvedReason.MATERIAL_NOT_REFERENCE, e.reason)
    }

    @Test fun `a worn projectile is not the tested round`() {
        val a = load("apcbc_example")
        val ctx = contextFor(a, 0.0) { it.copy(deformation = 0.3) }
        val e = resolverFor(a).resolve(ctx, plate(50.0), 0.0)
        assertIs<AmmoEvaluation.Unresolved>(e)
        assertEquals(UnresolvedReason.PROJECTILE_NOT_AS_TESTED, e.reason)
    }

    @Test fun `table without reference material or out of table range is unresolved`() {
        val a = load("apcbc_example")
        assertEquals(UnresolvedReason.OUT_OF_TABLE_RANGE, (resolverFor(a).resolve(contextFor(a, 0.0), plate(50.0), 5000.0) as AmmoEvaluation.Unresolved).reason)
        val noRef = a.copy(penetration = (a.penetration as PenetrationSpec.Table).let { it.copy(meta = it.meta.copy(referenceMaterial = null)) })
        assertEquals(UnresolvedReason.TABLE_HAS_NO_REFERENCE_MATERIAL, (resolverFor(noRef).resolve(contextFor(noRef, 0.0), plate(50.0), 0.0) as AmmoEvaluation.Unresolved).reason)
    }

    // --- solvers: never substituted ------------------------------------------------------------------------------------------

    @Test fun `an unimplemented solver is never replaced by another one`() {
        for (n in listOf("apfsds_example", "heat_example")) {
            val a = load(n)
            val e = resolverFor(a).resolve(contextFor(a, 0.0), plate(50.0), 0.0)
            assertIs<AmmoEvaluation.Unresolved>(e)
            assertEquals(UnresolvedReason.UNIMPLEMENTED_SOLVER, e.reason)
        }
        // A TABLE ammo naming an unimplemented solver is unresolved too, even though a table could answer.
        val a = load("apcbc_example").let { it.copy(terminal = it.terminal.copy(solver = Ident("dncity", "full_caliber_capped_ap"))) }
        val e = resolverFor(a).resolve(contextFor(a, 0.0), plate(50.0), 0.0)
        assertEquals(UnresolvedReason.UNIMPLEMENTED_SOLVER, (e as AmmoEvaluation.Unresolved).reason)
    }

    @Test fun `registered physics solver declines when it has no calibration`() {
        val a = load("ap_example")
        val e = resolverFor(a).resolve(contextFor(a, 0.0), plate(50.0), null)
        assertIs<AmmoEvaluation.Unresolved>(e)
        assertEquals(UnresolvedReason.OUT_OF_SOLVER_MODEL, e.reason)
    }

    // --- HYBRID --------------------------------------------------------------------------------------------------------------

    private class FakePhysics(val perforates: Boolean) : AmmoSolver {
        override val id = Ident("test", "fake_physics")
        override val canSolvePhysics = true
        override fun evaluate(context: ImpactContext, layer: ArmorLayer): AmmoEvaluation {
            val p = context.projectile
            return if (!perforates) {
                AmmoEvaluation.Resolved(Verdict.STOPPED, PenetrationResult.stopped(p.kineticEnergyJ), EvaluationSource.PHYSICS, null, null, emptySet())
            } else {
                val res = p.copy(position = p.position + p.velocity.normalize().scale(layer.thicknessM), velocity = p.velocity.scale(0.5))
                AmmoEvaluation.Resolved(Verdict.PERFORATED, PenetrationResult.perforated(res, p.kineticEnergyJ - res.kineticEnergyJ, res.position), EvaluationSource.PHYSICS, null, null, emptySet())
            }
        }
    }

    private fun hybrid(physicsPerforates: Boolean?, policy: ConflictPolicy, tol: Double = 0.05): Pair<AmmoDefinition, AmmoImpactResolver> {
        val base = load("apcbc_example")
        val a = base.copy(
            penetration = PenetrationSpec.Hybrid(base.penetration as PenetrationSpec.Table, tol, policy),
            terminal = base.terminal.copy(solver = Ident("test", "fake_physics")),
        )
        val solvers = ModelRegistry<AmmoSolver>("s") { it.id }
        if (physicsPerforates != null) solvers.register(FakePhysics(physicsPerforates))
        else solvers.register(object : AmmoSolver {
            override val id = Ident("test", "fake_physics")
            override val canSolvePhysics = true
            override fun evaluate(context: ImpactContext, layer: ArmorLayer) = AmmoEvaluation.Unresolved(UnresolvedReason.OUT_OF_SOLVER_MODEL, "no")
        })
        return a to AmmoImpactResolver({ if (it == a.id) a else null }, solvers)
    }

    @Test fun `hybrid agreement takes the more complete physics result`() {
        val (a, r) = hybrid(true, ConflictPolicy.FAIL)
        val e = r.resolve(contextFor(a, 0.0), plate(50.0), 1000.0) as AmmoEvaluation.Resolved
        assertEquals(EvaluationSource.HYBRID, e.source)
        assertEquals(Verdict.PERFORATED, e.verdict)
        assertNotNull(e.result)
        assertTrue(AmmoDiagnostic.RESIDUAL_NOT_DERIVED_FROM_TABLE !in e.diagnostics)
    }

    @Test fun `hybrid conflict is reported and follows the policy`() {
        // 50 mm vs table limit 98 mm: table perforates, physics stops; far beyond the 5 % tolerance.
        val (a, useTable) = hybrid(false, ConflictPolicy.USE_TABLE)
        val e = useTable.resolve(contextFor(a, 0.0), plate(50.0), 1000.0) as AmmoEvaluation.Resolved
        assertEquals(Verdict.PERFORATED, e.verdict)
        assertTrue(AmmoDiagnostic.VERDICT_CONFLICT_TABLE_USED in e.diagnostics)

        val (a2, fail) = hybrid(false, ConflictPolicy.FAIL)
        val f = fail.resolve(contextFor(a2, 0.0), plate(50.0), 1000.0)
        assertEquals(UnresolvedReason.HYBRID_CONFLICT, (f as AmmoEvaluation.Unresolved).reason)

        // Within tolerance of the limit a disagreement is only noted.
        val (a3, strict) = hybrid(false, ConflictPolicy.FAIL)
        val n = strict.resolve(contextFor(a3, 0.0), plate(97.0), 1000.0) as AmmoEvaluation.Resolved
        assertTrue(AmmoDiagnostic.NEAR_LIMIT_DISAGREEMENT in n.diagnostics)
    }

    @Test fun `hybrid says so when physics could not cross-check`() {
        val (a, r) = hybrid(null, ConflictPolicy.FAIL)
        val e = r.resolve(contextFor(a, 0.0), plate(50.0), 1000.0) as AmmoEvaluation.Resolved
        assertTrue(AmmoDiagnostic.PHYSICS_COULD_NOT_CROSS_CHECK in e.diagnostics)
    }

    // --- calibration and validation ------------------------------------------------------------------------------------------

    @Test fun `fictional or unsourced tables are not production`() {
        val a = load("apcbc_example")
        assertEquals(CalibrationStatus.FICTIONAL, AmmoValidation.calibrationOf(a))
        val real = a.copy(provenance = a.provenance.copy(status = ProvenanceStatus.ESTIMATED), penetration = (a.penetration as PenetrationSpec.Table).let {
            it.copy(meta = it.meta.copy(source = DataSource(SourceKind.ESTIMATED, "a citation")))
        })
        assertEquals(CalibrationStatus.PRODUCTION, AmmoValidation.calibrationOf(real))
        val noCriterion = real.copy(penetration = (real.penetration as PenetrationSpec.Table).let { it.copy(meta = it.meta.copy(criterion = null)) })
        assertEquals(CalibrationStatus.UNCALIBRATED, AmmoValidation.calibrationOf(noCriterion))
    }

    @Test fun `validation reports production violations and unknown references`() {
        val a = load("apcbc_example")
        val view = io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsCatalogView(emptyList(), emptyMap(), emptyList(), emptyList())
        val problems = AmmoValidation.validate(listOf(a), view, setOf(AmmoSolvers.TABLE_LIMIT), production = true)
        assertTrue(problems.any { "unknown reference material" in it })
        assertTrue(problems.any { "not allowed in production" in it })
        val warnings = ArrayList<String>()
        val ok = AmmoValidation.validate(listOf(load("heat_example")), view, emptySet(), production = false, warnings = warnings)
        assertTrue(ok.isEmpty())
        assertTrue(warnings.any { "not implemented" in it })
    }
}
