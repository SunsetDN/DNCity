// AGENT-DONE(claude): full-caliber-ap-demarre
package io.github.jwyoon1220.dncity.ballistics.terminal.fullcaliber

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorEffectSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorConstruction
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorMaterial
import io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsCatalog
import io.github.jwyoon1220.dncity.ballistics.terminal.EnergyBudget
import io.github.jwyoon1220.dncity.ballistics.terminal.ExternalBallisticsSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.ImpactContext
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerGeometry
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerRole
import io.github.jwyoon1220.dncity.ballistics.terminal.MaterialClass
import io.github.jwyoon1220.dncity.ballistics.terminal.PayloadSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetrationOutcome
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileDefinition
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileState
import io.github.jwyoon1220.dncity.ballistics.terminal.ResistancePreset
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Stabilization
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Fixtures only. [FIXTURE_MASS_KG] is NOT a confirmed M318A1 mass (see UNVERIFIED_FIXTURE_MASS): it only lets the calibration
 * machinery run. Nothing here may move into src/main/resources.
 */
private const val FIXTURE_MASS_KG = 10.93 // UNVERIFIED_FIXTURE_MASS: ~24.1 lb, assembly boundary not confirmed
private const val DIAMETER_M = 0.09

private val PROJECTILE = Ident("test", "m318a1_like")
private val RHA = Ident("test", "rha")
private val PRESET_ID = Ident("test", "rha_m318a1_like_fixture")

private fun definition(
    geometry: Geometry = Geometry.OGIVE,
    payload: PayloadSpec? = null,
    id: Ident = PROJECTILE,
    mass: Double = FIXTURE_MASS_KG,
) = ProjectileDefinition(
    id = id, gunCaliberM = DIAMETER_M, projectileDiameterM = DIAMETER_M, massKg = mass, geometry = geometry,
    stabilization = Stabilization.SPIN, penetrator = null, payload = payload,
    external = ExternalBallisticsSpec(0.3, 0.0), terminalModel = FullCaliberApModel.ID,
)

private class ApCatalog(val def: ProjectileDefinition, val mat: ArmorMaterial) : BallisticsCatalog {
    override fun projectile(id: Ident) = def
    override fun material(id: Ident) = mat
    override fun preset(id: Ident): ResistancePreset = throw UnsupportedOperationException()
    override fun effect(id: Ident): ArmorEffectSpec = throw UnsupportedOperationException()
    override fun construction(id: Ident): ArmorConstruction = throw UnsupportedOperationException()
}

private fun material(resistance: Map<Ident, Ident> = mapOf(FullCaliberApModel.ID to PRESET_ID)) =
    ArmorMaterial(RHA, MaterialClass.METAL, 7850.0, 300.0, resistance, SpallSpec(false, 0.0))

private fun layer(thicknessM: Double) = ArmorLayer(RHA, thicknessM, LayerGeometry.FLAT, LayerRole.ARMOR)

private fun loadTableA7(): List<CalibrationPoint> {
    val csv = File("docs/ballistics/data/mil-dtl-12560k-appendix-a-ap-tables.csv")
    return csv.readLines().filter { it.startsWith("A-VII,") }.map {
        val c = it.split(',')
        CalibrationPoint(c[3].toDouble() * 0.0254, c[4].toDouble() * 0.3048)
    }
}

private val IMPERIAL_PROFILE = DeMarreProfile.fromImperial(0.75, 0.70, 0.50, Math.pow(10.0, 3.00945))

