// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.ballistics.vehicle.damage

import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import io.github.jwyoon1220.dncity.ballistics.vehicle.ArmorSolver
import io.github.jwyoon1220.dncity.ballistics.vehicle.HitOutcome
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorSpec
import io.github.jwyoon1220.dncity.ballistics.vehicle.PlateArmor
import io.github.jwyoon1220.dncity.ballistics.vehicle.ReactiveArmor
import io.github.jwyoon1220.dncity.ballistics.vehicle.ReactiveType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DamageModelTest {
    private val x = V3(1.0, 0.0, 0.0)
    private val y = V3(0.0, 1.0, 0.0)
    private val z = V3(0.0, 0.0, 1.0)
    /** A tank-sized hull (3.6 m wide, 1.2 m tall, 7 m long, front at +Z) with a turret on top. */
    private val hull = OrientedBox(V3.ZERO, x, y, z, V3(1.8, 0.6, 3.5))
    private val turret = OrientedBox(V3(0.0, 0.9, 0.3), x, y, z, V3(1.1, 0.3, 1.4))
    private val anchors = mapOf(Anchor.HULL to hull, Anchor.TURRET to turret)
    private val modules = placeModules(ModuleLayouts.TANK, anchors)
    private val interior = Interior(listOf(hull, turret))
    private val back = V3(0.0, 0.0, -1.0)

    private fun frontHit(kind: PenetratorKind = PenetratorKind.KE, residual: Double = 300.0, caliber: Double = 120.0, at: V3 = V3(0.0, -0.1, 3.5), liner: Boolean = false, filler: Double = 0.0, fuseDelay: Double = 0.0, armed: Boolean = true) =
        Penetration(at, back, kind, residual, caliber, filler, fuseDelay, armed, liner)

    private fun run(p: Penetration, seed: Long = 1L, state: VehicleDamageState = VehicleDamageState()) = DamageModel.simulate(p, modules, interior, state, seed)

    @Test
    fun `template modules sit inside their boxes and every crew member has a seat`() {
        assertEquals(modules.size, modules.map { it.spec.id }.toSet().size)
        for (m in modules) {
            val anchor = if (m.spec.placement.anchor == Anchor.TURRET) turret else hull
            assertTrue(anchor.contains(m.box.center, 1e-9), "${m.spec.id} outside its box")
        }
        assertTrue(ModuleLayouts.TEMPLATES.values.flatten().filter { it.type == ModuleType.CREW }.all { it.seat != null })
        // no engine box on this vehicle: the engine falls back to the rear of the hull
        assertTrue(modules.first { it.spec.id == "engine" }.box.center.z < 0)
    }

    @Test
    fun `a kinetic round through the front kills the driver and throws a cone of spall`() {
        val r = run(frontHit())
        assertTrue(r.hits.any { it.id == "driver" && it.destroyedNow })
        assertTrue(r.events.contains(DamageEvent(EventType.CREW_KNOCKED_OUT, "driver")))
        val spall = r.rays.filter { it.kind == RayKind.SPALL }
        assertTrue(spall.size >= 20)
        // a cone, not a line: fragments spread sideways and up/down
        assertTrue(spall.any { it.to.x - it.from.x > 0.2 } && spall.any { it.to.x - it.from.x < -0.2 })
        assertTrue(spall.any { it.to.y - it.from.y > 0.1 } && spall.any { it.to.y - it.from.y < -0.1 })
        // nothing flies through the far wall
        assertTrue(r.rays.all { hull.contains(it.to, 1e-6) || turret.contains(it.to, 1e-6) })
    }

    @Test
    fun `a spall liner narrows the cone and takes most of the fragments`() {
        val bare = run(frontHit()).rays.filter { it.kind == RayKind.SPALL }
        val lined = run(frontHit(liner = true)).rays.filter { it.kind == RayKind.SPALL }
        assertTrue(lined.size < bare.size / 2 + 1)
        fun maxSpread(rs: List<DamageRay>) = rs.maxOf { val d = (it.to - it.from).normalize(); 1 - d.dot(back) }
        assertTrue(maxSpread(lined) < maxSpread(bare))
    }

    @Test
    fun `rifle bullets make no spall`() {
        val r = run(frontHit(residual = 5.0, caliber = 7.62))
        assertTrue(r.rays.none { it.kind == RayKind.SPALL })
        assertEquals(1, r.rays.size)
    }

    @Test
    fun `a shaped-charge jet dies in the air within a few metres`() {
        val r = run(frontHit(kind = PenetratorKind.HEAT, residual = 150.0))
        val jet = r.rays.single { it.kind == RayKind.JET }
        assertTrue(jet.from.distanceTo(jet.to) <= 1.0 + 1e-9)
        // and its spall cone is narrow
        val spall = r.rays.filter { it.kind == RayKind.SPALL }
        assertTrue(spall.isNotEmpty() && spall.all { (it.to - it.from).normalize().dot(back) > kotlin.math.cos(Math.toRadians(12.0)) - 1e-9 })
    }

    @Test
    fun `aphe goes off behind the plate when the plate armed the fuze, and not otherwise`() {
        val side = Penetration(V3(1.1, 0.9, 0.3), V3(-1.0, 0.0, 0.0), PenetratorKind.KE, 60.0, 76.0, fillerKg = 0.06, fuseDelayM = 0.5)
        val armed = run(side)
        assertNotNull(armed.blast)
        assertTrue(armed.rays.count { it.kind == RayKind.BLAST_FRAGMENT } >= 8)
        // the blast fills the turret: both turret crew on the far side of it take damage
        assertTrue(armed.hits.count { it.type == ModuleType.CREW } >= 2)
        val dud = run(side.copy(fuseArmed = false))
        assertNull(dud.blast)
        assertTrue(dud.hits.sumOf { it.damage } < armed.hits.sumOf { it.damage })
    }

    @Test
    fun `a destroyed ammo rack either detonates the vehicle or cooks off into a fire`() {
        val rack = modules.first { it.spec.id == "turret_ammo" }.box.center
        val hit = Penetration(rack + V3(0.0, 0.0, -0.4), z, PenetratorKind.KE, 400.0, 120.0)
        val outcomes = (1L..60L).map { run(hit, seed = it) }
        val detonations = outcomes.filter { r -> r.events.any { it.type == EventType.AMMO_DETONATION } }
        val fires = outcomes.filter { r -> "turret_ammo" in r.newFires }
        assertTrue(detonations.isNotEmpty() && fires.isNotEmpty())
        assertTrue(detonations.all { it.destroyed == DestroyCause.AMMO_DETONATION })
        assertTrue(outcomes.all { r -> r.hits.any { it.id == "turret_ammo" && it.destroyedNow } })
    }

    @Test
    fun `the vehicle is out when fewer than two crew are left`() {
        val specs = ModuleLayouts.TANK
        val crew = specs.filter { it.type == ModuleType.CREW }.map { it.id }
        assertFalse(DamageModel.crewOut(specs) { id -> if (id in crew.take(2)) 1000.0 else 0.0 })
        assertTrue(DamageModel.crewOut(specs) { id -> if (id in crew.take(3)) 1000.0 else 0.0 })
        assertFalse(DamageModel.crewOut(ModuleLayouts.TANK.filter { it.type != ModuleType.CREW }) { 1e9 })
    }

    @Test
    fun `same hit and seed, same result`() {
        assertEquals(run(frontHit(), seed = 7), run(frontHit(), seed = 7))
    }

    @Test
    fun `fires hurt the crew, burn out, and a full crew puts them out sooner`() {
        fun burnTime(crewDead: Boolean): Int {
            val state = VehicleDamageState()
            state.apply(DamageReport.NONE.copy(newFires = mapOf("engine" to 15.0)))
            if (crewDead) state.apply(DamageReport.NONE.copy(hits = ModuleLayouts.TANK.filter { it.type == ModuleType.CREW }.map { ModuleHit(it.id, it.type, 1000.0, true) }))
            var seconds = 0
            val noCookOff = ModuleLayouts.TANK.filter { it.type != ModuleType.AMMO }
            while (state.isBurning && seconds < 100) { state.tickFires(noCookOff, 1.0, Random(seconds)); seconds++ }
            return seconds
        }
        assertTrue(burnTime(crewDead = false) < burnTime(crewDead = true))
        val state = VehicleDamageState()
        state.apply(DamageReport.NONE.copy(newFires = mapOf("engine" to 15.0)))
        val tick = state.tickFires(ModuleLayouts.TANK, 1.0, Random(0))
        assertTrue(tick.crewDamage.isNotEmpty() && tick.hullShare > 0)
    }

    @Test
    fun `a long fire next to the ammunition can cook it off`() {
        val cookOffs = (0 until 40).count { seed ->
            val state = VehicleDamageState()
            state.apply(DamageReport.NONE.copy(newFires = mapOf("engine" to 1000.0)))
            val rng = Random(seed)
            repeat(60) { state.tickFires(ModuleLayouts.TANK, 1.0, rng) }
            state.destroyed == DestroyCause.AMMO_DETONATION
        }
        assertTrue(cookOffs in 1 until 40)
    }

    @Test
    fun `the state survives a save and load`() {
        val state = VehicleDamageState()
        state.apply(run(frontHit()))
        state.recordReactive(ReactiveHit(2, 4, V3(0.1, 0.2, 0.3), 0.35))
        val copy = VehicleDamageState().apply { restore(state.snapshot()) }
        assertEquals(state.snapshot(), copy.snapshot())
        assertFalse(copy.reactiveIntact(2, 4, V3(0.2, 0.2, 0.3)))
        assertTrue(copy.reactiveIntact(2, 4, V3(1.0, 0.2, 0.3)))
        copy.repairAll()
        assertTrue(copy.isPristine)
    }

    @Test
    fun `explicit modules replace template ones by id and add new ones`() {
        val json = JsonParser.parseString(
            """[{"id": "driver", "type": "crew", "at": [0.5, 0, 0.7], "size": [0.2, 0.3, 0.1], "seat": 0},
               {"id": "radio", "type": "turret_drive", "anchor": "turret", "at": [0, 0, -0.5], "size": [0.1, 0.1, 0.1], "hp": 20}]""",
        ).asJsonArray
        val specs = ModuleLayouts.resolve("tank", json)
        assertEquals(ModuleLayouts.TANK.size + 1, specs.size)
        assertEquals(0.5, specs.first { it.id == "driver" }.placement.at.x)
        assertEquals(20.0, specs.first { it.id == "radio" }.hp)
    }
}

