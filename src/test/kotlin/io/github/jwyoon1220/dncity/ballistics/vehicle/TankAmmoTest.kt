// AGENT-DONE(claude): tank-ammo
package io.github.jwyoon1220.dncity.ballistics.vehicle

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RES = "src/main/resources"
private const val TANK_AMMO = "$RES/data/dncity/dncity/shells/superbwarfare/tank_ammo"
private const val SBW_VEHICLES = "mods/SuperbWarfare/src/main/resources/data/superbwarfare/sbw/vehicles"

private fun json(file: File): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

class TankAmmoTest {
    private val rounds: Map<String, JsonObject> =
        File(TANK_AMMO).listFiles { f -> f.extension == "json" }!!.associate { it.nameWithoutExtension to json(it) }
    private val en = json(File("$RES/assets/dncity/lang/en_us.json"))
    private val ko = json(File("$RES/assets/dncity/lang/ko_kr.json"))

    private fun spec(id: String) = PenetratorSpec.fromJson(rounds.getValue(id).getAsJsonObject("armor_penetration"))
    private fun caliber(id: String) = rounds.getValue(id)["caliber_mm"].asInt

    @Test
    fun `the rounds of each calibre, high explosive only for the Soviet 125 mm gun`() {
        assertEquals(
            setOf("m829", "m829a1", "m829a2", "m829a3", "m830", "m830a1", "3bm42", "3bm46", "3bm60", "3bk18m", "3bk29m", "3of26"),
            rounds.keys,
        )
        fun kinds(cal: Int) = rounds.keys.filter { caliber(it) == cal }.groupingBy { spec(it).kind }.eachCount()
        assertEquals(mapOf(PenetratorKind.KE to 4, PenetratorKind.HEAT to 2), kinds(120))
        assertEquals(mapOf(PenetratorKind.KE to 3, PenetratorKind.HEAT to 2, PenetratorKind.HE to 1), kinds(125))
        // 3OF26: 3.4 kg A-IX-2 (Wikipedia)
        assertEquals(3.4, spec("3of26").fillerKg)
    }

    @Test
    fun `every round is a complete TACZ ammo`() {
        for ((id, shell) in rounds) {
            assertEquals("superbwarfare:cannon_shell", shell["entity"].asString, id)
            assertEquals("dncity:$id", shell["key"].asString, id)

            val index = File("$RES/data/dncity/index/ammo/$id.json")
            assertTrue(index.isFile, "$id: no TACZ ammo index")
            val idx = json(index)
            assertEquals("dncity:${id}_display", idx["display"].asString)
            assertTrue(idx["stack_size"].asInt >= 1)

            val display = json(File("$RES/assets/dncity/display/ammo/${id}_display.json"))
            val slot = display["slot"].asString
            assertEquals("dncity:ammo/slot/$id", slot)
            val icon = ImageIO.read(File("$RES/assets/dncity/textures/ammo/slot/$id.png"))
            assertEquals(32, icon.width, "$id icon")
            assertEquals(32, icon.height, "$id icon")

            val recipe = json(File("$RES/data/dncity/recipe/ammo/$id.json"))
            assertEquals("tacz:gun_smith_table_crafting", recipe["type"].asString)
            val result = recipe.getAsJsonObject("result")
            assertEquals("ammo", result["type"].asString)
            assertEquals("dncity:$id", result["id"].asString)
            assertTrue(recipe.getAsJsonArray("materials").size() > 0)

            for (key in listOf(idx["name"].asString, idx["tooltip"].asString, "weapon.dncity.$id")) {
                assertTrue(en.has(key), "en_us lacks $key")
                assertTrue(ko.has(key), "ko_kr lacks $key")
            }
        }
    }

    @Test
    fun `tank guns load the rounds of their calibre instead of the generic AP shell`() {
        val guns = mapOf("m_1a_2" to 120, "t_90a" to 125, "ztz_99a" to 125)
        for ((vehicle, cal) in guns) {
            val cannon = json(File("$SBW_VEHICLES/$vehicle.json")).getAsJsonObject("Weapons").getAsJsonObject("Cannon")
            val ammo = cannon.getAsJsonArray("AmmoType")
            val names = ammo.map { if (it.isJsonPrimitive) it.asString else it.asJsonObject["Ammo"].asString }
            assertFalse("superbwarfare:large_shell_ap" in names, "$vehicle still loads the generic AP shell")
            assertFalse("superbwarfare:large_shell_he" in names, "$vehicle still loads the generic HE shell")
            assertTrue("superbwarfare:large_shell_gs" in names, "$vehicle lost its grapeshot")

            val loaded = ammo.filter { it.isJsonObject && it.asJsonObject["Ammo"].asString.startsWith("@dncity:") }.map { it.asJsonObject }
            val ids = loaded.map { it["Ammo"].asString.removePrefix("@dncity:") }.toSet()
            assertEquals(rounds.keys.filter { caliber(it) == cal }.toSet(), ids, vehicle)
            for (entry in loaded) {
                val id = entry["Ammo"].asString.removePrefix("@dncity:")
                val o = entry.getAsJsonObject("Override")
                val expected = if (spec(id).kind == PenetratorKind.KE) "AP" else "HE"  // HEAT and HE both burst
                assertEquals(expected, o["ShellType"].asString, "$vehicle $id")
                assertEquals("weapon.dncity.$id", o["Name"].asString)
                assertTrue(o["Velocity"].asDouble > 0)
            }
        }
    }

