// AGENT-DONE(claude): nera-bulge-model
package io.github.jwyoon1220.dncity.ballistics.terminal.nera

import io.github.jwyoon1220.dncity.ballistics.terminal.Ident
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallFragments
import io.github.jwyoon1220.dncity.ballistics.terminal.SpallSource
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpallFragmentsTest {
    private val axis = V3(1.0, 2.0, 3.0).normalize()
    private val source = SpallSource(V3(1.0, 1.0, 1.0), axis, 0.5, 40_000.0, 0.2, Ident("test", "steel"))

    @Test
    fun `the swarm carries exactly the energy and mass of the source`() {
        val f = SpallFragments.expand(source, seed = 1L, count = 64)
        assertEquals(64, f.size)
        assertEquals(source.energyJ, f.sumOf { it.kineticEnergyJ }, source.energyJ * 1e-9)
        assertEquals(source.fragmentMassKg, f.sumOf { it.massKg }, 1e-12)
    }

    @Test
    fun `every fragment flies inside the cone and from the origin`() {
        val f = SpallFragments.expand(source, seed = 2L, count = 200)
        val limit = cos(source.coneHalfAngleRad) - 1e-9
        assertTrue(f.all { it.velocity.normalize().dot(axis) >= limit })
        assertTrue(f.all { it.origin == source.origin })
    }

    @Test
    fun `fragments spread over all three dimensions, not along one plane`() {
        val f = SpallFragments.expand(source, seed = 3L, count = 200)
        val t1 = axis.cross(V3(0.0, 0.0, 1.0)).normalize()
        val t2 = axis.cross(t1).normalize()
        assertTrue(f.any { it.velocity.dot(t1) > 0 } && f.any { it.velocity.dot(t1) < 0 })
        assertTrue(f.any { it.velocity.dot(t2) > 0 } && f.any { it.velocity.dot(t2) < 0 })
    }

    @Test
    fun `same seed same swarm, other seed another one`() {
        assertEquals(SpallFragments.expand(source, 9L), SpallFragments.expand(source, 9L))
        assertNotEquals(SpallFragments.expand(source, 9L), SpallFragments.expand(source, 10L))
    }

    @Test
    fun `a source with no energy has nothing to fly`() {
        val dead = SpallSource(V3.ZERO, axis, 0.5, 0.0, 0.0, Ident("test", "steel"))
        assertTrue(SpallFragments.expand(dead, 1L).isEmpty())
    }
}
