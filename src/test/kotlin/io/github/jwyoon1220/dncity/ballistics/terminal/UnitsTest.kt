// AGENT-DONE(claude): terminal-ballistics-core
package io.github.jwyoon1220.dncity.ballistics.terminal

import com.google.gson.JsonParser
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitsTest {
    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun `120 mm is 0_12 m`() = assertEquals(0.12, Units.mmToM(120.0), 1e-12)

    @Test
    fun `17_6 g per cm3 is 17600 kg per m3`() = assertEquals(17600.0, Units.gPerCm3ToKgM3(17.6), 1e-9)

    @Test
    fun `17_6 g is 0_0176 kg`() = assertEquals(0.0176, Units.gToKg(17.6), 1e-12)

    @Test
    fun `json in friendly units is converted to SI`() {
        val j = json("""{ "diameter_mm": 120, "mass_g": 4600, "density_g_cm3": 17.6 }""")
        assertEquals(0.12, SiJson.requireLength(j, "diameter"), 1e-12)
        assertEquals(4.6, SiJson.requireMass(j, "mass"), 1e-12)
        assertEquals(17600.0, SiJson.density(j)!!, 1e-9)
    }

    @Test
    fun `json in SI is taken as it is`() {
        val j = json("""{ "diameter_m": 0.12, "mass_kg": 4.6, "density_kg_m3": 17600 }""")
        assertEquals(0.12, SiJson.requireLength(j, "diameter"), 1e-12)
        assertEquals(4.6, SiJson.requireMass(j, "mass"), 1e-12)
        assertEquals(17600.0, SiJson.density(j)!!, 1e-9)
    }

    @Test
    fun `missing quantity is null or an error, never zero`() {
        val j = json("{}")
        assertNull(SiJson.length(j, "diameter"))
        assertNull(SiJson.mass(j, "mass"))
        assertNull(SiJson.density(j))
        assertFailsWith<IllegalArgumentException> { SiJson.requireLength(j, "diameter") }
        assertFailsWith<IllegalArgumentException> { SiJson.requireMass(j, "mass") }
    }

    @Test
    fun `line of sight thickness follows the angle from the normal`() {
        assertEquals(0.05, ImpactAngle.losThicknessM(0.05, 0.0), 1e-12)
        assertEquals(0.10, ImpactAngle.losThicknessM(0.05, PI / 3), 1e-9)
        assertTrue(ImpactAngle.losThicknessM(0.05, PI / 2).isInfinite())
    }
}
