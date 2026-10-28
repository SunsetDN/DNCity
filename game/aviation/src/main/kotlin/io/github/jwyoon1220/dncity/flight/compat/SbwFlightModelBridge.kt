// AGENT-DONE(claude): aircraft-flight-model
package io.github.jwyoon1220.dncity.flight.compat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.ExternalFlightModel
import io.github.jwyoon1220.dncity.ballistics.flight.MinecraftUnits
import io.github.jwyoon1220.dncity.ballistics.terminal.V3
import io.github.jwyoon1220.dncity.flight.aircraft.AircraftState
import io.github.jwyoon1220.dncity.flight.aircraft.FixedWingControls
import io.github.jwyoon1220.dncity.flight.aircraft.FixedWingSim
import io.github.jwyoon1220.dncity.flight.aircraft.FlightModel
import io.github.jwyoon1220.dncity.flight.aircraft.FlightModels
import io.github.jwyoon1220.dncity.flight.aircraft.HelicopterSim
import io.github.jwyoon1220.dncity.flight.aircraft.StructuralFailure
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Drives SuperbWarfare's aircraft with [FixedWingSim] / [HelicopterSim] for every vehicle that has a model in [FlightModels]
 * (SuperbWarfare's `ExternalFlightModel` hook; vehicles without one keep SuperbWarfare's flight).
 *
 * Attitude uses SuperbWarfare's render convention, `Ry(-yRot) Rx(xRot) Rz(roll)` with the nose on +Z, up on +Y and the right wing on
 * -X, so the body axes here are exactly the ones the model is drawn with. Fixed wing: the body rates the model returns are applied
 * as local rotations. Helicopter: SuperbWarfare keeps its cyclic/attitude logic, the model only replaces rotor thrust and drag.
 *
 * The mouse-aim instructor (War Thunder's idea; the law here is our own): it banks toward the cursor's side and pulls toward it,
 * levels the wings when the cursor is centred, and limits the angle of attack (in [FixedWingSim]). Holding W/S takes the stick
 * without a limiter, A/D the roll, Q/E the rudder.
 *
 * A structural failure (over-G wing failure, overspeed) leaves the aircraft a wreck: its health drops under SuperbWarfare's 10 %
 * threshold, where SuperbWarfare's own uncontrolled-fall logic takes over.
 */
object SbwFlightModelBridge : ExternalFlightModel {
    private const val TICK_S = MinecraftUnits.TICK_S

    /** Engine spool per aircraft (the one bit of model state SuperbWarfare does not keep). */
    private val spool: MutableMap<VehicleEntity, Double> = Collections.synchronizedMap(WeakHashMap())

    /** Strongest bank the instructor asks for (design), rad. */
    private const val INSTRUCTOR_MAX_BANK = 1.4

    /** Instructor gains (design): stick per radian of bank error, stick per unit of cursor offset. */
    private const val INSTRUCTOR_ROLL_GAIN = 4.0
    private const val INSTRUCTOR_PITCH_GAIN = 4.0

    /** Nosewheel steering at taxi speed (design), rad/s at full input. */
    private const val NOSEWHEEL_RATE = 0.6

    fun register() {
        ExternalFlightModel.model = this
    }

    private fun modelOf(vehicle: VehicleEntity): FlightModel? = FlightModels.byVehicle[BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type).toString()]

    override fun supports(vehicle: VehicleEntity): Boolean = modelOf(vehicle) != null

    private fun orientation(vehicle: VehicleEntity): Quaterniond =
        Quaterniond().rotateYXZ(Math.toRadians(-vehicle.yRot.toDouble()), Math.toRadians(vehicle.xRot.toDouble()), Math.toRadians(vehicle.roll.toDouble()))

    private fun axis(q: Quaterniond, x: Double, y: Double, z: Double): V3 = q.transform(Vector3d(x, y, z)).let { V3(it.x, it.y, it.z) }

    private fun state(vehicle: VehicleEntity, q: Quaterniond): AircraftState {
        val dm = vehicle.deltaMovement
        return AircraftState(
            velocityMps = V3(MinecraftUnits.blocksPerTickToMps(dm.x), MinecraftUnits.blocksPerTickToMps(dm.y), MinecraftUnits.blocksPerTickToMps(dm.z)),
            forward = axis(q, 0.0, 0.0, 1.0),
            up = axis(q, 0.0, 1.0, 0.0),
            right = axis(q, -1.0, 0.0, 0.0),
            altitudeM = MinecraftUnits.altitudeM(vehicle.y),
            onGround = vehicle.onGround(),
            spool = spool[vehicle] ?: 0.0,
        )
    }

    private fun setVelocity(vehicle: VehicleEntity, v: V3) {
        vehicle.deltaMovement = Vec3(MinecraftUnits.mpsToBlocksPerTick(v.x), MinecraftUnits.mpsToBlocksPerTick(v.y), MinecraftUnits.mpsToBlocksPerTick(v.z))
    }

    override fun tickFixedWing(vehicle: VehicleEntity, controls: ExternalFlightModel.FixedWingInput) {
        val spec = (modelOf(vehicle) as? FlightModel.FixedWing)?.spec ?: return
        val q = orientation(vehicle)
        val s = state(vehicle, q)

        // Instructor (mouse aim) or manual stick
        val bank = atan2(-s.right.y, s.up.y)
        val aimX = controls.aimX.toDouble().let { if (abs(it) < 0.02) 0.0 else it }
        val aimY = controls.aimY.toDouble().let { if (abs(it) < 0.02) 0.0 else it }
        val wantBank = if (aimX == 0.0) 0.0 else atan2(aimX, (-aimY).coerceAtLeast(0.0) + 0.15).coerceIn(-INSTRUCTOR_MAX_BANK, INSTRUCTOR_MAX_BANK)
        val roll = if (controls.manualRoll != 0f) controls.manualRoll.toDouble() else ((wantBank - bank) * INSTRUCTOR_ROLL_GAIN).coerceIn(-1.0, 1.0)
        val manualPitch = controls.manualPitch != 0f
        val pitch = if (manualPitch) controls.manualPitch.toDouble()
        else ((-aimY + abs(aimX) * cos(wantBank - bank).coerceAtLeast(0.0) * 0.8) * INSTRUCTOR_PITCH_GAIN).coerceIn(-1.0, 1.0)
        val yaw = (controls.rudder + 0.15f * controls.aimX).toDouble().coerceIn(-1.0, 1.0)

        val step = FixedWingSim.step(
            spec, s,
            FixedWingControls(
                pitch = pitch, roll = roll, yaw = yaw,
                throttle = controls.throttle.toDouble(), wep = controls.wep.toDouble(),
                flaps = controls.flaps.toDouble(), gear = controls.gear.toDouble(),
                airbrake = controls.airbrake, wheelBrake = controls.wheelBrake, instructor = !manualPitch,
            ),
            TICK_S,
        )
        spool[vehicle] = step.spool

        var yawRate = step.yawRate
        if (s.onGround) {
            val taxi = (s.velocityMps.length() / 5.0).coerceIn(0.0, 1.0)
            yawRate += (controls.rudder + controls.aimX).toDouble().coerceIn(-1.0, 1.0) * NOSEWHEEL_RATE * taxi
        }
        q.rotateX(-step.pitchRate * TICK_S)
        q.rotateY(-yawRate * TICK_S)
        q.rotateZ(step.rollRate * TICK_S)
        applyOrientation(vehicle, q)
        if (s.onGround) {
            // Sitting on the gear: wings level, the nose settles unless the elevator is lifting it
            vehicle.setZRot(vehicle.roll * 0.8f)
            if (step.pitchRate <= 0.0) vehicle.xRot = vehicle.xRot * 0.9f
        }
        setVelocity(vehicle, step.velocityMps)

        if (vehicle.level().isClientSide) return
        when (step.failure) {
            StructuralFailure.WING_OVERLOAD -> fail(vehicle, Component.translatable("message.dncity.flight.wing_overload", "%.1f".format(step.loadFactor)))
            StructuralFailure.OVERSPEED -> fail(vehicle, Component.translatable("message.dncity.flight.overspeed", Mth.floor(step.iasMps * 3.6)))
            StructuralFailure.NONE -> Unit
        }
        if (step.flapsOverspeed && vehicle.flapsDown) {
            vehicle.flapsDown = false
            notify(vehicle, Component.translatable("message.dncity.flight.flaps_overspeed"))
        }
        if (step.gearOverspeed && vehicle.tickCount % 20 == 0) notify(vehicle, Component.translatable("message.dncity.flight.gear_overspeed"))
    }

    override fun tickHelicopter(vehicle: VehicleEntity, collective: Float) {
        val spec = (modelOf(vehicle) as? FlightModel.Helicopter)?.spec ?: return
        val q = orientation(vehicle)
        val s = state(vehicle, q)
        val step = HelicopterSim.step(spec, s, collective.toDouble(), rotorHeight(vehicle, spec.rotorRadiusM), TICK_S)
        setVelocity(vehicle, step.velocityMps)
        if (vehicle.level().isClientSide) return
        when (step.failure) {
            StructuralFailure.WING_OVERLOAD -> fail(vehicle, Component.translatable("message.dncity.flight.rotor_overload", "%.1f".format(step.loadFactor)))
            StructuralFailure.OVERSPEED -> fail(vehicle, Component.translatable("message.dncity.flight.overspeed", Mth.floor(step.iasMps * 3.6)))
            StructuralFailure.NONE -> Unit
        }
    }

    /** Height of the rotor hub (top of the vehicle) above whatever is below it, solid or liquid; infinite beyond 4 rotor radii. */
    private fun rotorHeight(vehicle: VehicleEntity, rotorRadiusM: Double): Double {
        val hub = vehicle.position().add(0.0, vehicle.bbHeight.toDouble(), 0.0)
        val reach = 4.0 * rotorRadiusM
        val hit = vehicle.level().clip(ClipContext(hub, hub.add(0.0, -reach, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, vehicle))
        return if (hit.type == HitResult.Type.MISS) Double.POSITIVE_INFINITY else hub.y - hit.location.y
    }

    private fun applyOrientation(vehicle: VehicleEntity, q: Quaterniond) {
        val e = q.getEulerAnglesYXZ(Vector3d())
        val yaw = -Math.toDegrees(e.y).toFloat()
        vehicle.yRot = vehicle.yRot + Mth.wrapDegrees(yaw - vehicle.yRot)
        vehicle.xRot = Math.toDegrees(e.x).toFloat()
        vehicle.setZRot(Math.toDegrees(e.z).toFloat())
    }

    /** Wreck it: below 10 % health SuperbWarfare's uncontrolled fall takes over. */
    private fun fail(vehicle: VehicleEntity, message: Component) {
        val wreck = vehicle.getMaxHealth() * 0.09f
        if (vehicle.health <= wreck) return
        vehicle.health = wreck
        notify(vehicle, message)
    }

    private fun notify(vehicle: VehicleEntity, message: Component) {
        (vehicle.firstPassenger as? Player)?.displayClientMessage(message, true)
    }
}
