// AGENT-DONE(claude): small-arms-penetration
package io.github.jwyoon1220.dncity.ballistics.smallarms

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val INCH = 0.0254
private const val DATA = "src/main/resources/data/dncity/dncity"

private fun json(path: String): JsonObject = JsonParser.parseString(File(path).readText()).asJsonObject

private fun shell(name: String): BulletSpec = BulletSpec.fromJson(json("$DATA/shells/tacz/$name.json").getAsJsonObject("small_arms"))

/** Tests against the shipped data: the numbers are the sourced ones, so these tests also pin what the sources say. */
class SmallArmsBlockTest {
    private val table = BlockMaterialTable.fromJson(json("$DATA/small_arms/block_materials.json"))
    private val m80 = table.reference
    private val v80 = m80.referenceVelocityMps

    @Test
    fun `the reference round reaches exactly the FM 3-06 11 depths it was calibrated on`() {
        val ed = m80.energyDensity(v80)
        assertEquals(13 * INCH, table.material(BlockCategory.WOOD).depthM(ed), 1e-12)
        assertEquals(5 * INCH, table.material(BlockCategory.SOIL).depthM(ed), 1e-12)
        assertEquals(2 * INCH, table.material(BlockCategory.STONE).depthM(ed), 1e-12)
        assertTrue(table.material(BlockCategory.METAL).depthIsLowerBound)
        assertTrue(table.solve(BlockCategory.WOOD, m80, v80, 12.9 * INCH).perforated)
        assertFalse(table.solve(BlockCategory.WOOD, m80, v80, 13.1 * INCH).perforated)
    }

    @Test
    fun `5_56 M855 is stopped by a 2 inch concrete wall, which FM 3-06 11 states but the calibration did not use`() {
        // FM 3-06.11 para 7-2c: "A 2-inch concrete wall (nonreinforced)" stops a 5.56-mm round fired at less than 50 meters.
        val m855 = shell("556x45")
        assertFalse(table.solve(BlockCategory.STONE, m855, m855.referenceVelocityMps, 2 * INCH).perforated)
    }

    @Test
    fun `a full block of wood soil or stone stops every rifle round, a door does not stop a 7_62`() {
        listOf("9mm", "556x45", "762x39", "762x54", "308", "338", "50bmg").map(::shell).forEach { b ->
            listOf(BlockCategory.WOOD, BlockCategory.SOIL, BlockCategory.STONE, BlockCategory.METAL).forEach { c ->
                assertFalse(table.solve(c, b, b.referenceVelocityMps, 1.0).perforated, "$c 1 m vs ${b.source}")
            }
        }
        // a vanilla door is 3/16 of a block thick
        assertTrue(table.solve(BlockCategory.WOOD, m80, v80, 3.0 / 16.0).perforated)
    }

    @Test
    fun `energy is only lost - glass and foliage lose nothing, unknown stops, resist loses in proportion to the path`() {
        val g = table.solve(BlockCategory.GLASS, m80, v80, 1.0)
        assertTrue(g.perforated)
        assertEquals(v80, g.exitSpeedMps, 1e-9)
        assertTrue(table.solve(BlockCategory.FOLIAGE, m80, v80, 1.0).perforated)
        assertFalse(table.solve(BlockCategory.UNKNOWN, m80, v80, 0.0).perforated)
        val half = table.solve(BlockCategory.WOOD, m80, v80, 6.5 * INCH)
        assertEquals(0.5, half.energyFraction, 1e-9)
        assertTrue(half.exitSpeedMps < v80)
    }

    @Test
    fun `the block table is validated`() {
        val base = json("$DATA/small_arms/block_materials.json")
        fun bad(mutate: (JsonObject) -> Unit) = assertFailsWith<IllegalArgumentException> {
            BlockMaterialTable.fromJson(base.deepCopy().also(mutate))
        }
        bad { it.getAsJsonObject("materials").remove("unknown") } // every category needs an entry
        bad { it.getAsJsonObject("materials").getAsJsonObject("wood").remove("source") }
        bad { it.getAsJsonObject("materials").getAsJsonObject("wood").remove("reference_depth_in") }
        bad { it.getAsJsonObject("materials").add("lava", JsonParser.parseString("""{ "behavior": "STOP", "source": "x" }""")) }
        bad { it.getAsJsonObject("reference_round").remove("source") }
    }
}

class SmallArmsArmorTest {
    private val table = BodyArmorTable.fromJson(json("$DATA/small_arms/body_armor.json"))

