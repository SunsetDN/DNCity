// AGENT-DONE(claude): cartridge-items
package io.github.jwyoon1220.dncity.item.cartridge

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val ASSETS = "src/main/resources/assets/dncity"

class CartridgeCatalogTest {
    private fun lang(name: String) = JsonParser.parseString(File("$ASSETS/lang/$name.json").readText()).asJsonObject

    @Test
    fun everyCaliberHasATracerAndUniqueItems() {
        val rounds = CartridgeCatalog.rounds
        assertTrue(rounds.size > 20)
        for (caliber in CartridgeCatalog.calibers) {
            assertTrue(caliber.rounds.any { it.tracer }, caliber.id)
            assertTrue(caliber.taczAmmo.startsWith("tacz:"), caliber.id)
        }
        assertEquals(rounds.size, rounds.map { it.itemPath }.toSet().size)
    }

    @Test
    fun usDesignationsAreInTheEnglishName() {
        val m995 = CartridgeCatalog.rounds.single { it.itemPath == "cartridge_556x45_m995" }
        assertEquals("5.56x45 M995 AP", m995.designationEn)
        assertEquals("9x19 RIP", CartridgeCatalog.rounds.single { it.itemPath == "cartridge_9x19_rip" }.designationEn)
        val m20 = CartridgeCatalog.rounds.single { it.itemPath == "cartridge_50bmg_m20" }
        assertEquals(".50 BMG M20 API-T", m20.designationEn)
        assertTrue(m20.tracer)
    }

    /** The generator script was re-run after the last catalog change: every round has a model, an icon and both names. */
    @Test
    fun generatedAssetsMatchTheCatalog() {
        val en = lang("en_us")
        val ko = lang("ko_kr")
        for (round in CartridgeCatalog.rounds) {
            val key = "item.dncity.${round.itemPath}"
            assertEquals(round.designationEn, en[key]?.asString, key)
            assertTrue(ko.has(key), "$key missing in ko_kr")
            assertTrue(File("$ASSETS/models/item/${round.itemPath}.json").isFile, "${round.itemPath} model")
            assertTrue(File("$ASSETS/textures/item/cartridge/${round.caliber.id}_${round.id}.png").isFile, "${round.itemPath} icon")
        }
        assertEquals("5.56mm 철갑탄", ko["item.dncity.cartridge_556x45_m995"].asString)
        assertEquals("탄약", ko["itemGroup.dncity.ammo"].asString)
    }

    @Test
    fun aCaliberWithoutTracerIsRejected() {
        val json = JsonParser.parseString(
            """{"calibers":[{"id":"x","tacz_ammo":"tacz:x","name_en":"X","stack_size":10,
               "rounds":[{"id":"fmj","type":"FMJ"}]}]}"""
        ).asJsonObject
        assertFailsWith<IllegalArgumentException> { CartridgeCatalog.parse(json) }
    }
}