private fun fixturePresetJson(
    k: Double,
    thickness: Pair<Double, Double>,
    ratio: Pair<Double, Double>,
    mutate: (JsonObject) -> Unit = {},
): JsonObject {
    val json = JsonParser.parseString(
        """
        {
          "calibration_coefficient": $k,
          "de_marre": { "diameter_exponent": 0.75, "thickness_exponent": 0.70, "mass_exponent": 0.50,
                        "constant_log10": 3.00945, "constant_units": "IMPERIAL_FTS_IN_LB" },
          "valid_ranges": {
            "thickness_m": { "min": ${thickness.first}, "max": ${thickness.second}, "extrapolation_margin": 0.05 },
            "diameter_m": { "min": $DIAMETER_M, "max": $DIAMETER_M, "extrapolation_margin": 0.10 },
            "mass_kg": { "min": $FIXTURE_MASS_KG, "max": $FIXTURE_MASS_KG, "extrapolation_margin": 0.10 },
            "diameter_over_thickness": { "min": ${ratio.first}, "max": ${ratio.second}, "extrapolation_margin": 0.05 }
          },
          "normal_tolerance_deg": 0.5,
          "allowed_projectiles": ["test:m318a1_like"],
          "ballistic_limit_definition": "PROTECTION_LIMIT",
          "residual_strategy": "NO_PLUG_ENERGY_UPPER_BOUND",
          "provenance": {
            "source_id": "mil-dtl-12560k-tbl-a-vii", "title": "MIL-DTL-12560K Table A-VII (fixture use)",
            "original_document": "MIL-DTL-12560K, 07 Dec 2013, Appendix A, Table A-VII", "access_copy": null,
            "armor_standard": "MIL-DTL-12560K", "plate_class": "1&3",
            "ballistic_limit_definition": "PROTECTION_LIMIT",
            "projectile": { "designation": "90 mm M318A1 AP", "mass_kg": $FIXTURE_MASS_KG,
                            "mass_source": "UNVERIFIED_FIXTURE_MASS", "mass_boundary": "unknown",
                            "mass_verification": "UNVERIFIED_FIXTURE" },
            "fit_method": "log-space least squares, exponents fixed", "sample_count": 212, "independent_samples": null,
            "rms_residual_pct": null,
            "systematic_uncertainty_notes": ["PROTECTION_LIMIT counts armor fragments as perforation; PERFORATED means projectile perforation",
                                             "table is acceptance minima, piecewise linear"]
          }
        }
        """.trimIndent(),
    ).asJsonObject
    mutate(json)
    return json
}

class FullCaliberApModelTest {
    private val points = loadTableA7()
    private val fit = DeMarreCalibrator.fit(IMPERIAL_PROFILE, DIAMETER_M, FIXTURE_MASS_KG, points)
    private val tMin = points.minOf { it.thicknessM }
    private val tMax = points.maxOf { it.thicknessM }
    private val ratio = (DIAMETER_M / tMax) to (DIAMETER_M / tMin)

    private val preset = FullCaliberApPreset.fromJson(PRESET_ID, fixturePresetJson(fit.coefficient, tMin to tMax, ratio), requireVerifiedMass = false)
    private val model = FullCaliberApModel(mapOf(PRESET_ID to preset))

    private fun context(
        speed: Double,
        angleRad: Double = 0.0,
        def: ProjectileDefinition = definition(),
        mat: ArmorMaterial = material(),
        mass: Double = def.massKg,
    ): ImpactContext {
        val dir = V3(cos(angleRad), sin(angleRad), 0.0)
        val state = ProjectileState.launch(def, V3.ZERO, dir, speed).copy(massRemainingKg = mass)
        return ImpactContext.of(state, ApCatalog(def, mat), V3.ZERO, V3(-1.0, 0.0, 0.0))
    }

    private val midT = (tMin + tMax) / 2

    private fun vbl(t: Double, m: Double = FIXTURE_MASS_KG, d: Double = DIAMETER_M) = preset.ballisticLimitMps(d, t, m)

    // --- historical reproduction ---

    @Test
    fun `reproduces the 1937 manual's printed Problem II and its SI conversion`() {
        // Problem II: 10 in projectile, 500 lb, 15.77 in plate, printed result 1772 ft/s.
        val ftS = 10.0.let { Math.pow(10.0, 3.00945) * Math.pow(it, 0.75) * Math.pow(15.77, 0.70) * Math.pow(500.0, -0.5) }
        assertEquals(1772.0, ftS, 0.5)
        val si = IMPERIAL_PROFILE.baselineVelocityMps(10.0 * 0.0254, 15.77 * 0.0254, 500.0 * 0.45359237)
        assertEquals(ftS * 0.3048, si, 1e-6 * si)
    }

    // --- calibration (self-consistency of a fixture, NOT validation) ---