    private fun threatBullet(t: TestThreat) = BulletSpec(t.massKg, t.diameterM, t.velocityMps, "NIJ", "NIJ 0101.06", SourceKind.STANDARD)

    @Test
    fun `every level stops its own NIJ test threats and is perforated by the next rifle threat`() {
        table.levels.values.forEach { level ->
            level.threats.forEach { t ->
                assertFalse(table.solve(level, 1.0, threatBullet(t), t.velocityMps).perforated, "${level.id} vs ${t.name}")
            }
        }
        val m80 = table.levels.getValue("III").threats.single()
        assertTrue(table.solve(table.levels.getValue("IIIA"), 1.0, threatBullet(m80), m80.velocityMps).perforated)
        assertFalse(table.solve(table.levels.getValue("IV"), 1.0, threatBullet(m80), m80.velocityMps).perforated)
    }

    @Test
    fun `soft armor stops pistol rounds and not rifle rounds`() {
        val iiia = table.levels.getValue("IIIA")
        listOf("9mm", "45acp", "357mag").map(::shell).forEach { assertFalse(table.solve(iiia, 1.0, it, it.referenceVelocityMps).perforated, it.source) }
        listOf("556x45", "762x39", "762x54").map(::shell).forEach { assertTrue(table.solve(iiia, 1.0, it, it.referenceVelocityMps).perforated, it.source) }
    }

    @Test
    fun `a perforating bullet keeps only what the armor did not take`() {
        val m855 = shell("556x45")
        val iia = table.levels.getValue("IIA")
        val r = table.solve(iia, 1.0, m855, m855.referenceVelocityMps)
        assertTrue(r.perforated)
        val absorbed = iia.thresholdJPerM2 * m855.areaM2
        assertEquals(m855.energyJ(m855.referenceVelocityMps) - absorbed, r.energyOutJ, 1e-6)
    }

    @Test
    fun `enchantments raise the threshold, unknown ones add nothing`() {
        assertEquals(1.0, table.multiplier(emptyMap()), 0.0)
        assertEquals(1.0 + 4 * 0.04 + 2 * 0.08, table.multiplier(mapOf("minecraft:protection" to 4, "minecraft:projectile_protection" to 2)), 1e-12)
        assertEquals(1.0, table.multiplier(mapOf("minecraft:sharpness" to 5)), 0.0)
        // a 7.62x39 that gets through plain IIIA... (energy density above IIIA)
        val ak = shell("762x39")
        val iiia = table.levels.getValue("IIIA")
        val plain = table.solve(iiia, 1.0, ak, ak.referenceVelocityMps)
        val enchanted = table.solve(iiia, 1.32, ak, ak.referenceVelocityMps)
        assertTrue(enchanted.energyOutJ < plain.energyOutJ)
    }

    @Test
    fun `vanilla materials map to levels and the design values are bounded`() {
        assertEquals("IIA", table.levelFor("minecraft:leather")?.id)
        assertEquals("IV", table.levelFor("minecraft:netherite")?.id)
        assertEquals(null, table.levelFor("somemod:cardboard"))
        assertTrue(table.bluntDamageFraction in 0.0..1.0)
        val base = json("$DATA/small_arms/body_armor.json")
        assertFailsWith<IllegalArgumentException> { BodyArmorTable.fromJson(base.deepCopy().also { it.remove("blunt_damage_fraction") }) }
        assertFailsWith<IllegalArgumentException> {
            BodyArmorTable.fromJson(base.deepCopy().also { it.getAsJsonObject("materials").addProperty("minecraft:leather", "V") })
        }
        assertFailsWith<IllegalArgumentException> { BodyArmorTable.fromJson(base.deepCopy().also { it.remove("wear") }) }
    }

    @Test
    fun `a worn piece takes less out of a bullet and a spent one takes nothing`() {
        val pistol = shell("9mm")
        val iiia = table.levels.getValue("IIIA")
        assertFalse(table.solve(iiia, 1.0, pistol, pistol.referenceVelocityMps, 1.0).perforated)
        val spent = table.solve(iiia, 1.0, pistol, pistol.referenceVelocityMps, 0.0)
        assertTrue(spent.perforated)
        assertEquals(1.0, spent.energyFraction, 1e-9)
        val m855 = shell("556x45")
        val iia = table.levels.getValue("IIA")
        assertTrue(table.solve(iia, 1.0, m855, m855.referenceVelocityMps, 0.5).energyOutJ > table.solve(iia, 1.0, m855, m855.referenceVelocityMps, 1.0).energyOutJ)
    }

