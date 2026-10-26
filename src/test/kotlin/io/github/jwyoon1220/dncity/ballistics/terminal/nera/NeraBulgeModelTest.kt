// AGENT-DONE(claude): nera-bulge-model
package io.github.jwyoon1220.dncity.ballistics.terminal.nera

import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorConstruction
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorEffectSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorElement
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorLayer
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorMaterial
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorRuntimeState
import io.github.jwyoon1220.dncity.ballistics.terminal.ArmorTear
import io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsCatalog
import io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsCatalogView
import io.github.jwyoon1220.dncity.ballistics.terminal.BallisticsValidation
import io.github.jwyoon1220.dncity.ballistics.terminal.EffectSlot
import io.github.jwyoon1220.dncity.ballistics.terminal.ExternalBallisticsSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Fx
import io.github.jwyoon1220.dncity.ballistics.terminal.Geometry
import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerGeometry
import io.github.jwyoon1220.dncity.ballistics.terminal.LayerRole
import io.github.jwyoon1220.dncity.ballistics.terminal.MaterialClass
import io.github.jwyoon1220.dncity.ballistics.terminal.PenetratorSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileDefinition
import io.github.jwyoon1220.dncity.ballistics.terminal.ProjectileState
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallSpec
import io.github.jwyoon1220.dncity.ballistics.terminal.Stabilization
import io.github.jwyoon1220.dncity.ballistics.terminal.TraversalOutcome
import io.github.jwyoon1220.dncity.ballistics.terminal.TraversalResult
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Made-up, easy-to-check numbers; the model itself is uncalibrated, these tests pin its behaviour, not real armor. */
class NeraBulgeModelTest {
    private val steelId = Ident("test", "steel")
    private val rubberId = Ident("test", "rubber")
    private val effectId = Ident("test", "nera")
    private val steel = ArmorMaterial(steelId, MaterialClass.METAL, 7850.0, 300.0, emptyMap(), SpallSpec(true, 1.0))
    private val rubber = ArmorMaterial(rubberId, MaterialClass.ELASTOMER, 1100.0, null, emptyMap(), SpallSpec(false, 0.0))
    private val spec = ArmorEffectSpec(effectId, NeraBulgeModel.ID, emptyMap(), singleUse = false)

    /** 3 kg, 30 mm, 0.6 m long rod. */
    private val rod = ProjectileDefinition(
        id = Fx.PROJECTILE, gunCaliberM = 0.12, projectileDiameterM = 0.03, massKg = 3.0, geometry = Geometry.LONG_ROD,
        stabilization = Stabilization.FIN, penetrator = PenetratorSpec(steelId, 0.03, 0.6, 3.0, 17000.0, null), payload = null,
        external = ExternalBallisticsSpec(0.3, 1500.0), terminalModel = Fx.MODEL,
    )

    private val catalog = object : BallisticsCatalog {
        override fun projectile(id: Ident) = rod
        override fun material(id: Ident) = if (id == rubberId) rubber else steel
        override fun preset(id: Ident) = throw UnsupportedOperationException()
        override fun effect(id: Ident) = spec
        override fun construction(id: Ident) = throw UnsupportedOperationException()
    }

    private fun layer(material: Ident, thicknessM: Double) = ArmorLayer(material, thicknessM, LayerGeometry.FLAT, LayerRole.ARMOR)
    private fun sandwich(back: Double = 0.005) =
        ArmorElement.EffectPackage(listOf(layer(steelId, 0.005), layer(rubberId, 0.012), layer(steelId, back)), effectId)
    private val slot = EffectSlot(Ident("test", "c"), 0)

    private fun shot(dir: V3, speed: Double = 1500.0, from: V3 = V3.ZERO) = ProjectileState.launch(rod, from, dir, speed)

