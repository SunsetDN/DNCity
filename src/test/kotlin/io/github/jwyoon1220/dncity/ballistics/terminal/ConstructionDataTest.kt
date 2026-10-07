// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** G. malformed data fails strictly, well-formed data parses to the structure the traversal expects. */
class ConstructionDataTest {
    private val id = Ident("test", "c")

    private fun construction(json: String) = ArmorConstruction.fromJson(id, JsonParser.parseString(json).asJsonObject)

    private fun elements(vararg e: String) = """{ "elements": [ ${e.joinToString(",")} ] }"""

    private val solid = """{ "type": "solid", "material": "test:steel", "thickness_mm": 50 }"""

    @Test
    fun `a nera style package is layers plus an effect, not a material`() {
        val c = construction(elements(
            solid,
            """{ "type": "gap", "distance_mm": 300 }""",
            """{ "type": "effect_package", "effect": "test:era", "layers": [
                 { "material": "test:steel", "thickness_mm": 4 },
                 { "material": "test:rubber", "thickness_mm": 6 },
                 { "material": "test:steel", "thickness_mm": 4 } ] }""",
            """{ "type": "solid", "material": "test:liner", "thickness_mm": 20, "role": "liner" }""",
            """{ "type": "internal_space", "depth_mm": 500 }""",
        ))
        assertEquals(5, c.elements.size)
        assertEquals(0.05, (c.elements[0] as ArmorElement.Solid).layer.thicknessM, 1e-12)
        assertEquals(0.3, (c.elements[1] as ArmorElement.Gap).distanceM, 1e-12)
        assertEquals(3, (c.elements[2] as ArmorElement.EffectPackage).layers.size)
        assertEquals(LayerRole.LINER, (c.elements[3] as ArmorElement.Solid).layer.role)
    }

    @Test
    fun `negative or zero thickness is rejected`() {
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "solid", "material": "test:steel", "thickness_mm": -5 }""")) }
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "solid", "material": "test:steel", "thickness_mm": 0 }""")) }
    }

    @Test
    fun `negative or zero gap and internal space are rejected`() {
        assertFailsWith<IllegalArgumentException> { construction(elements(solid, """{ "type": "gap", "distance_mm": -1 }""")) }
        assertFailsWith<IllegalArgumentException> { construction(elements(solid, """{ "type": "gap", "distance_mm": 0 }""")) }
        assertFailsWith<IllegalArgumentException> { construction(elements(solid, """{ "type": "internal_space", "depth_mm": 0 }""")) }
    }

    @Test
    fun `an effect package without layers is rejected`() {
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "effect_package", "effect": "test:era", "layers": [] }""")) }
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "effect_package", "effect": "test:era" }""")) }
    }

    @Test
    fun `an effect package without an effect is rejected`() {
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "effect_package", "layers": [ { "material": "test:steel", "thickness_mm": 4 } ] }""")) }
    }

    @Test
    fun `structure rules are enforced`() {
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "internal_space", "depth_mm": 500 }""", solid)) } // internal space not last
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "internal_space", "depth_mm": 500 }""")) } // no armor at all
        assertFailsWith<IllegalArgumentException> { construction("""{ "elements": [] }""") }
        assertFailsWith<IllegalArgumentException> { construction("""{ }""") }
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "mystery" }""")) }
    }

    @Test
    fun `cast is not a geometry any more and a curved layer needs a radius`() {
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "solid", "material": "test:steel", "thickness_mm": 50, "shape": "cast" }""")) }
        assertFailsWith<IllegalArgumentException> { construction(elements("""{ "type": "solid", "material": "test:steel", "thickness_mm": 50, "shape": "curved" }""")) }
        val c = construction(elements("""{ "type": "solid", "material": "test:steel", "thickness_mm": 50, "shape": "curved", "radius_m": 1.5 }"""))
        assertEquals(LayerShape.CURVED, (c.elements[0] as ArmorElement.Solid).layer.geometry.shape)
    }

    // cross references (after loading)
    private fun view(
        constructions: List<ArmorConstruction>,
        materials: List<ArmorMaterial> = listOf(Fx.steel),
        presets: Map<Ident, ResistancePreset> = emptyMap(),
        effects: List<ArmorEffectSpec> = listOf(Fx.eraSpec),
    ) = BallisticsCatalogView(materials, presets, constructions, effects)

    @Test
    fun `unknown ids are found by validation`() {
        val unknownMaterial = construction(elements("""{ "type": "solid", "material": "test:nothing", "thickness_mm": 5 }"""))
        val unknownEffect = construction(elements("""{ "type": "effect_package", "effect": "test:nope", "layers": [ { "material": "test:steel", "thickness_mm": 4 } ] }"""))
        val unknownFill = construction(elements(solid, """{ "type": "gap", "distance_mm": 10, "fill": "test:nothing" }"""))
        val problems = BallisticsValidation.validate(view(listOf(unknownMaterial, unknownEffect, unknownFill)))
        assertEquals(3, problems.size, problems.toString())
        assertTrue(problems.any { "unknown material" in it } && problems.any { "unknown effect" in it } && problems.any { "gap fill" in it })
    }

    @Test
    fun `a preset must exist and belong to the same solver`() {
        val rodPreset = ResistancePreset(Ident("test", "p_rod"), Ident("test", "long_rod"), emptyMap(), emptyMap())
        val m = ArmorMaterial.fromJson(Ident("test", "m"), JsonParser.parseString(
            """{ "density_kg_m3": 1000, "resistance": { "test:long_rod": "test:p_rod", "test:ap": "test:p_rod", "test:ce": "test:missing" } }""").asJsonObject)
        val problems = BallisticsValidation.validate(view(emptyList(), materials = listOf(m), presets = mapOf(rodPreset.id to rodPreset)))
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `models that do not exist in code are found when asked for`() {
        val p = Fx.fuzed(null)
        val v = BallisticsCatalogView(listOf(Fx.steel), emptyMap(), emptyList(), listOf(Fx.eraSpec), listOf(p))
        assertTrue(BallisticsValidation.validate(v).isEmpty()) // not asked: not checked
        val problems = BallisticsValidation.validate(v, registeredPenetrators = emptySet(), registeredEffectModels = emptySet())
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun `a sound set of data has no problems`() {
        val c = construction(elements(solid, """{ "type": "gap", "distance_mm": 100 }""", """{ "type": "effect_package", "effect": "test:era", "layers": [ { "material": "test:steel", "thickness_mm": 4 } ] }"""))
        assertTrue(BallisticsValidation.validate(view(listOf(c))).isEmpty())
    }
}