/** The damage model on SuperbWarfare's own M1A2 boxes: the template has to land in the right places on a real hull. */
class M1a2LayoutTest {
    private val obbs = JsonParser.parseString(
        java.io.File("mods/SuperbWarfare/src/main/resources/data/superbwarfare/sbw/vehicles/m_1a_2.json").readText(),
    ).asJsonObject.getAsJsonArray("OBB").map { it.asJsonObject }
    private val x = V3(1.0, 0.0, 0.0)
    private val y = V3(0.0, 1.0, 0.0)
    private val z = V3(0.0, 0.0, 1.0)

    private fun box(o: com.google.gson.JsonObject, lift: Double = 0.0): OrientedBox {
        val s = o.getAsJsonArray("Size"); val p = o.getAsJsonArray("Position")
        return OrientedBox(V3(p[0].asDouble, p[1].asDouble + lift, p[2].asDouble), x, y, z, V3(s[0].asDouble, s[1].asDouble, s[2].asDouble))
    }

    private fun part(o: com.google.gson.JsonObject) = o["Part"]?.asString
    private val hullBoxes = obbs.filter { part(it) == null }.map { box(it) }
    private val hull = OrientedBox.enclosing(hullBoxes)
    // the turret's position is relative to its own pivot; sit it on the hull roof for the test
    private val turret = obbs.first { part(it) == "Turret" }.let { box(it, lift = hull.center.y + hull.half.y) }
    private val engine = box(obbs.first { part(it) == "MainEngine" })
    private val modules = placeModules(ModuleLayouts.TANK, mapOf(Anchor.HULL to hull, Anchor.TURRET to turret, Anchor.ENGINE to engine))
    private val interior = Interior(hullBoxes + turret + engine)
    private fun at(id: String) = modules.first { it.spec.id == id }.box.center

