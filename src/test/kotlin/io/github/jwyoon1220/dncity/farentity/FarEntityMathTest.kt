// AGENT-DONE(claude): far-entity-render
package io.github.jwyoon1220.dncity.farentity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FarEntityMathTest {
    private fun sample(t: Long, x: Double, yaw: Float = 0f) = FarEntityMath.Sample(t, x, 64.0, 0.0, yaw, yaw, 0f)

    @Test
    fun `farther players are sent less often`() {
        assertEquals(2, FarEntityMath.updateIntervalTicks(100.0))
        assertEquals(4, FarEntityMath.updateIntervalTicks(700.0))
        assertEquals(8, FarEntityMath.updateIntervalTicks(3000.0))
    }

    @Test
    fun `angles survive the byte round trip within one step and wrap the short way`() {
        for (deg in listOf(0f, 45f, 90f, 179f, -90f, -179f, 359f)) {
            val back = FarEntityMath.unpackDegrees(FarEntityMath.packDegrees(deg))
            val diff = ((back - deg) % 360f + 540f) % 360f - 180f
            assertTrue(kotlin.math.abs(diff) <= 360f / 256f + 1e-3f, "deg=$deg back=$back")
        }
        // 170 -> -170 goes through 180, not through 0
        assertEquals(180f, FarEntityMath.lerpDegrees(0.5f, 170f, -170f), 1e-4f)
    }

    @Test
    fun `interpolates between the samples around the render time and never extrapolates`() {
        val buffer = FarEntityMath.SampleBuffer()
        assertNull(buffer.at(0))
        buffer.add(sample(100, 0.0))
        buffer.add(sample(200, 10.0))
        assertEquals(5.0, buffer.at(150)!!.x, 1e-9)
        assertEquals(0.0, buffer.at(50)!!.x, 1e-9)
        assertEquals(10.0, buffer.at(900)!!.x, 1e-9)
        // an out-of-order sample is ignored instead of breaking the time order
        buffer.add(sample(150, 99.0))
        assertEquals(5.0, buffer.at(150)!!.x, 1e-9)
    }

    @Test
    fun `the buffer keeps only its capacity`() {
        val buffer = FarEntityMath.SampleBuffer(capacity = 3)
        for (i in 1..10) buffer.add(sample(i * 10L, i.toDouble()))
        // oldest kept is t=80
        assertEquals(8.0, buffer.at(0)!!.x, 1e-9)
    }

    @Test
    fun `compression keeps the projected size and leaves near things alone`() {
        assertEquals(1.0, FarEntityMath.compressionScale(100.0, 691.2))
        val scale = FarEntityMath.compressionScale(2000.0, 691.2)
        assertEquals(691.2, 2000.0 * scale, 1e-9)
        // angular size = size / distance is unchanged: (2 * s) / (2000 * s) == 2 / 2000
        assertEquals(2.0 / 2000.0, (2.0 * scale) / (2000.0 * scale), 1e-12)
    }

    @Test
    fun `a hill between the eye and the target occludes it, open ground does not`() {
        // a ridge at x = 500..510, 100 blocks high; ground elsewhere ends at y = 60
        val hill = FarEntityMath.ColumnSource { x, _ -> if (x in 500..510) intArrayOf(-64, 100) else intArrayOf(-64, 60) }
        val eye = doubleArrayOf(0.0, 70.0, 0.5)
        val target = doubleArrayOf(1000.0, 62.0, 0.5)
        assertTrue(FarEntityMath.isOccluded(eye, target, startDistance = 192.0, endMargin = 1.5, columns = hill))

        val flat = FarEntityMath.ColumnSource { _, _ -> intArrayOf(-64, 60) }
        assertFalse(FarEntityMath.isOccluded(eye, target, startDistance = 192.0, endMargin = 1.5, columns = flat))
    }

    @Test
    fun `terrain inside the vanilla radius and in the target's own column is not marched`() {
        // the only obstacle is at x = 100, inside the 192-block vanilla radius: the depth buffer handles that one
        val nearWall = FarEntityMath.ColumnSource { x, _ -> if (x == 100) intArrayOf(-64, 200) else null }
        val eye = doubleArrayOf(0.0, 70.0, 0.5)
        val target = doubleArrayOf(1000.0, 70.0, 0.5)
        assertFalse(FarEntityMath.isOccluded(eye, target, startDistance = 192.0, endMargin = 1.5, columns = nearWall))
        // a column the target stands in does not hide it
        val ownColumn = FarEntityMath.ColumnSource { x, _ -> if (x == 1000) intArrayOf(-64, 200) else null }
        assertFalse(FarEntityMath.isOccluded(eye, target, startDistance = 192.0, endMargin = 1.5, columns = ownColumn))
    }

    @Test
    fun `peek eyes step across the sight line and up, not along it`() {
        val eye = doubleArrayOf(0.0, 70.0, 0.0)
        val eyes = FarEntityMath.peekEyes(eye, doubleArrayOf(1000.0, 70.0, 0.0), sideways = 1.0, up = 1.0)
        assertEquals(4, eyes.size)
        // looking along +x, the side steps are along z
        assertTrue(eyes.any { it[2] == 1.0 && it[0] == 0.0 })
        assertTrue(eyes.any { it[2] == -1.0 && it[0] == 0.0 })
        assertTrue(eyes.any { it[1] == 71.0 })
        // straight up or down at the target: no horizontal direction, only the eye and the jump
        assertEquals(2, FarEntityMath.peekEyes(eye, doubleArrayOf(0.0, 10.0, 0.0)).size)
    }

    @Test
    fun `a player behind a ridge is hidden, but shows once the viewer could lean or step past its end`() {
        // cover 80 blocks high at x = 100..110 that ends at z = -1 (columns z <= -2); open ground elsewhere
        val ridge = FarEntityMath.ColumnSource { x, z -> if (x in 100..110 && z <= -2) intArrayOf(-64, 80) else intArrayOf(-64, 60) }
        val target = listOf(doubleArrayOf(1000.0, 61.6, -1.5), doubleArrayOf(1000.0, 61.0, -1.5), doubleArrayOf(1000.0, 60.2, -1.5))
        val hidden = doubleArrayOf(0.0, 70.0, -1.5)
        assertFalse(FarEntityMath.isVisibleFromAny(listOf(hidden), target, 0.5, 1.5, ridge))
        // one step to the side clears the ridge's end: the server already sends the player
        assertTrue(FarEntityMath.isVisibleFromAny(FarEntityMath.peekEyes(hidden, target[0]), target, 0.5, 1.5, ridge))
    }

    @Test
    fun `columns without data never occlude, and overhangs only where the ray is inside them`() {
        val eye = doubleArrayOf(0.0, 70.0, 0.5)
        val target = doubleArrayOf(1000.0, 70.0, 0.5)
        assertFalse(FarEntityMath.isOccluded(eye, target, 192.0, 1.5, FarEntityMath.ColumnSource { _, _ -> null }))
        // a floating span at y 80..90 is above a level ray at y = 70
        val overhang = FarEntityMath.ColumnSource { x, _ -> if (x == 600) intArrayOf(-64, 60, 80, 90) else intArrayOf(-64, 60) }
        assertFalse(FarEntityMath.isOccluded(eye, target, 192.0, 1.5, overhang))
        val wall = FarEntityMath.ColumnSource { x, _ -> if (x == 600) intArrayOf(-64, 60, 65, 75) else intArrayOf(-64, 60) }
        assertTrue(FarEntityMath.isOccluded(eye, target, 192.0, 1.5, wall))
    }
}