    /** [deg] off the plate normal (the plate faces -x, the shot flies +x); [azimuthDeg] turns the tilt around the line of the normal. */
    private fun oblique(deg: Double, azimuthDeg: Double = 0.0, from: V3 = V3.ZERO): ProjectileState {
        val t = Math.toRadians(deg)
        val a = Math.toRadians(azimuthDeg)
        return shot(V3(cos(t), sin(t) * cos(a), sin(t) * sin(a)), from = from)
    }

    private fun run(p: ProjectileState, runtime: ArmorRuntimeState = ArmorRuntimeState(), back: Double = 0.005): TraversalResult =
        Fx.traverser(Fx.ThicknessModel(), NeraBulgeModel(), catalog)
            .traverse(ArmorConstruction(Ident("test", "c"), listOf(sandwich(back))).stack(Fx.facingMinusX), p, runtime, seed = 7L)

    @Test
    fun `a perpendicular hit is not disrupted, NERA needs obliquity`() {
        val r = run(shot(V3(1.0, 0.0, 0.0)))
        val out = r.residual!!
        assertEquals(TraversalOutcome.EXITED_CONSTRUCTION, r.outcome)
        assertEquals(3.0, out.massRemainingKg, 1e-12)
        assertTrue(out.yawRad < 1e-9)
        assertEquals(1.0, out.integrity, 1e-12)
    }

    @Test
    fun `an oblique hit costs the penetrator mass, integrity and straightness`() {
        val r = run(oblique(60.0))
        val out = r.residual!!
        assertTrue(out.massRemainingKg in 1.5..2.95, "mass ${out.massRemainingKg}")
        assertTrue(out.integrity < 1.0 && out.deformation > 0.0)
        assertTrue(out.yawRad > Math.toRadians(1.0), "yaw ${Math.toDegrees(out.yawRad)}")
        assertTrue(out.penetratorLengthRemainingM!! < 0.6)
        assertTrue(r.state.spall.isNotEmpty(), "broken-off penetrator must show up as spall")
    }

    @Test
    fun `more obliquity is worse for the penetrator`() {
        val m30 = run(oblique(30.0)).residual!!.massRemainingKg
        val m60 = run(oblique(60.0)).residual!!.massRemainingKg
        assertTrue(m60 < m30, "60 deg left $m60 kg, 30 deg left $m30 kg")
    }

    @Test
    fun `energy never grows through the sandwich`() {
        val start = oblique(55.0)
        val r = run(start)
        val after = r.residual!!.kineticEnergyJ + r.state.spall.sumOf { it.energyJ } + r.state.depositedEnergyJ
        assertTrue(after <= start.kineticEnergyJ * (1.0 + 1e-6), "$after > ${start.kineticEnergyJ}")
    }

    @Test
    fun `the sideways push lies in the plane of the plate normal and the line of flight, in three dimensions`() {
        val start = oblique(55.0, azimuthDeg = 40.0) // the flight has y and z components
        // Equal plates push the rod's ends equally and opposite (a pure couple, no net turn), so make the back plate thicker.
        val out = run(start, back = 0.008).residual!!
        val d = start.velocity.normalize()
        val planeNormal = V3(-1.0, 0.0, 0.0).cross(d).normalize()
        val newDir = out.velocity.normalize()
        assertTrue(newDir.distanceTo(d) > 1e-4, "the line of flight must have turned")
        assertEquals(0.0, newDir.dot(planeNormal), 1e-9) // the turn stays in the plane (n, d)
        assertEquals(0.0, out.axis.dot(planeNormal), 1e-9) // and so does the yaw of the body
        assertTrue(out.yawRad > 0.0)
        assertTrue(abs(d.z) > 0.1) // and the case really is not planar
    }

    @Test
    fun `a hit leaves a tear in world space along the line of fire`() {
        val r = run(oblique(60.0, azimuthDeg = 90.0)) // flies in +x and +z
        val (tearSlot, tear) = r.state.tears.single()
        assertEquals(slot, tearSlot)
        assertEquals(V3(-1.0, 0.0, 0.0), tear.normal)
        assertTrue(tear.semiMajorM > tear.semiMinorM, "an oblique hit tears a slot")
        assertEquals(1.0, abs(tear.majorAxis.z), 1e-6) // long side along the trace of the flight line in the plate
        assertTrue(tear.center.distanceTo(V3.ZERO) < 0.005, "tear is where the shot went in, was ${tear.center}")
    }