    @Test
    fun `fixture calibration is only a self-consistency check of a piecewise-linear table`() {
        assertEquals(212, fit.rowCount)
        assertEquals(null, fit.independentSamples)
        assertTrue(fit.minResidualPct > -5.0 && fit.maxResidualPct < 5.0, "residuals ${fit.minResidualPct}..${fit.maxResidualPct}")
        assertTrue(fit.rmsResidualPct < 3.0, "rms ${fit.rmsResidualPct}")
        // coefficient scales with sqrt(mass): the mass behind it matters
        val heavier = DeMarreCalibrator.fit(IMPERIAL_PROFILE, DIAMETER_M, FIXTURE_MASS_KG * 1.21, points).coefficient
        assertEquals(1.1, heavier / fit.coefficient, 1e-9)
    }

    // --- regime ---

    @Test
    fun `in range, extrapolated and out of model are three distinct answers`() {
        val inRange = model.evaluate(context(vbl(midT) * 1.5), layer(midT))
        assertIs<ModelEvaluation.Resolved>(inRange)
        assertEquals(ModelRegime.IN_RANGE, inRange.report.regime)

        val extrapolatedT = tMax * 1.03
        val extra = model.evaluate(context(vbl(extrapolatedT) * 1.5), layer(extrapolatedT))
        assertIs<ModelEvaluation.Resolved>(extra)
        assertEquals(ModelRegime.EXTRAPOLATED, extra.report.regime)
        assertTrue(ModelDiagnostic.EXTRAPOLATED_AXIS in extra.diagnostics)

        val farT = tMax * 1.2
        val out = model.evaluate(context(3000.0), layer(farT))
        assertIs<ModelEvaluation.Unresolved>(out)
        assertEquals(ModelRegime.OUT_OF_MODEL, out.report.regime)
    }

    @Test
    fun `nothing is clamped into range`() {
        val t = tMax * 1.03
        val extra = model.evaluate(context(5000.0), layer(t))
        assertIs<ModelEvaluation.Resolved>(extra)
        assertEquals(vbl(t), extra.ballisticLimitMps, 1e-9)
        assertNotEquals(vbl(tMax), extra.ballisticLimitMps)
    }

    @Test
    fun `anything outside the scope is Unresolved and never throws`() {
        val l = layer(midT)
        fun unresolved(c: ImpactContext, layer: ArmorLayer = l) = assertIs<ModelEvaluation.Unresolved>(model.evaluate(c, layer))

        unresolved(context(3000.0, angleRad = Math.toRadians(30.0)))
        unresolved(context(3000.0, angleRad = Math.toRadians(1.0)))
        unresolved(context(3000.0, def = definition(id = Ident("test", "m82_apc_like"))))
        unresolved(context(3000.0, def = definition(geometry = Geometry.LONG_ROD)))
        unresolved(context(3000.0, def = definition(geometry = Geometry.SHAPED_CHARGE_CONE)))
        unresolved(context(3000.0, def = definition(payload = PayloadSpec(1.0, null, null, null))))
        unresolved(context(3000.0, mat = material(emptyMap())))
        unresolved(context(3000.0, mat = material(mapOf(FullCaliberApModel.ID to Ident("test", "no_such_preset")))))
        unresolved(context(3000.0, mass = FIXTURE_MASS_KG * 2))
        assertEquals(ModelRegime.OUT_OF_MODEL, model.supports(context(3000.0, angleRad = 0.5), l).regime)
    }

    @Test
    fun `a nearly normal impact inside the tolerance is accepted`() {
        val r = model.evaluate(context(vbl(midT) * 1.5, angleRad = Math.toRadians(0.2)), layer(midT))
        assertIs<ModelEvaluation.Resolved>(r)
    }

    // --- NO_PLUG_ENERGY_UPPER_BOUND ---

    @Test
    fun `at or below the ballistic limit the projectile is stopped`() {
        val v = vbl(midT)
        for (speed in listOf(v * 0.5, v * 0.999, v)) {
            val r = model.evaluate(context(speed), layer(midT))
            assertIs<ModelEvaluation.Resolved>(r)
            assertEquals(PenetrationOutcome.STOPPED, r.result.outcome)
        }
    }

