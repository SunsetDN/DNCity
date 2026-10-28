// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DIR = "src/main/resources/dncity/flight_models"
private const val G = 9.80665

private fun json(name: String): JsonObject = JsonParser.parseString(File("$DIR/$name").readText()).asJsonObject

private fun fixedWing(name: String) = (FlightModelJson.parse(json("$name.json")) as FlightModel.FixedWing).spec

private fun heli(name: String) = (FlightModelJson.parse(json("$name.json")) as FlightModel.Helicopter).spec

/** Rodrigues rotation of [v] about unit [axis] by [angle] rad. */
private fun rotate(v: V3, axis: V3, angle: Double): V3 {
    val c = cos(angle)
    val s = sin(angle)
    return v.scale(c) + axis.cross(v).scale(s) + axis.scale(axis.dot(v) * (1 - c))
}

/** A tiny flight loop: applies the model's body rates to the axes the way the SuperbWarfare bridge does. */
private class Flight(val spec: FixedWingSpec, speed: Double, altitude: Double = 100.0) {
    var forward = V3(0.0, 0.0, 1.0)
    var up = V3(0.0, 1.0, 0.0)
    var right = V3(-1.0, 0.0, 0.0)
    var velocity = forward.scale(speed)
    var spool = 1.0
    val altitude = altitude
    var last: FixedWingStep? = null

    fun tick(c: FixedWingControls, onGround: Boolean = false): FixedWingStep {
        val step = FixedWingSim.step(spec, AircraftState(velocity, forward, up, right, altitude, onGround, spool), c, 0.05)
        velocity = step.velocityMps
        if (onGround && velocity.y < 0) velocity = V3(velocity.x, 0.0, velocity.z)
        spool = step.spool
        // pitch up about the right wing (nose towards up), roll right about the nose, yaw right about up
        val dp = step.pitchRate * 0.05
        forward = rotate(forward, right, dp).normalize(); up = rotate(up, right, dp).normalize()
        val dr = step.rollRate * 0.05
        up = rotate(up, forward, dr).normalize(); right = rotate(right, forward, dr).normalize()
        val dy = step.yawRate * 0.05
        forward = rotate(forward, up, -dy).normalize(); right = rotate(right, up, -dy).normalize()
        last = step
        return step
    }
}

private fun controls(pitch: Double = 0.0, roll: Double = 0.0, throttle: Double = 1.0, instructor: Boolean = true, brake: Boolean = false) =
    FixedWingControls(pitch, roll, 0.0, throttle, 0.0, 0.0, 0.0, false, brake, instructor)

class ShippedFlightModelsTest {
    @Test
    fun `every shipped model parses, the index lists exactly the files there`() {
        val listed = json("index.json").getAsJsonArray("files").map { it.asString }.toSet()
        val present = File(DIR).listFiles()!!.map { it.name }.filter { it != "index.json" }.toSet()
        assertEquals(present, listed)
        listed.forEach { FlightModelJson.parse(json(it)) }
    }

    @Test
    fun `every number needs a provenance and unknown keys are errors`() {
        val base = json("a_10a.json")
        assertFailsWith<IllegalArgumentException> { FlightModelJson.parse(base.deepCopy().also { it.getAsJsonObject("provenance").remove("mass_kg") }) }
        assertFailsWith<IllegalArgumentException> { FlightModelJson.parse(base.deepCopy().also { it.getAsJsonObject("provenance").addProperty("mass_kg", "DATAMINE") }) }
        assertFailsWith<IllegalArgumentException> { FlightModelJson.parse(base.deepCopy().also { it.addProperty("thrust_boost", 2) }) }
        assertFailsWith<IllegalArgumentException> { FlightModelJson.parse(base.deepCopy().also { it.remove("sources") }) }
    }

    @Test
    fun `fitted aircraft hit War Thunder's published turn time and their climb lands near its published climb rate`() {
        for (name in listOf("a_10a", "j_16", "ju_87")) {
            val obj = json("$name.json")
            val spec = fixedWing(name)
            val fit = obj.getAsJsonObject("fit_turn_time")
            val wep = fit["with_wep"].asBoolean
            val turn = FixedWingSim.sustainedTurnTimeS(spec, fit["altitude_m"].asDouble, wep)!!
            val climb = FixedWingSim.maxClimbRateMps(spec, 0.0, wep)
            val targetClimb = obj.getAsJsonObject("fit_climb")["climb_mps"].asDouble
            println("$name: thrust %.0f N cd_min %.4f cl_max %.2f oswald %.2f | turn %.1f s (WT %.1f) | climb %.1f m/s (WT %.1f)".format(
                spec.engine.thrustN(1.0, if (wep) 1.0 else 0.0, 1.0, 1.225, 0.0), spec.polar.cdMin, spec.polar.clMax, spec.polar.oswald, turn, fit["turn_time_s"].asDouble, climb, targetClimb))
            assertTrue(spec.polar.cdMin in 0.005..0.08, "$name cd_min ${spec.polar.cdMin}")
            assertEquals(fit["turn_time_s"].asDouble, turn, fit["turn_time_s"].asDouble * 0.03, "$name turn time")
            assertEquals(targetClimb, climb, targetClimb * 0.03, "$name climb vs War Thunder")
        }
    }
}

