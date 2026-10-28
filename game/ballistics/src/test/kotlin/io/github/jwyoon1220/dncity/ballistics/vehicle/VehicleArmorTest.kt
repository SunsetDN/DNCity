// AGENT-DONE(claude): vehicle-armor-penetration
package io.github.jwyoon1220.dncity.ballistics.vehicle

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DATA = "src/main/resources/data/dncity/dncity"
private const val SBW_VEHICLES = "../../mods/SuperbWarfare/src/main/resources/data/superbwarfare/sbw/vehicles"

private fun json(file: File): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

class VehicleArmorTest {
    private val ke = PenetratorSpec(PenetratorKind.KE, 600.0, rangeM = 2000.0, lossPerKmMm = 40.0)

    @Test
    fun kineticPenetrationFallsWithDistanceHeatDoesNot() {
        assertEquals(600.0, ke.penetrationAt(2000.0), 1e-9)
        assertEquals(680.0, ke.penetrationAt(0.0), 1e-9)
        assertEquals(560.0, ke.penetrationAt(3000.0), 1e-9)
        val heat = PenetratorSpec(PenetratorKind.HEAT, 500.0)
        assertEquals(500.0, heat.penetrationAt(4000.0), 1e-9)
    }

    @Test
    fun squareHitComparesPenetrationWithArmor() {
        val plate = PlateArmor(ke = 500.0, ce = 900.0)
        assertEquals(HitOutcome.PENETRATED, ArmorSolver.solve(ke, plate, 0.0, 2000.0).outcome)
        val thick = ArmorSolver.solve(ke, PlateArmor(700.0, 700.0), 0.0, 2000.0)
        assertEquals(HitOutcome.NOT_PENETRATED, thick.outcome)
        assertEquals(0.0, thick.residualMm)
        assertEquals(0.0, thick.depthBlocks)
        // a shaped charge meets the CE value
        assertEquals(HitOutcome.NOT_PENETRATED, ArmorSolver.solve(PenetratorSpec(PenetratorKind.HEAT, 800.0), plate, 0.0, 0.0).outcome)
    }

    @Test
    fun obliquityThickensThePlateAndGrazingRicochets() {
        val plate = PlateArmor(400.0, 400.0)
        val at60 = ArmorSolver.solve(ke, plate, 60.0, 2000.0)
        assertEquals(800.0, at60.effectiveArmorMm, 1e-6)
        assertEquals(HitOutcome.NOT_PENETRATED, at60.outcome)
        assertEquals(HitOutcome.RICOCHET, ArmorSolver.solve(ke, PlateArmor(1.0, 1.0), 80.0, 0.0).outcome)
        assertEquals(HitOutcome.NOT_PENETRATED, ArmorSolver.solve(PenetratorSpec(PenetratorKind.HE, 30.0), plate, 89.0, 0.0).outcome)
    }

    @Test
    fun penetrationCarriesDeeperWithMoreResidual() {
        val thin = ArmorSolver.solve(ke, PlateArmor(30.0, 30.0), 0.0, 2000.0)
        val thick = ArmorSolver.solve(ke, PlateArmor(500.0, 500.0), 0.0, 2000.0)
        assertTrue(thin.depthBlocks > thick.depthBlocks)
        assertTrue(thick.depthBlocks >= 1.0)
    }

    private val identity = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))

    @Test
    fun rayEntersTheFaceItComesFrom() {
        val extents = doubleArrayOf(1.0, 1.0, 2.0)
        // coming from the front (+Z) going -Z
        val front = RayBox.enter(doubleArrayOf(0.0, 0.0, 10.0), doubleArrayOf(0.0, 0.0, -1.0), DoubleArray(3), identity, extents)
        assertNotNull(front)
        assertEquals(8.0, front.first, 1e-9)
        assertEquals(Face.FRONT, front.second)
        // from the vehicle's left (+X)
        assertEquals(Face.LEFT, RayBox.enter(doubleArrayOf(5.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), DoubleArray(3), identity, extents)!!.second)
        // from above
        assertEquals(Face.TOP, RayBox.enter(doubleArrayOf(0.0, 5.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0), DoubleArray(3), identity, extents)!!.second)
        // miss
        assertNull(RayBox.enter(doubleArrayOf(5.0, 0.0, 10.0), doubleArrayOf(0.0, 0.0, -1.0), DoubleArray(3), identity, extents))
    }

    @Test
    fun rotatedBoxUsesItsOwnFacesAndAngles() {
        // box yawed 90 degrees: its front (+Z local) points along world +X
        val yaw = Math.toRadians(90.0)
        val axes = arrayOf(
            doubleArrayOf(cos(yaw), 0.0, -sin(yaw)),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(sin(yaw), 0.0, cos(yaw)),
        )
        val hit = RayBox.enter(doubleArrayOf(10.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), DoubleArray(3), axes, doubleArrayOf(1.0, 1.0, 2.0))
        assertEquals(Face.FRONT, hit!!.second)
        // 45 degrees off the front plate
        val d = doubleArrayOf(-1 / sqrt(2.0), 0.0, -1 / sqrt(2.0))
        assertEquals(45.0, RayBox.impactAngleDeg(d, RayBox.normal(Face.FRONT, axes)), 1e-6)
    }

    @Test
    fun shippedArmorMatchesSuperbWarfareVehicles() {
        val files = File("$DATA/vehicle_armor").listFiles { f -> f.extension == "json" }!!
        assertTrue(files.size >= 10)
        for (file in files) {
            val obj = json(file)
            val armor = VehicleArmor.fromJson(obj)
            for (vehicle in armor.vehicles) {
                val sbw = File("$SBW_VEHICLES/${vehicle.substringAfter(':')}.json")
                assertTrue(sbw.isFile, "${file.name}: no SuperbWarfare vehicle $vehicle")
                val obbCount = json(sbw).getAsJsonArray("OBB")?.size() ?: 0
                armor.obbZones.keys.forEach { assertTrue(it in 0 until obbCount, "${file.name}: OBB $it out of range for $vehicle") }
            }
            assertTrue(obj.getAsJsonArray("sources").all { s ->
                val o = s.asJsonObject
                o["kind"].asString in setOf("ESTIMATE", "DESIGN") && o.has("source")
            }, "${file.name}: every source needs kind ESTIMATE/DESIGN and a source")
        }
    }

    @Test
    fun everyShippedPenetratorParsesAndSaysWhereItsNumberIsFrom() {
        val shells = File("$DATA/shells").walkTopDown().filter { it.extension == "json" }
            .mapNotNull { json(it).getAsJsonObject("armor_penetration")?.let { p -> it to p } }.toList()
        assertTrue(shells.size >= 20)
        for ((file, p) in shells) {
            PenetratorSpec.fromJson(p)
            assertTrue(p["status"].asString in setOf("ESTIMATE", "WAR_THUNDER", "DESIGN"), file.path)
            if (p["status"].asString != "DESIGN") assertTrue(p.has("source_url"), "${file.path}: an ESTIMATE / WAR_THUNDER value needs its source_url")
        }
    }
}