    @Test
    fun `the newer rod of each pair goes deeper at every range`() {
        val generations = listOf(listOf("m829", "m829a1", "m829a2", "m829a3"), listOf("3bm42", "3bm46", "3bm60"))
        for ((old, new) in generations.flatMap { it.zipWithNext() }) {
            // the sources stop at 2 km; past ~3 km M829A1's steep sourced falloff (50 mm/km) dips under M829's 19 mm/km
            for (d in listOf(0.0, 1000.0, 2000.0, 2500.0)) {
                assertTrue(spec(new).penetrationAt(d) > spec(old).penetrationAt(d), "$new vs $old at $d m")
                assertTrue(spec(old).penetrationAt(d) < spec(old).penetrationAt(d - 500.0), "$old loses penetration with range")
            }
        }
        // shaped charges do not care about range
        assertEquals(spec("m830").penetrationAt(0.0), spec("m830").penetrationAt(3000.0))
        assertTrue(spec("3bk29m").tandem)
        assertFalse(spec("3bk18m").tandem)
    }

    @Test
    fun `only the 125 mm tanks get high explosive`() {
        val he = rounds.keys.filter { spec(it).kind == PenetratorKind.HE }
        assertEquals(listOf("3of26"), he)
        val m1 = json(File("$SBW_VEHICLES/m_1a_2.json")).getAsJsonObject("Weapons").getAsJsonObject("Cannon").getAsJsonArray("AmmoType")
        assertTrue(m1.none { it.isJsonObject && it.asJsonObject.getAsJsonObject("Override")?.get("ShellType")?.asString == "HE" &&
            spec(it.asJsonObject["Ammo"].asString.removePrefix("@dncity:")).kind == PenetratorKind.HE })
        for (vehicle in listOf("t_90a", "ztz_99a")) {
            val ammo = json(File("$SBW_VEHICLES/$vehicle.json")).getAsJsonObject("Weapons").getAsJsonObject("Cannon").getAsJsonArray("AmmoType")
            assertTrue(ammo.any { it.isJsonObject && it.asJsonObject["Ammo"].asString == "@dncity:3of26" }, vehicle)
        }
    }

    @Test
    fun `against the T-90A, kontakt-5 is what stops the single charge and the tandem goes through it`() {
        val t90 = VehicleArmor.fromJson(json(File("$RES/data/dncity/dncity/vehicle_armor/t_90a.json")))
        val hullFront = t90.zones.getValue("hull").plate(Face.FRONT)

        val single = ArmorSolver.solve(spec("3bk18m"), hullFront, 0.0, 1000.0, caliberMm = 125.0)
        assertEquals(HitOutcome.NOT_PENETRATED, single.outcome)
        assertTrue(single.reactiveMm > 0)
        val tandem = ArmorSolver.solve(spec("3bk29m"), hullFront, 0.0, 1000.0, caliberMm = 125.0)
        assertEquals(HitOutcome.PENETRATED, tandem.outcome)
        assertEquals(0.0, tandem.reactiveMm)
        // the bricks still go off, so the next hit there meets bare armor
        assertEquals(ReactiveType.ERA, tandem.reactiveFired)
        val second = ArmorSolver.solve(spec("3bk18m"), hullFront, 0.0, 1000.0, caliberMm = 125.0, reactiveIntact = false)
        assertEquals(HitOutcome.PENETRATED, second.outcome)
    }

    @Test
    fun `M829 at 2 km beats the T-90A turret front only where the ERA is gone`() {
        val t90 = VehicleArmor.fromJson(json(File("$RES/data/dncity/dncity/vehicle_armor/t_90a.json")))
        val turretFront = t90.zones.getValue("turret").plate(Face.FRONT)
        assertEquals(HitOutcome.NOT_PENETRATED, ArmorSolver.solve(spec("m829"), turretFront, 0.0, 2000.0, caliberMm = 120.0).outcome)
        assertEquals(
            HitOutcome.PENETRATED,
            ArmorSolver.solve(spec("m829"), turretFront, 0.0, 2000.0, caliberMm = 120.0, reactiveIntact = false).outcome,
        )
    }
}