    @Test
    fun `a second hit in the same hole meets no working sandwich, a hit a metre away does`() {
        val vehicle = ArmorRuntimeState()
        val first = run(oblique(60.0), vehicle)
        vehicle.commit(first)
        assertEquals(1, vehicle.tearsOf(slot).size)

        val again = run(oblique(60.0), vehicle).residual!!
        assertEquals(3.0, again.massRemainingKg, 1e-12)
        assertTrue(again.yawRad < 1e-9)

        val elsewhere = run(oblique(60.0, from = V3(0.0, 0.0, 1.0)), vehicle).residual!!
        assertEquals(first.residual!!.massRemainingKg, elsewhere.massRemainingKg, 1e-9)
    }

    @Test
    fun `a stopped projectile still tears the sandwich`() {
        val r = run(oblique(60.0), back = 3.0) // a back plate nothing gets through
        assertEquals(TraversalOutcome.STOPPED_IN_ARMOR, r.outcome)
        assertEquals(1, r.state.tears.size)
    }

    @Test
    fun `tears are bounded per tile`() {
        val vehicle = ArmorRuntimeState()
        repeat(ArmorRuntimeState.MAX_TEARS_PER_SLOT + 10) { i ->
            vehicle.commit(run(oblique(60.0, from = V3(0.0, 0.0, 1.0 * (i + 1)))))
        }
        assertEquals(ArmorRuntimeState.MAX_TEARS_PER_SLOT, vehicle.tearsOf(slot).size)
    }

    @Test
    fun `tear geometry is validated`() {
        val n = V3(-1.0, 0.0, 0.0)
        assertFailsWith<IllegalArgumentException> { ArmorTear(V3.ZERO, n, V3(1.0, 0.0, 0.0), 0.02, 0.01) } // axis not in the plate plane
        assertFailsWith<IllegalArgumentException> { ArmorTear(V3.ZERO, n, V3(0.0, 1.0, 0.0), 0.01, 0.02) } // major < minor
        val t = ArmorTear(V3.ZERO, n, V3(0.0, 1.0, 0.0), 0.04, 0.02)
        assertEquals(1.0, t.normalizedDistance(V3(5.0, 0.04, 0.0)), 1e-12) // depth does not matter
        assertEquals(1.0, t.normalizedDistance(V3(0.0, 0.0, 0.02)), 1e-12)
        assertEquals(0.0, t.normalizedDistance(V3.ZERO), 1e-12)
    }

    @Test
    fun `parameters are validated and unknown keys rejected`() {
        assertFailsWith<IllegalArgumentException> { NeraParameters.of(spec.copy(parameters = mapOf("typo_key" to 1.0))) }
        assertFailsWith<IllegalArgumentException> { NeraParameters(debrisSpeedRatio = 1.5) }
        assertEquals(NeraParameters(), NeraParameters.of(spec))
    }

    @Test
    fun `validation wants plate elastomer plate`() {
        fun problems(vararg layers: ArmorLayer): List<String> = BallisticsValidation.validate(
            BallisticsCatalogView(
                listOf(steel, rubber), emptyMap(),
                listOf(ArmorConstruction(Ident("test", "c"), listOf(ArmorElement.EffectPackage(layers.toList(), effectId)))),
                listOf(spec),
            ),
        )
        assertTrue(problems(layer(steelId, 0.005), layer(rubberId, 0.01), layer(steelId, 0.005)).isEmpty())
        assertTrue(problems(layer(steelId, 0.005), layer(steelId, 0.01), layer(steelId, 0.005)).isNotEmpty())
        assertTrue(problems(layer(steelId, 0.005), layer(rubberId, 0.01)).isNotEmpty())
    }
}
