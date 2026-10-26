// AGENT-DONE(claude): blast-injury
package io.github.jwyoon1220.dncity.ballistics.vehicle.damage

import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.ballistics.vehicle.VehicleArmor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlastInjuryTest {
    @Test
    fun `kinney-graham gives one atmosphere at a scaled distance of 2,75`() {
        assertEquals(100.0, BlastInjury.overpressureKpa(2.75, 1.0), 2.0)
        // cube-root scaling: 8 kg at twice the distance is the same wave
        assertEquals(BlastInjury.overpressureKpa(2.0, 1.0), BlastInjury.overpressureKpa(4.0, 8.0), 1e-6)
    }

    @Test
    fun `overpressure falls with distance, doubles inside a closed hull, and needs explosive`() {
        var last = Double.MAX_VALUE
        for (d in listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)) {
            val p = BlastInjury.overpressureKpa(d, 3.4)
            assertTrue(p < last, "at $d m")
            last = p
        }
        assertEquals(2 * BlastInjury.overpressureKpa(3.0, 3.4), BlastInjury.overpressureKpa(3.0, 3.4, confined = true), 1e-9)
        assertEquals(0.0, BlastInjury.overpressureKpa(1.0, 0.0))
        // a point-blank burst is not infinite
        assertEquals(BlastInjury.overpressureKpa(0.3, 1.0), BlastInjury.overpressureKpa(0.0, 1.0))
    }

    @Test
    fun `health share follows the FM 8-9 and Glasstone anchors`() {
        assertEquals(0.0, BlastInjury.healthShare(10.0))
        assertEquals(0.0, BlastInjury.healthShare(22.0))
        assertEquals(0.1, BlastInjury.healthShare(68.9), 1e-9)
        assertEquals(0.4, BlastInjury.healthShare(193.0), 1e-9)
        assertEquals(1.0, BlastInjury.healthShare(634.0))
        assertEquals(1.0, BlastInjury.healthShare(5000.0))
        var last = -1.0
        for (kpa in 0..800 step 10) {
            val share = BlastInjury.healthShare(kpa.toDouble())
            assertTrue(share >= last && share in 0.0..1.0)
            last = share
        }
    }

    @Test
    fun `a 3OF26 bursting on a pickup kills the man beside it and only rattles one five metres off`() {
        val kg = 3.4  // 3OF26: 3.4 kg A-IX-2
        assertEquals(1.0, BlastInjury.healthShare(BlastInjury.overpressureKpa(1.5, kg)))
        assertTrue(BlastInjury.healthShare(BlastInjury.overpressureKpa(3.0, kg)) in 0.3..0.6)
        assertTrue(BlastInjury.healthShare(BlastInjury.overpressureKpa(5.0, kg)) < 0.15)
        assertEquals(0.0, BlastInjury.healthShare(BlastInjury.overpressureKpa(12.0, kg)))
        // the same charge inside a closed hull: walls double it
        assertTrue(BlastInjury.healthShare(BlastInjury.overpressureKpa(3.0, kg, confined = true)) > 0.6)
    }

    @Test
    fun `a 40 mm grenade hurts at arm's length and not at two metres`() {
        val kg = 0.032
        assertTrue(BlastInjury.healthShare(BlastInjury.overpressureKpa(0.5, kg)) > 0.5)
        assertTrue(BlastInjury.healthShare(BlastInjury.overpressureKpa(1.0, kg)) in 0.05..0.2)
        assertEquals(0.0, BlastInjury.healthShare(BlastInjury.overpressureKpa(2.0, kg)))
    }

    @Test
    fun `a closed hull that held passes on less the thicker it is, but never nothing`() {
        assertEquals(1.0, BlastInjury.transmitted(0.0), 1e-9)
        var last = 2.0
        for (mm in listOf(0.0, 5.0, 16.0, 40.0, 100.0, 525.0, 1000.0)) {
            val t = BlastInjury.transmitted(mm)
            assertTrue(t < last && t >= BlastInjury.TRANSMISSION_FLOOR, "$mm mm")
            last = t
        }
        assertEquals(BlastInjury.TRANSMISSION_FLOOR, BlastInjury.transmitted(1000.0), 1e-6)
    }

    @Test
    fun `a 3OF26 on a tank's front plate still hurts the crew behind it`() {
        val kg = 3.4
        // T-90A turret front, 525 mm, crew about 1.5 m behind the burst
        val tank = BlastInjury.healthShare(BlastInjury.transmitted(525.0) * BlastInjury.overpressureKpa(1.5, kg))
        assertTrue(tank in 0.1..0.3, "tank crew: $tank")
        // the same burst on 10 mm of an APC's plate is far worse, and in the open it kills
        val apc = BlastInjury.healthShare(BlastInjury.transmitted(10.0) * BlastInjury.overpressureKpa(1.5, kg))
        assertTrue(apc > 0.8, "apc crew: $apc")
        assertEquals(1.0, BlastInjury.healthShare(BlastInjury.overpressureKpa(1.5, kg)))
        // a 40 mm grenade on a tank: nothing
        assertEquals(0.0, BlastInjury.healthShare(BlastInjury.transmitted(525.0) * BlastInjury.overpressureKpa(1.5, 0.032)))
    }

    @Test
    fun `soft vehicles are open-top, armored ones are not`() {
        fun armor(name: String) = VehicleArmor.fromJson(
            JsonParser.parseString(File("src/main/resources/data/dncity/dncity/vehicle_armor/$name.json").readText()).asJsonObject,
        )
        for (open in listOf("soft_cars", "soft_speedboat", "soft_light")) assertTrue(armor(open).openTop, open)
        for (closed in listOf("m_1a_2", "t_90a", "bmp_2", "bradley", "soft_aircraft", "soft_ju_87")) assertFalse(armor(closed).openTop, closed)
    }
}
