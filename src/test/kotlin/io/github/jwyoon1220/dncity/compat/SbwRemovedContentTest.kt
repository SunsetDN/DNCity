// AGENT-DONE(claude): sbw-removed-content
package io.github.jwyoon1220.dncity.compat

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SBW = "mods/SuperbWarfare/src"

class SbwRemovedContentTest {
    private val names = SbwRemovedNames.NAMES

    private fun stripped(path: String) = SbwRemovedNames.isRemovedRecipe("superbwarfare", path)

    @Test
    fun `every removed name is something SuperbWarfare actually has`() {
        val lang = JsonParser.parseString(File("$SBW/main/resources/assets/superbwarfare/lang/en_us.json").readText().removePrefix("﻿"))
            .asJsonObject
        val sbw = "$SBW/main/resources/data/superbwarfare/sbw"
        for (name in names) {
            val known = lang.has("entity.superbwarfare.$name") || lang.has("item.superbwarfare.$name") ||
                File("$sbw/guns/$name.json").isFile || File("$sbw/vehicles/$name.json").isFile
            assertTrue(known, "typo? $name is not in SuperbWarfare")
        }
    }

    @Test
    fun `every SuperbWarfare hand-held gun is gone except the guided launchers and the repair tool`() {
        val guns = File("$SBW/main/resources/data/superbwarfare/sbw/guns").listFiles { f -> f.extension == "json" }!!
            .map { it.nameWithoutExtension }.toSet()
        assertEquals(guns - setOf("javelin", "igla_9k38", "repair_tool"), SbwRemovedNames.SMALL_ARMS)
        // the vehicles' own internal guns must stay
        assertFalse("vehicle_gun" in names || "empty_gun" in names)
    }

    @Test
    fun `no mob is armed with a removed gun`() {
        val mobGuns = File("$SBW/main/resources/data/superbwarfare/sbw/mob_guns").listFiles { f -> f.extension == "json" }!!
        for (file in mobGuns) {
            val text = file.readText()
            for (name in names) assertFalse("\"superbwarfare:$name\"" in text, "${file.name} still hands out $name")
        }
    }

    @Test
    fun `recipes named after removed content are stripped, the rest stay`() {
        val recipes = File("$SBW/generated/resources/data/superbwarfare/recipe").walkTopDown()
            .filter { it.extension == "json" }.map { it.nameWithoutExtension }.toSet()
        val stripped = recipes.filter(::stripped).toSet()
        for (expected in listOf("annihilator", "yx_100", "prism_tank", "laser_tower", "waveforce_tower", "swarm_drone",
            "sentinel_smithing", "devotion_blueprint_copy", "ql_1031_blueprint_copy_researching", "secondary_cataclysm_smithing",
            "ak_47_smithing", "m_4_blueprint_copy_researching", "rpg_blueprint_copy", "homemade_shotgun", "minigun_smithing")) {
            assertTrue(expected in stripped, "$expected should be stripped")
        }
        // a part that shares a word, real vehicles and the shells the tanks still use stay
        for (kept in listOf("laser_unit", "m_1a_2", "t_90a", "large_shell_he", "kirov", "rpg_rocket_standard", "rpg_rocket_tbg",
            "taser_electrode", "javelin_smithing", "igla_9k38_blueprint_copy", "repair_tool")) {
            assertFalse(stripped(kept), "$kept must stay")
        }
        assertTrue(stripped.all { r -> names.any { r == it || r.startsWith(it + "_") } })
        // only SuperbWarfare's own recipes are touched
        assertFalse(SbwRemovedNames.isRemovedRecipe("dncity", "sentinel"))
    }

    @Test
    fun `no DNCity data still targets removed content`() {
        val data = File("src/main/resources/data/dncity/dncity")
        val offenders = data.walkTopDown().filter { it.extension == "json" }.filter { f ->
            val obj = JsonParser.parseString(f.readText()).asJsonObject
            val refs = listOfNotNull(obj["entity"], obj["key"]).map { it.asString } +
                (obj.getAsJsonArray("vehicles")?.map { it.asString } ?: emptyList())
            refs.any { ref -> names.any { ref == "superbwarfare:$it" } }
        }.map { it.path }.toList()
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `the loot modifier is wired up`() {
        val global = JsonParser.parseString(File("src/main/resources/data/neoforge/loot_modifiers/global_loot_modifiers.json").readText()).asJsonObject
        assertFalse(global["replace"].asBoolean)
        assertTrue(global.getAsJsonArray("entries").any { it.asString == "dncity:remove_sbw_content" })
        val modifier = JsonParser.parseString(File("src/main/resources/data/dncity/loot_modifiers/remove_sbw_content.json").readText()).asJsonObject
        assertEquals("dncity:remove_sbw_content", modifier["type"].asString)
    }
}