    @Test
    fun `residual speed is the energy upper bound and the layer takes exactly the limit energy`() {
        val v = vbl(midT)
        val vi = v * 1.4
        val r = model.evaluate(context(vi), layer(midT))
        assertIs<ModelEvaluation.Resolved>(r)
        assertEquals(PenetrationOutcome.PERFORATED, r.result.outcome)
        assertEquals(Math.sqrt(vi * vi - v * v), r.result.residual!!.speedMps, 1e-9)
        assertEquals(0.5 * FIXTURE_MASS_KG * v * v, r.result.depositedEnergyJ, 1e-6 * FIXTURE_MASS_KG * v * v)
        assertEquals(V3(midT, 0.0, 0.0).x, r.result.perforationPoint!!.x, 1e-12)
        assertTrue(ModelDiagnostic.RESIDUAL_MODEL_UNCALIBRATED in r.diagnostics)
        assertEquals(BallisticLimitDefinition.PROTECTION_LIMIT, r.limitDefinition)
    }

    @Test
    fun `energy is never created over fuzzed inputs`() {
        val rnd = Random(42)
        repeat(1000) {
            val t = tMin * 0.9 + rnd.nextDouble() * (tMax * 1.1 - tMin * 0.9)
            val speed = vbl(t) * (0.1 + rnd.nextDouble() * 3.0)
            val ctx = context(speed)
            val r = model.evaluate(ctx, layer(t))
            if (r is ModelEvaluation.Resolved) {
                val budget = EnergyBudget.ofLayer(ctx.projectile.kineticEnergyJ, r.result)
                assertTrue(budget.isSound, "created energy at t=$t v=$speed: $budget")
                assertTrue(r.result.depositedEnergyJ >= 0.0)
                assertTrue((r.result.residual?.speedMps ?: 0.0) <= speed)
                assertTrue(ModelDiagnostic.RESIDUAL_MODEL_UNCALIBRATED in r.diagnostics)
            }
        }
    }

    // --- properties ---

    @Test
    fun `ballistic limit grows with thickness and diameter and falls with mass`() {
        assertTrue(vbl(midT * 1.1) > vbl(midT))
        assertTrue(vbl(midT, d = DIAMETER_M * 1.1) > vbl(midT))
        assertTrue(vbl(midT, m = FIXTURE_MASS_KG * 1.1) < vbl(midT))
    }

    @Test
    fun `the same context gives the same result`() {
        val ctx = context(vbl(midT) * 1.3)
        assertEquals(model.evaluate(ctx, layer(midT)), model.evaluate(ctx, layer(midT)))
    }

    // --- loader rules ---

    @Test
    fun `a preset needs provenance, margins and a calibration coefficient`() {
        fun load(mutate: (JsonObject) -> Unit, verified: Boolean = false) =
            FullCaliberApPreset.fromJson(PRESET_ID, fixturePresetJson(fit.coefficient, tMin to tMax, ratio, mutate), requireVerifiedMass = verified)
        assertFailsWith<IllegalArgumentException> { load({ it.remove("provenance") }) }
        assertFailsWith<IllegalArgumentException> { load({ it.getAsJsonObject("valid_ranges").getAsJsonObject("thickness_m").remove("extrapolation_margin") }) }
        assertFailsWith<IllegalArgumentException> { load({ it.add("armor_coefficient", it.get("calibration_coefficient")) }) }
        assertFailsWith<IllegalArgumentException> { load({ it.addProperty("residual_strategy", "RECHT_IPSON") }) }
        assertFailsWith<IllegalArgumentException> { load({ it.addProperty("normal_tolerance_deg", 5.0) }) }
        assertFailsWith<IllegalArgumentException> { load({ it.getAsJsonObject("provenance").add("systematic_uncertainty_notes", com.google.gson.JsonArray()) }) }
    }

    @Test
    fun `a production preset is refused while the projectile mass is not confirmed by two sources`() {
        val json = fixturePresetJson(fit.coefficient, tMin to tMax, ratio)
        val e = assertFailsWith<IllegalArgumentException> { FullCaliberApPreset.fromJson(PRESET_ID, json) }
        assertTrue("TWO_INDEPENDENT_SOURCES" in e.message!!)
    }
}
