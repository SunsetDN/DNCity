// AGENT-DONE(claude): tacz-launcher-sounds
package io.github.jwyoon1220.dncity.compat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PACK = "mods/TACZ-1.21.1/src/main/resources/assets/tacz/custom/tacz_default_gun"

/**
 * TACZ's SoundPlayManager (the fork) plays launchers (gun type "rpg") through the vanilla sound engine with the gun pack's own
 * sounds instead of FMOD. That only works if every launcher has those sounds, which is what this checks.
 */
class TaczLauncherSoundTest {
    private fun json(file: File): JsonObject =
        JsonParser.parseString(file.readText().lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")).asJsonObject

    private val launchers: Map<String, JsonObject> = File("$PACK/data/tacz/index/guns").listFiles { f -> f.extension == "json" }!!
        .associate { it.nameWithoutExtension to json(it) }
        .filterValues { it["type"].asString == "rpg" }

    @Test
    fun `the launchers are the RPG-7 and the M320`() {
        assertEquals(setOf("rpg7", "m320"), launchers.keys)
    }

    @Test
    fun `every launcher has its own fire, reload, draw and holster sounds on disk`() {
        for ((gun, index) in launchers) {
            val display = index["display"].asString.substringAfter(':')
            val sounds = json(File("$PACK/assets/tacz/display/guns/$display.json")).getAsJsonObject("sounds")
            for (key in listOf("shoot", "reload_empty", "draw", "put_away")) {
                assertTrue(sounds.has(key), "$gun has no $key sound")
                val id = sounds[key].asString
                val ogg = File("$PACK/assets/${id.substringBefore(':')}/tacz_sounds/${id.substringAfter(':')}.ogg")
                assertTrue(ogg.isFile, "$gun: $id is not on disk ($ogg)")
            }
        }
    }

    @Test
    fun `the fork routes launchers past FMOD`() {
        val source = File("mods/TACZ-1.21.1/src/main/java/com/tacz/guns/client/sound/SoundPlayManager.java").readText()
        assertTrue("\"rpg\".equals(index.getType())" in source)
        // shoot, reload, bolt, draw and holster each ask before going to FMOD
        assertEquals(5, Regex("""if \(usesVanillaSounds\(entity\)\)""").findAll(source).count())
    }
}