    @Test
    fun `the three hull boxes become one hull and the driver sits at the front`() {
        assertEquals(3, hullBoxes.size)
        assertTrue(hullBoxes.all { b -> b.corners().all { hull.contains(it, 1e-9) } })
        assertTrue(at("driver").z > 2.0)
        assertTrue(at("transmission").z < -4.0)
        assertEquals(engine.center, at("engine"))
    }

    @Test
    fun `a shot through the front hull plate reaches the driver, one into the bustle the turret ammo`() {
        val front = Penetration(V3(0.0, at("driver").y, hull.center.z + hull.half.z), V3(0.0, 0.0, -1.0), PenetratorKind.KE, 200.0, 120.0)
        val r1 = DamageModel.simulate(front, modules, interior, VehicleDamageState(), 3L)
        assertTrue(r1.hits.any { it.id == "driver" })

        val rack = at("turret_ammo")
        val side = Penetration(V3(turret.center.x + turret.half.x, rack.y, rack.z), V3(-1.0, 0.0, 0.0), PenetratorKind.KE, 200.0, 120.0)
        val r2 = DamageModel.simulate(side, modules, interior, VehicleDamageState(), 3L)
        assertTrue(r2.hits.any { it.id == "turret_ammo" })
    }
}

class ArmorSolverWarThunderTest {
    private val apfsds = PenetratorSpec(PenetratorKind.KE, 500.0)