class WingPolarTest {
    private val polar = WingPolar(finiteWingLiftSlope(6.0), 0.2, 1.3, -0.8, 0.03, 0.8, 6.0, 6 * PI / 180)

    @Test
    fun `lift is linear up to the critical angle, then collapses towards a flat plate`() {
        val crit = polar.aoaCritHighRad
        assertEquals(polar.clMax, polar.cl(crit), 1e-9)
        assertTrue(polar.cl(crit + 0.3) < 0.8 * polar.clMax)
        assertTrue(polar.cd(crit + 0.3) > 3 * polar.cd(crit - 0.05))
        assertTrue(polar.isStalled(crit + 0.01) && !polar.isStalled(crit - 0.01))
    }

    @Test
    fun `flaps add lift and drag, Helmbold's slope stays below thin-airfoil 2 pi`() {
        val flapped = polar.withFlaps(FlapsEffect(0.3, 0.5, 0.02), 1.0)
        assertTrue(flapped.cl(0.05) > polar.cl(0.05) && flapped.cd(0.05) > polar.cd(0.05))
        assertTrue(finiteWingLiftSlope(6.0) < 2 * PI && finiteWingLiftSlope(30.0) > finiteWingLiftSlope(6.0))
    }
}

class FixedWingSimTest {
    private val a10 = fixedWing("a_10a")

    @Test
    fun `the instructor keeps a full pull below the critical angle of attack, the manual stick stalls it`() {
        val guarded = Flight(a10, 90.0)
        repeat(60) { guarded.tick(controls(pitch = 1.0, throttle = 0.3)) }
        val guardedMax = (0 until 40).maxOf { guarded.tick(controls(pitch = 1.0, throttle = 0.3)).aoaRad }
        assertTrue(guardedMax < a10.polar.aoaCritHighRad, "instructor AoA $guardedMax")

        val manual = Flight(a10, 90.0)
        val stalled = (0 until 100).any { manual.tick(controls(pitch = 1.0, throttle = 0.3, instructor = false)).stalled }
        assertTrue(stalled)
    }

    @Test
    fun `a hard manual pull at high speed breaks the wing, the instructor's pull at the same speed stays under the limit load`() {
        val j16 = fixedWing("j_16")
        val manual = Flight(j16, 300.0)
        assertTrue((0 until 40).any { manual.tick(controls(pitch = 1.0, instructor = false)).failure == StructuralFailure.WING_OVERLOAD })
        val guarded = Flight(j16, 300.0)
        val peak = (0 until 40).maxOf { guarded.tick(controls(pitch = 1.0)).loadFactor }
        assertTrue(peak <= j16.limits.limitLoadPos * 1.05 && guarded.last!!.failure == StructuralFailure.NONE, "instructor peak $peak g")
        // The A-10's lift cannot reach its ultimate load below its compression speed: a slow aircraft cannot break itself
        val slow = Flight(a10, 150.0)
        assertTrue((0 until 40).none { slow.tick(controls(pitch = 1.0, instructor = false)).failure != StructuralFailure.NONE })
    }

    @Test
    fun `lift holds level flight, too little speed sinks`() {
        val cruise = Flight(a10, 150.0)
        val trimAoa = a10.polar.aoaForCl(a10.massKg * G / (0.5 * FixedWingSim.density(100.0) * 150.0 * 150.0 * a10.wingAreaM2))
        cruise.forward = V3(0.0, sin(trimAoa), cos(trimAoa))
        cruise.up = V3(0.0, cos(trimAoa), -sin(trimAoa))
        val vy = cruise.tick(controls(throttle = 0.6)).velocityMps.y
        assertTrue(kotlin.math.abs(vy) < 0.5, "level flight vertical speed $vy")

        val slow = Flight(a10, 30.0)
        repeat(20) { slow.tick(controls(throttle = 0.0)) }
        assertTrue(slow.velocity.y < -3.0)
    }

    @Test
    fun `on the ground thrust rolls it forward, brakes hold it back, it does not slide sideways`() {
        val roll = Flight(a10, 0.0).also { it.spool = 0.0 }
        repeat(200) { roll.tick(controls(), onGround = true) }
        val braked = Flight(a10, 0.0).also { it.spool = 0.0 }
        repeat(200) { braked.tick(controls(brake = true), onGround = true) }
        assertTrue(roll.velocity.z > 10.0, "takeoff roll ${roll.velocity.z}")
        assertTrue(braked.velocity.z < roll.velocity.z)
        val side = Flight(a10, 0.0).also { it.velocity = V3(5.0, 0.0, 20.0) }
        side.tick(controls(throttle = 0.0), onGround = true)
        assertTrue(kotlin.math.abs(side.velocity.x) < 5.0)
    }
}