    @Test
    fun `wear is proportional to the bullet's energy per area and max hits empties the piece`() {
        val iiia = table.levels.getValue("IIIA")
        val threat = iiia.threats.maxBy { it.energyDensity }
        val atThreshold = threatBullet(threat)
        // A bullet exactly at the threshold takes the configured loss
        assertEquals(1.0 - table.wear.integrityLossAtThreshold, table.wornIntegrity(iiia, 1.0, atThreshold, threat.velocityMps, 1.0, 1), 1e-9)
        // A rifle round wears a pistol vest faster than a pistol round does
        val rifle = shell("762x39")
        val pistol = shell("9mm")
        val afterRifle = table.wornIntegrity(iiia, 1.0, rifle, rifle.referenceVelocityMps, 1.0, 1)
        val afterPistol = table.wornIntegrity(iiia, 1.0, pistol, pistol.referenceVelocityMps, 1.0, 1)
        assertTrue(afterRifle < afterPistol)
        // However light the bullets, the piece is spent at max hits
        val iv = table.levels.getValue("IV")
        var integrity = 1.0
        for (hit in 1 until table.wear.maxHits) {
            integrity = table.wornIntegrity(iv, 1.0, pistol, pistol.referenceVelocityMps, integrity, hit)
            assertTrue(integrity > 0.0)
        }
        assertEquals(0.0, table.wornIntegrity(iv, 1.0, pistol, pistol.referenceVelocityMps, integrity, table.wear.maxHits), 0.0)
        assertEquals(5, table.wear.maxHits)
    }
}

class SmallArmsShellDataTest {
    @Test
    fun `every shipped TACZ bullet shell has sourced small-arms data, explosive ones have none`() {
        val dir = File("$DATA/shells/tacz")
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 18)
        files.forEach { f ->
            val j = json(f.path)
            val explosive = j.get("explosive")?.asBoolean ?: false
            val sa = j.getAsJsonObject("small_arms")
            when {
                explosive -> assertEquals(null, sa, f.name)
                f.name == "generic.json" -> assertEquals(null, sa, "generic has no known bullet")
                else -> {
                    val spec = BulletSpec.fromJson(assertNotNull(sa, f.name))
                    assertTrue(spec.source.isNotBlank())
                    assertTrue(spec.referenceVelocityMps in 200.0..1100.0, "${f.name} velocity ${spec.referenceVelocityMps}")
                    assertTrue(spec.massKg in 0.001..0.05, "${f.name} mass ${spec.massKg}")
                }
            }
        }
    }
}

/** Small-arms rounds without an armor_penetration value against soft (sheet-metal) vehicles, see [SteelPenetration]. */
class SoftVehicleSteelTest {
    private val table = BlockMaterialTable.fromJson(json("$DATA/small_arms/block_materials.json"))
    private fun armor(name: String) = io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmor.fromJson(json("$DATA/vehicle_armor/$name.json"))

    private fun mm(name: String) = shell(name).let { SteelPenetration.mildSteelMm(table, it, it.referenceVelocityMps) }

    @Test
    fun `the reference round gets through exactly the FM 3-06 11 mild-steel door it was calibrated on`() {
        assertEquals(0.375 * INCH * 1000, SteelPenetration.mildSteelMm(table, table.reference, table.reference.referenceVelocityMps), 1e-9)
    }

    @Test
    fun `car sheet metal lets every round through, the engine block stops rifle ball but not a 50`() {
        val cars = armor("soft_cars")
        assertTrue(cars.soft && cars.vehicles.size > 1)
        val side = cars.zones.getValue("body").plate(io.github.jwyoon1220.dncity.ballistics.vehicle.Face.LEFT).ke
        val engine = cars.zones.getValue("body").plate(io.github.jwyoon1220.dncity.ballistics.vehicle.Face.FRONT).ke
        listOf("9mm", "45acp", "556x45", "762x39", "762x54").forEach { assertTrue(mm(it) > side, "$it ${mm(it)} mm through the door") }
        listOf("9mm", "556x45", "762x39", "762x54").forEach { assertTrue(mm(it) < engine, "$it ${mm(it)} mm through the engine") }
        assertTrue(mm("50bmg") > engine)
    }

    @Test
    fun `aircraft engine boxes are the engine zone, tank engine boxes stay hull`() {
        val air = armor("soft_aircraft")
        assertEquals("engine", air.zoneFor(99, "MAIN_ENGINE"))
        assertEquals("hull", armor("m_1a_2").zoneFor(99, "SUB_ENGINE"))
    }
}