    @Test
    fun `ricochet becomes likelier over the last ten degrees, then certain`() {
        assertEquals(0.0, ArmorSolver.ricochetChance(apfsds, 64.0, false))
        assertEquals(0.5, ArmorSolver.ricochetChance(apfsds, 70.0, false), 1e-9)
        assertEquals(1.0, ArmorSolver.ricochetChance(apfsds, 75.0, false))
        val plate = PlateArmor(50.0, 50.0)
        assertEquals(HitOutcome.RICOCHET, ArmorSolver.solve(apfsds, plate, 70.0, 0.0, roll = 0.2).outcome)
        assertEquals(HitOutcome.PENETRATED, ArmorSolver.solve(apfsds, plate, 70.0, 0.0, roll = 0.8).outcome)
    }

    @Test
    fun `a big shell overmatches a thin plate, cannot glance off and the slope counts less`() {
        val plate = PlateArmor(30.0, 30.0)
        val ap = PenetratorSpec(PenetratorKind.KE, 150.0)
        val thin = ArmorSolver.solve(ap, plate, 78.0, 0.0, caliberMm = 120.0)
        assertTrue(thin.overmatch)
        assertEquals(HitOutcome.PENETRATED, thin.outcome)
        val small = ArmorSolver.solve(ap, plate, 60.0, 0.0, caliberMm = 20.0)
        val big = ArmorSolver.solve(ap, plate, 60.0, 0.0, caliberMm = 120.0)
        assertTrue(big.effectiveArmorMm < small.effectiveArmorMm)
    }

    @Test
    fun `normalization takes degrees off before the slope counts`() {
        val plate = PlateArmor(100.0, 100.0)
        val plain = ArmorSolver.solve(PenetratorSpec(PenetratorKind.KE, 300.0), plate, 45.0, 0.0)
        val capped = ArmorSolver.solve(PenetratorSpec(PenetratorKind.KE, 300.0, normalizationDeg = 5.0), plate, 45.0, 0.0)
        assertTrue(capped.effectiveArmorMm < plain.effectiveArmorMm)
    }