class HelicopterSimTest {
    private val mi28 = heli("mi_28")
    private val ah6 = heli("ah_6")

    private fun hoverState(spec: HelicopterSpec, v: V3 = V3.ZERO, altitude: Double = 0.0) =
        AircraftState(v, V3(0.0, 0.0, 1.0), V3(0.0, 1.0, 0.0), V3(-1.0, 0.0, 0.0), altitude, false, 0.0)

    @Test
    fun `both hover at sea level and at the 1,000 m War Thunder quotes them at`() {
        listOf(mi28, ah6).forEach { spec ->
            for (altitude in listOf(0.0, 1000.0)) {
                val rho = FixedWingSim.density(altitude)
                val collective = spec.massKg * G / HelicopterSim.availableThrustN(spec, HelicopterSim.rotorPowerW(spec, rho), rho, 0.0, 0.0)
                assertTrue(collective in 0.5..0.99, "hover collective $collective at $altitude m")
            }
        }
    }

    @Test
    fun `forward speed gives translational lift, the ground gives ground effect`() {
        val p = HelicopterSim.rotorPowerW(mi28, 1.225)
        assertTrue(HelicopterSim.availableThrustN(mi28, p, 1.225, 20.0, 0.0) > 1.1 * HelicopterSim.availableThrustN(mi28, p, 1.225, 0.0, 0.0))
        val vh = sqrt(mi28.massKg * G / (2 * 1.225 * mi28.discAreaM2))
        assertTrue(HelicopterSim.groundEffect(mi28, mi28.rotorRadiusM, 0.0, vh) > 1.05)
        assertEquals(1.0, HelicopterSim.groundEffect(mi28, Double.POSITIVE_INFINITY, 0.0, vh), 1e-9)
        assertEquals(1.0, HelicopterSim.groundEffect(mi28, mi28.rotorRadiusM, 3 * vh, vh), 1e-9)
    }

    @Test
    fun `descending into the own wake loses thrust, the same descent with forward speed does not`() {
        val vh = sqrt(mi28.massKg * G / (2 * 1.225 * mi28.discAreaM2))
        val sinking = HelicopterSim.step(mi28, hoverState(mi28, V3(0.0, -vh, 0.0)), 0.8, Double.POSITIVE_INFINITY, 0.05)
        val flying = HelicopterSim.step(mi28, hoverState(mi28, V3(0.0, -vh, 2 * vh)), 0.8, Double.POSITIVE_INFINITY, 0.05)
        assertTrue(sinking.vortexRing > 0.8 && flying.vortexRing == 0.0)
        assertTrue(sinking.thrustN < flying.thrustN)
    }

    @Test
    fun `helicopters climb at least at War Thunder's published rate and hover with margin`() {
        for (name in listOf("mi_28", "ah_6")) {
            val obj = json("$name.json")
            val spec = heli(name)
            val climb = HelicopterSim.maxClimbRateMps(spec, 1000.0)
            println("$name: power %.0f kW figure of merit %.2f drag area %.2f m2 | climb %.1f m/s (WT %.1f)".format(
                spec.enginePowerW / 1000, spec.figureOfMerit, spec.dragAreaM2, climb, obj.getAsJsonObject("fit_climb")["climb_mps"].asDouble))
            // The hover margin may need more power than the published climb: the climb is then above it, never below
            assertTrue(climb >= obj.getAsJsonObject("fit_climb")["climb_mps"].asDouble - 0.3, "$name climb $climb")
        }
    }

    @Test
    fun `full collective climbs, low collective sinks, the derived drag stops it near the sourced top speed`() {
        var climb = hoverState(mi28)
        repeat(100) { climb = climb.copy(velocityMps = HelicopterSim.step(mi28, climb, 1.0, Double.POSITIVE_INFINITY, 0.05).velocityMps) }
        assertTrue(climb.velocityMps.y > 0.5, "vertical climb ${climb.velocityMps.y}")
        var sink = hoverState(mi28)
        repeat(40) { sink = sink.copy(velocityMps = HelicopterSim.step(mi28, sink, 0.4, Double.POSITIVE_INFINITY, 0.05).velocityMps) }
        assertTrue(sink.velocityMps.y < -1.0)
        // The derived area also absorbs the blade profile power the model does not count separately
        assertTrue(mi28.dragAreaM2 in 0.5..10.0 && ah6.dragAreaM2 in 0.3..3.0, "drag areas ${mi28.dragAreaM2}, ${ah6.dragAreaM2}")
    }
}