    @Test
    fun `era stops a shaped charge once, then the spot is bare`() {
        val plate = PlateArmor(200.0, 200.0, ReactiveArmor(ReactiveType.ERA, ke = 0.0, ce = 400.0, tileM = 0.35))
        val heat = PenetratorSpec(PenetratorKind.HEAT, 500.0)
        val first = ArmorSolver.solve(heat, plate, 0.0, 0.0, caliberMm = 105.0)
        assertEquals(HitOutcome.NOT_PENETRATED, first.outcome)
        assertEquals(ReactiveType.ERA, first.reactiveFired)
        val second = ArmorSolver.solve(heat, plate, 0.0, 0.0, caliberMm = 105.0, reactiveIntact = false)
        assertEquals(HitOutcome.PENETRATED, second.outcome)
        // a rifle bullet does not set it off
        assertNull(ArmorSolver.solve(PenetratorSpec(PenetratorKind.KE, 10.0), plate, 0.0, 0.0, caliberMm = 7.62).reactiveFired)
    }

    @Test
    fun `kontakt-1 style era takes a share of the jet, nothing off a rod`() {
        val plate = PlateArmor(80.0, 80.0, ReactiveArmor(ReactiveType.ERA, ke = 0.0, ce = 0.0, tileM = 0.13, ceReduction = 0.52))
        // 160 mm HEAT halved by the ERA is 76.8 mm, under the 80 mm plate; without the ERA it goes through
        assertEquals(HitOutcome.NOT_PENETRATED, ArmorSolver.solve(PenetratorSpec(PenetratorKind.HEAT, 160.0), plate, 0.0, 0.0, caliberMm = 85.0).outcome)
        assertEquals(HitOutcome.PENETRATED, ArmorSolver.solve(PenetratorSpec(PenetratorKind.HEAT, 160.0), plate, 0.0, 0.0, caliberMm = 85.0, reactiveIntact = false).outcome)
        assertEquals(0.0, ArmorSolver.solve(PenetratorSpec(PenetratorKind.KE, 160.0), plate, 0.0, 0.0, caliberMm = 120.0).reactiveMm)
    }

    @Test
    fun `a tandem charge strips era but not nera`() {
        val era = PlateArmor(200.0, 200.0, ReactiveArmor(ReactiveType.ERA, ke = 0.0, ce = 400.0, tileM = 0.35))
        val tandem = PenetratorSpec(PenetratorKind.HEAT, 500.0, tandem = true)
        val hit = ArmorSolver.solve(tandem, era, 0.0, 0.0, caliberMm = 125.0)
        assertEquals(HitOutcome.PENETRATED, hit.outcome)
        assertEquals(ReactiveType.ERA, hit.reactiveFired)
        val nera = PlateArmor(200.0, 200.0, ReactiveArmor(ReactiveType.NERA, ke = 0.0, ce = 400.0, tileM = 0.5))
        assertTrue(ArmorSolver.solve(tandem, nera, 45.0, 0.0, caliberMm = 125.0).reactiveMm > 0)
    }

    @Test
    fun `nera does more against an oblique hit than a square one`() {
        val plate = PlateArmor(300.0, 300.0, ReactiveArmor(ReactiveType.NERA, ke = 150.0, ce = 300.0, tileM = 0.5))
        val rod = PenetratorSpec(PenetratorKind.KE, 1000.0)
        val square = ArmorSolver.solve(rod, plate, 0.0, 0.0, caliberMm = 120.0)
        val oblique = ArmorSolver.solve(rod, plate, 45.0, 0.0, caliberMm = 120.0)
        assertTrue(oblique.reactiveMm > square.reactiveMm && square.reactiveMm > 0)
    }
}
